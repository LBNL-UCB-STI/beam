package beam.router.skim

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicleType, VehicleCategory}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.geozone._
import beam.agentsim.infrastructure.taz.H3TAZ
import beam.router.BeamRouter.{RoutingFailure, RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, WALK, WALK_TRANSIT}
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.BeamServices
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes, PopulationAdjustment}
import beam.utils.ProfilingUtils
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

private case class Container(
  srcGeoIndex: GeoIndex,
  dstGeoIndex: GeoIndex,
  considerModes: Array[BeamMode],
  request: RoutingRequest,
  response: RoutingResponse
)

class PeakSkimCreator(val beamServices: BeamServices, val config: BeamConfig, val r5Router: ActorRef)
    extends StrictLogging {
  private val scenario: Scenario = beamServices.matsimServices.getScenario
  private val dummyPersonAttributes = createDummyPersonAttribute
  private val modeChoiceCalculator: ModeChoiceCalculator =
    beamServices.modeChoiceCalculatorFactory(dummyPersonAttributes)

  private val dummyCarVehicleType: BeamVehicleType = beamServices.beamScenario.vehicleTypes.values
    .find(theType => theType.vehicleCategory == VehicleCategory.Car && theType.maxVelocity.isEmpty)
    .get
  private val dummyBodyVehicleType: BeamVehicleType =
    beamServices.beamScenario.vehicleTypes.values.find(theType => theType.vehicleCategory == VehicleCategory.Body).get

  private val dummyBikeVehicleType: BeamVehicleType =
    beamServices.beamScenario.vehicleTypes.values.find(theType => theType.vehicleCategory == VehicleCategory.Bike).get

  private val wgsCoordinates = getAllActivitiesLocations.map(beamServices.geo.utm2Wgs(_)).map(WgsCoordinate.apply).toSet

  private val summary: GeoZoneSummary =
    TopDownEqualDemandGeoIndexMapper.from(new GeoZone(wgsCoordinates).includeBoundBoxPoints, 1000).generateSummary()

  logger.info(s"Created ${summary.items.length} H3 indexes from ${wgsCoordinates.size} unique coordinates")

  private val resolutionToPoints = summary.items
    .map(x => x.index.resolution -> x.size)
    .groupBy { case (res, _) => res }
    .toSeq
    .map { case (res, xs) => res -> xs.map(_._2).sum }
    .sortBy { case (_, size) => -size }
  resolutionToPoints.foreach {
    case (res, size) =>
      logger.info(s"Resolution: $res, number of points: $size")
  }

  private val transformation: GeotoolsTransformation =
    new GeotoolsTransformation(H3TAZ.H3Projection, beamServices.beamConfig.matsim.modules.global.coordinateSystem)

  private val h3Indexes = summary.items.sortBy(x => -x.size)

  private val h3IndexPairs = h3Indexes
    .flatMap { srcGeo =>
      h3Indexes.map { dstGeo =>
        (srcGeo.index, dstGeo.index)
      }
    }

  private val beamModes: Array[BeamMode] = Array(BeamMode.CAR, BeamMode.BIKE, BeamMode.WALK_TRANSIT)

  private val thresholdDistanceForBikeMeteres: Double = 20 * 1.60934 * 1E3 // 20 miles to meters

  private implicit val timeout: Timeout = Timeout(20000, TimeUnit.SECONDS)

  private implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  def write(iteration: Int): Unit = {
    try {
      val skimmer = new ODSkimmer(beamServices, config.beam.router.skim) {
        override def writeToDisk(event: IterationEndsEvent): Unit = {
          val filePath = event.getServices.getControlerIO.getIterationFilename(
            event.getServices.getIterationNumber,
            skimFileBaseName + ".UrbanSim.Full.csv.gz"
          )
          val hour = config.beam.urbansim.allTAZSkimsPeakHour.toInt
          val uniqueTimeBins: Seq[Int] = hour to hour
          val origins = h3Indexes.map { h3Index =>
            val wgsCenter = H3Wrapper.hexToCoord(h3Index.index)
            val utmCenter = beamServices.geo.wgs2Utm(wgsCenter)
            val areaInSquareMeters = H3Wrapper.hexAreaM2(h3Index.index.resolution)
            GeoUnit.H3(h3Index.index.value, utmCenter, areaInSquareMeters)
          }
          writeFullSkims(origins, origins, uniqueTimeBins, filePath)
          logger.info(
            s"Written UrbanSim peak skims for hour ${config.beam.urbansim.allTAZSkimsPeakHour} to ${filePath}"
          )
        }
      }
      ProfilingUtils.timed(s"Populate skims for ${h3Indexes.length}", logger.debug(_)) {
        populateSkimmer(skimmer)
      }
      ProfilingUtils.timed("Write skims to disk", logger.debug(_)) {
        skimmer.writeToDisk(new IterationEndsEvent(beamServices.matsimServices, iteration))
      }
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Something wrong during skims preparation/writing: ${ex.getMessage}", ex)
    }
  }

  def isDinstanceWithinRange(mode: BeamMode, dist: Double): Boolean = {
    mode match {
      case BeamMode.CAR          => true
      case BeamMode.WALK_TRANSIT => true
      case BeamMode.BIKE =>
        dist < thresholdDistanceForBikeMeteres
      case x => throw new IllegalStateException(s"Don't know what to do with $x")
    }
  }

  def createStreetVehicle(mode: BeamMode, requestTime: Int, srcCoord: Coord): StreetVehicle = {
    val streetVehicle: StreetVehicle = mode match {
      case BeamMode.CAR =>
        StreetVehicle(
          Id.createVehicleId("dummy-car-for-skim-observations"),
          dummyCarVehicleType.id,
          new SpaceTime(srcCoord, requestTime),
          mode,
          asDriver = true
        )
      case BeamMode.BIKE =>
        StreetVehicle(
          Id.createVehicleId("dummy-bike-for-skim-observations"),
          dummyBikeVehicleType.id,
          new SpaceTime(srcCoord, requestTime),
          mode,
          asDriver = true
        )
      case BeamMode.WALK_TRANSIT =>
        StreetVehicle(
          Id.createVehicleId("dummy-body-for-skim-observations"),
          dummyBodyVehicleType.id,
          new SpaceTime(srcCoord, requestTime),
          WALK,
          asDriver = true
        )
      case x =>
        throw new IllegalArgumentException(s"Get mode ${x}, but don't know what to do with it.")
    }
    streetVehicle
  }

  private def distanceWithMargin(srcCoord: Coord, dstCoord: Coord): Double = {
    GeoUtils.distFormula(srcCoord, dstCoord) * 1.4
  }

  private def getGeoIndexCenters(src: GeoIndex, dst: GeoIndex): (Coord, Coord) = {
    val srcGeoCenter = getGeoIndexCenter(src)
    val dstGeoCenter = getGeoIndexCenter(dst)
    val srcCoord = if (src == dst) {
      new Coord(srcGeoCenter.getX + Math.sqrt(H3Wrapper.hexAreaM2(src.resolution)) / 3.0, srcGeoCenter.getY)
    } else {
      srcGeoCenter
    }
    val dstCoord = if (src == dst) {
      new Coord(
        dstGeoCenter.getX - Math.sqrt(H3Wrapper.hexAreaM2(dst.resolution)) / 3.0,
        dstGeoCenter.getY
      )
    } else {
      dstGeoCenter
    }
    (srcCoord, dstCoord)
  }

  private def getGeoIndexCenter(geoIndex: GeoIndex): Coord = {
    val hexCentroid = H3Wrapper.hexToCoord(geoIndex)
    transformation.transform(hexCentroid)
  }

  private def populateSkimmer(skimmer: ODSkimmer): ODSkimmer = {
    val requestTime = (config.beam.urbansim.allTAZSkimsPeakHour * 3600).toInt
    logger.info(s"There are ${h3Indexes.length} H3 indexes")

    val processedAtomic = new AtomicInteger(0)
    val computedRoutes = new AtomicInteger(0)
    val failedRoutes = new AtomicInteger(0)
    val emptyItineraries = new AtomicInteger(0)

    val started = System.currentTimeMillis()

    val onePct = (h3IndexPairs.length.toDouble * 0.01).toInt
    logger.info(s"One percent from ${h3IndexPairs.length} is $onePct")
    val nonEmptyRoutesPerType = Map[BeamMode, AtomicInteger](
      BeamMode.CAR          -> new AtomicInteger(0),
      BeamMode.WALK_TRANSIT -> new AtomicInteger(0),
      BeamMode.BIKE         -> new AtomicInteger(0)
    )

    val requests = ProfilingUtils.timed("Creating routing requests", logger.info(_)) {
      // The most outer loop will be executed in parallel `.par`
      h3IndexPairs.par.map {
        case (srcGeoIndex, dstGeoIndex) =>
          val (srcCoord, dstCoord) = getGeoIndexCenters(srcGeoIndex, dstGeoIndex)
          val dist = distanceWithMargin(srcCoord, dstCoord)
          val considerModes = beamModes.filter(mode => isDinstanceWithinRange(mode, dist))
          val streetVehicles = considerModes.map(createStreetVehicle(_, requestTime, srcCoord))
          val routingReq = RoutingRequest(
            originUTM = srcCoord,
            destinationUTM = dstCoord,
            departureTime = requestTime,
            withTransit = true,
            streetVehicles = streetVehicles,
            attributesOfIndividual = Some(dummyPersonAttributes)
          )
          (srcGeoIndex, dstGeoIndex, considerModes, routingReq)
      }.seq
    }

    val futures = requests.map {
      case (src, dst, considerModes, routingReq) =>
        r5Router.ask(routingReq).map {
          case resp: RoutingResponse =>
            val processed = processedAtomic.getAndIncrement()
            if (processed > 0 && processed % onePct == 0) {
              val diff = System.currentTimeMillis() - started
              val rps = processed.toDouble / diff * 1000 // Average per second
              val pct = 100 * processed.toDouble / h3IndexPairs.length
              logger.info(
                s"Processed $processed routes, $pct % in $diff ms, AVG per second: $rps. Failed: ${failedRoutes
                  .get()}, empty: ${emptyItineraries.get()}, non-empty: ${computedRoutes.get}"
              )
              logger.info(s"Non-empty routes per mode: ")
              nonEmptyRoutesPerType.foreach {
                case (mode, counter) =>
                  logger.info(s"Non-empty route for $mode\t\t${counter.get()}")
              }
            }
            resp.itineraries.foreach { trip =>
              nonEmptyRoutesPerType.get(trip.tripClassifier).foreach(_.getAndIncrement())
            }
            Success(Container(src, dst, considerModes, routingReq, resp))
          case failure: RoutingFailure =>
            failedRoutes.getAndIncrement()
            Failure(failure.cause)
          case x =>
            Failure(new IllegalStateException(s"Didn't expect ${x.getClass} type here"))
        }
    }
    val waitDuration = 5.hours
    val results = ProfilingUtils.timed(s"Computed ${futures.length}", logger.info(_)) {
      Await.result(Future.sequence(futures), waitDuration)
    }
    var nSkimEvents: Int = 0
    results.foreach {
      case Success(
          Container(
            srcGeoIndex: GeoIndex,
            dstGeoIndex: GeoIndex,
            considerModes: Array[BeamMode],
            routingReq: RoutingRequest,
            response: RoutingResponse
          )
          ) =>
        if (response.itineraries.isEmpty)
          emptyItineraries.getAndIncrement()
        else
          computedRoutes.getAndIncrement()
        response.itineraries.foreach { trip =>
          if (considerModes.contains(trip.tripClassifier) && !isBikeTransit(trip)) {
            nonEmptyRoutesPerType.get(trip.tripClassifier).foreach(_.getAndIncrement())
            try {
              val event = createSkimEvent(srcGeoIndex, dstGeoIndex, trip.tripClassifier, requestTime, trip)
              skimmer.handleEvent(event)
              nSkimEvents += 1
            } catch {
              case NonFatal(ex) =>
                logger.error(s"Can't create skim event: ${ex.getMessage}", ex)
            }
          }
        }
      case _ =>
    }
    logger.info(s"Total number of skim events: $nSkimEvents, failed routes: ${failedRoutes
      .get()}, empty responses: ${emptyItineraries.get()}, computed in ${System.currentTimeMillis() - started} ms")
    nonEmptyRoutesPerType.foreach {
      case (mode, counter) =>
        logger.info(s"Non-empty route for $mode\t\t${counter.get()}")
    }
    skimmer
  }

  private def createSkimEvent(
    origin: GeoIndex,
    destination: GeoIndex,
    beamMode: BeamMode,
    requestTime: Int,
    trip: EmbodiedBeamTrip
  ): ODSkimmerEvent = {
    // In case of CAR AND BIKE we have to create two dummy legs: walk to the CAR in the beginning and walk when CAR has arrived
    val theTrip = if (beamMode == BeamMode.CAR || beamMode == BeamMode.BIKE) {
      val actualLegs = trip.legs
      EmbodiedBeamTrip(
        EmbodiedBeamLeg.dummyLegAt(
          start = actualLegs.head.beamLeg.startTime,
          vehicleId = Id.createVehicleId("dummy-body"),
          isLastLeg = false,
          location = actualLegs.head.beamLeg.travelPath.startPoint.loc,
          mode = WALK,
          vehicleTypeId = dummyBodyVehicleType.id
        ) +:
        actualLegs :+
        EmbodiedBeamLeg.dummyLegAt(
          start = actualLegs.last.beamLeg.endTime,
          vehicleId = Id.createVehicleId("dummy-body"),
          isLastLeg = true,
          location = actualLegs.last.beamLeg.travelPath.endPoint.loc,
          mode = WALK,
          vehicleTypeId = dummyBodyVehicleType.id
        )
      )
    } else {
      trip
    }
    val generalizedTime =
      modeChoiceCalculator.getGeneralizedTimeOfTrip(theTrip, Some(dummyPersonAttributes), None)
    val generalizedCost = modeChoiceCalculator.getNonTimeCost(theTrip) + dummyPersonAttributes.getVOT(generalizedTime)
    val energyConsumption = dummyCarVehicleType.primaryFuelConsumptionInJoulePerMeter * theTrip.legs
      .map(_.beamLeg.travelPath.distanceInM)
      .sum
    ODSkimmerEvent(
      origin = origin.value,
      destination = destination.value,
      eventTime = requestTime,
      trip = theTrip,
      generalizedTimeInHours = generalizedTime,
      generalizedCost = generalizedCost,
      energyConsumption = energyConsumption,
      skimName = beamServices.beamConfig.beam.router.skim.origin_destination_skimmer.name
    )
  }

  private def createDummyPersonAttribute: AttributesOfIndividual = {
    val medianHouseholdByIncome = scenario.getHouseholds.getHouseholds
      .values()
      .asScala
      .toList
      .sortBy(_.getIncome.getIncome)
      .drop(scenario.getHouseholds.getHouseholds.size() / 2)
      .head
    val dummyHouseholdAttributes = new HouseholdAttributes(
      householdId = medianHouseholdByIncome.getId.toString,
      householdIncome = medianHouseholdByIncome.getIncome.getIncome,
      householdSize = 1,
      numCars = 1,
      numBikes = 1
    )
    val personVOTT = PopulationAdjustment
      .incomeToValueOfTime(dummyHouseholdAttributes.householdIncome)
      .getOrElse(config.beam.agentsim.agents.modalBehaviors.defaultValueOfTime)
    AttributesOfIndividual(
      householdAttributes = dummyHouseholdAttributes,
      modalityStyle = None,
      isMale = true,
      availableModes = Seq(CAR, WALK_TRANSIT, BIKE),
      valueOfTime = personVOTT,
      age = None,
      income = Some(dummyHouseholdAttributes.householdIncome)
    )
  }

  private def getAllActivitiesLocations: Iterable[Coord] = {
    beamServices.matsimServices.getScenario.getPopulation.getPersons
      .values()
      .asScala
      .flatMap { person =>
        person.getSelectedPlan.getPlanElements.asScala.collect {
          case act: Activity => act.getCoord
        }
      }
  }

  private def isBikeTransit(trip: EmbodiedBeamTrip): Boolean = {
    trip.tripClassifier == BeamMode.WALK_TRANSIT && trip.beamLegs.exists(leg => leg.mode == BeamMode.BIKE)
  }
}
