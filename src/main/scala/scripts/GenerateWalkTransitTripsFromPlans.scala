package scripts

import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.RoutingRequest
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{WALK, WALK_TRANSIT}
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.r5.{R5Parameters, R5Wrapper}
import beam.router.{BeamRouter, FreeFlowTravelTime}
import beam.sim.config.{BeamConfig, BeamConfigHolder}
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import beam.sim.{BeamConfigChangesObservable, BeamHelper, BeamServices}
import beam.utils.NetworkHelperImpl
import beam.utils.csv.{CsvWriter, GenericCsvReader}
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.config.Config
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.events.EventsManagerImpl

import java.util
import scala.collection.parallel.ParSeq
import scala.util.Try

object GenerateWalkTransitTripsFromPlans extends BeamHelper {

  case class PersonTrip(
    personId: String,
    trip: EmbodiedBeamTrip,
    alternatives: Seq[String],
    planElementIndexes: String
  )

  case class Trip(
    personId: String,
    origin: Coord,
    destination: Coord,
    mode: BeamMode,
    departureTime: Int,
    planElementIndexes: String
  )

  case class PlanElement(
    personId: String,
    location: Option[Coord],
    legMode: Option[BeamMode],
    activityEndTime: Option[Int],
    planElementIndex: Int
  ) {
    def isLeg: Boolean = legMode.nonEmpty
    def isActivity: Boolean = location.nonEmpty
  }

  object PlanElement {

    def fromMap(csvRow: util.Map[String, String]): PlanElement = {
      val personId = csvRow.get("personId")
      val planElementIndex = csvRow.get("planElementIndex").toInt
      val location = {
        val x = Try { csvRow.get("activityLocationX").toDouble }.toOption
        val y = Try { csvRow.get("activityLocationY").toDouble }.toOption
        if (x.nonEmpty && y.nonEmpty) {
          Some(new Coord(x.get, y.get))
        } else {
          None
        }
      }
      // double -> int here is because activityEndTime sometimes written as double
      val activityEndTime = Try { csvRow.get("activityEndTime").toDouble.toInt }.toOption
      val legMode = Try { BeamMode.fromString(csvRow.get("legMode")) }.getOrElse(None)
      PlanElement(personId, location, legMode, activityEndTime, planElementIndex)
    }
  }

  def readGeneratedPlansTrips(inputPlanPath: String): Seq[Trip] = {
    val (rdr, toClose) =
      GenericCsvReader.readAs[PlanElement](inputPlanPath, PlanElement.fromMap, _ => true)
    val planElementArray =
      try { rdr.toArray }
      finally { toClose.close() }

    val trips = planElementArray.sliding(3).flatMap { case Array(activity1, leg, activity2) =>
      def isActivityLegActivity = leg.isLeg && activity1.isActivity && activity2.isActivity
      def isForTheSamePerson = activity1.personId == leg.personId && leg.personId == activity2.personId
      if (isActivityLegActivity && isForTheSamePerson && activity1.activityEndTime.nonEmpty) {
        Some(
          Trip(
            personId = activity1.personId,
            origin = activity1.location.get,
            destination = activity2.location.get,
            mode = leg.legMode.get,
            departureTime = activity1.activityEndTime.get,
            leg.planElementIndex.toString
          )
        )
      } else {
        None
      }
    }

    trips.toSeq
  }

  private def createConfigs(configPath: String): Config = {
    val manualArgs = Array[String]("--config", configPath)
    val (_, cfg) = prepareConfig(manualArgs, isConfigArgRequired = true)
    cfg
  }

  private def createR5Wrappers(cfg: Config): Seq[R5Wrapper] = {
    val (workerParams: R5Parameters, maybeSecondRouterNetworks: Option[(TransportNetwork, Network)]) =
      R5Parameters.fromConfig(cfg)
    val travelTime = new FreeFlowTravelTime
    val noiseFraction = workerParams.beamConfig.beam.routing.r5.travelTimeNoiseFraction
    val firstRouter = new R5Wrapper(workerParams, travelTime, noiseFraction)
    val secondRouter: Option[R5Wrapper] = for {
      (transportNetwork, network) <- maybeSecondRouterNetworks
    } yield new R5Wrapper(
      workerParams.copy(transportNetwork = transportNetwork, networkHelper = new NetworkHelperImpl(network)),
      travelTime,
      noiseFraction
    )

    Seq(Some(firstRouter), secondRouter).flatten
  }

  def getRoutingRequest(originUTM: Coord, destinationUTM: Coord, departureTime: Int): RoutingRequest = {
    val bodyStreetVehicle: StreetVehicle = {
      StreetVehicle(
        id = Id.createVehicleId("dummy-body"),
        vehicleTypeId = Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
        locationUTM = SpaceTime(loc = originUTM, time = departureTime),
        mode = BeamMode.WALK,
        asDriver = true,
        needsToCalculateCost = false
      )
    }

    val personAttributes = AttributesOfIndividual(
      householdAttributes = HouseholdAttributes("dummyHousehold", 70000.0, 10, 0, 1),
      modalityStyle = None,
      isMale = true,
      availableModes = Seq(BeamMode.WALK, BeamMode.WALK_TRANSIT, BeamMode.BIKE),
      valueOfTime = 1,
      age = None,
      income = Some(70000.0)
    )
    val personId = Id.createPersonId(1)
    RoutingRequest(
      originUTM = originUTM,
      destinationUTM = destinationUTM,
      departureTime = departureTime,
      withTransit = true,
      streetVehicles = Vector(bodyStreetVehicle),
      personId = Some(personId),
      attributesOfIndividual = Some(personAttributes),
      triggerId = -1
    )
  }

  def getModeChoiceMNL(
    typeSafeConfig: Config,
    beamServices: BeamServices
  ): ModeChoiceCalculator = {
    val eventsManager = new EventsManagerImpl
    val beamConfig = BeamConfig(typeSafeConfig)
    val configHolder = new BeamConfigHolder(
      new BeamConfigChangesObservable(beamConfig, None),
      beamConfig
    )
    val modeChoiceMNL = ModeChoiceCalculator(
      beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass,
      beamServices,
      configHolder,
      eventsManager
    )

    modeChoiceMNL.apply(AttributesOfIndividual.EMPTY)
  }

  val walkTransitModes: Set[BeamMode] = (BeamMode.WALK_TRANSIT +: BeamMode.transitModes).toSet

  def selectBeamEmbodyWalkTransitTrip(
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    modeChoiceMNL: ModeChoiceCalculator
  ): Option[EmbodiedBeamTrip] = {
    val attributesOfIndividual = AttributesOfIndividual(
      HouseholdAttributes.EMPTY,
      None,
      isMale = false,
      Seq(WALK, WALK_TRANSIT),
      15.0,
      None,
      None
    )

    modeChoiceMNL.apply(
      alternatives,
      attributesOfIndividual,
      None,
      None
    )
  }

  def alternativesToStringSeq(alternatives: IndexedSeq[EmbodiedBeamTrip]): Seq[String] = {
    def getWalkTransitMode(beamTrip: EmbodiedBeamTrip): String = {
      beamTrip.legs.map(_.beamLeg.mode).filter(mode => mode != WALK) match {
        case seq: Seq[BeamMode] => seq.map(_.value).sorted.toSet.mkString("+")
        case _                  => WALK.value
      }
    }

    alternatives.map(beamTrip =>
      beamTrip.tripClassifier match {
        case WALK_TRANSIT => getWalkTransitMode(beamTrip)
        case tripMode     => tripMode.value
      }
    )
  }

  def generateWalkTransitTrips(
    pathToBeamConfig: String,
    pathToGeneratedPlans: String
  ): ParSeq[PersonTrip] = {
    val typeSafeConfig = createConfigs(pathToBeamConfig)
    val (_, _, _, beamServices: BeamServices, _) = prepareBeamService(typeSafeConfig, None)
    val modeChoiceMNL: ModeChoiceCalculator = getModeChoiceMNL(typeSafeConfig, beamServices)

    val inputTrips = readGeneratedPlansTrips(pathToGeneratedPlans)

    val routers: Seq[R5Wrapper] = createR5Wrappers(typeSafeConfig)
    val walkTransitLegs = inputTrips.filter { trip => walkTransitModes.contains(trip.mode) }.toArray
    println(s"There are ${walkTransitLegs.length} walk transit legs, amount of routers: ${routers.size}")

    var legsProcessed = 0
    // notification for each completed 10%
    val progressReportIncrement = Math.max(10 * (walkTransitLegs.length / 100), 1)
    var nextProgressReport: Int = progressReportIncrement
    val beginningTimeStamp: Long = System.currentTimeMillis / 1000
    println(s"Progress will be reported for each $progressReportIncrement legs processed.")

    val personTrips: ParSeq[PersonTrip] = walkTransitLegs.par.flatMap { trip: Trip =>
      val request: BeamRouter.RoutingRequest = getRoutingRequest(
        originUTM = trip.origin,
        destinationUTM = trip.destination,
        departureTime = trip.departureTime
      )
      val routes = routers.map(_.calcRoute(request, buildDirectCarRoute = false, buildDirectWalkRoute = false))
      val alternatives = routes.map(_.itineraries).toIndexedSeq.flatten
      val maybeTransitTrip = selectBeamEmbodyWalkTransitTrip(alternatives, modeChoiceMNL)
      val maybePersonTrip: Option[PersonTrip] = maybeTransitTrip match {
        case Some(embodiedBeamTrip) if embodiedBeamTrip.legs.nonEmpty =>
          Some(
            PersonTrip(
              trip.personId,
              embodiedBeamTrip,
              alternativesToStringSeq(alternatives),
              trip.planElementIndexes
            )
          )
        case _ => None
      }

      this.synchronized {
        legsProcessed += 1

        if (legsProcessed >= nextProgressReport) {
          val currentTimeStamp: Long = System.currentTimeMillis / 1000
          val tookTimeInMinutes = Math.round((currentTimeStamp - beginningTimeStamp) / 60.0)
          val currentProgress = (100.0 * legsProcessed) / walkTransitLegs.length
          val expectedTimeToCalculateTheRest =
            Math.round((100 - currentProgress) * (tookTimeInMinutes / currentProgress))
          val timeStats =
            s"$tookTimeInMinutes minutes took, $expectedTimeToCalculateTheRest minutes is expected to calculate the rest"
          println(
            s"Generation of person walk transit trips from legs: ${Math.round(currentProgress)}% completed. $timeStats"
          )
          nextProgressReport += progressReportIncrement
        }
      }

      maybePersonTrip
    }

    println(s"Generation of person walk transit trips from legs completed.")
    personTrips
  }

  def embodiedBeamLegToStringSeq(leg: EmbodiedBeamLeg): Seq[String] = {
    val csvColumnValues = Seq(
      leg.beamLeg.mode,
      leg.beamLeg.travelPath.startPoint.loc.getX,
      leg.beamLeg.travelPath.startPoint.loc.getY,
      leg.beamLeg.travelPath.endPoint.loc.getX,
      leg.beamLeg.travelPath.endPoint.loc.getY,
      leg.beamLeg.travelPath.transitStops.map(_.agencyId),
      leg.beamLeg.travelPath.transitStops.map(_.routeId),
      leg.beamLeg.travelPath.transitStops.map(_.vehicleId),
      leg.beamLeg.travelPath.transitStops.map(_.fromIdx),
      leg.beamLeg.travelPath.transitStops.map(_.toIdx),
      leg.beamLeg.startTime,
      leg.beamLeg.endTime
    ).map {
      case null | None => ""
      case Some(value) => value.toString
      case value       => value.toString
    }

    csvColumnValues
  }

  def writeTripsToFile(path: String, trips: Array[PersonTrip]): Unit = {
    val header = Seq(
      "personId",
      "mode",
      "startX",
      "startY",
      "endX",
      "endY",
      "transitAgency",
      "transitRouteId",
      "transitVehicle",
      "transitStopStart",
      "transitStopEnd",
      "startTime",
      "endTime",
      "planElementIndexOfLeg",
      "alternatives"
    )
    val csvWriter: CsvWriter = new CsvWriter(path, header)
    try {
      trips.foreach { case PersonTrip(personId, trip, alternatives, planElementIndexes) =>
        trip.legs
          .filter { leg => BeamMode.transitModes.contains(leg.beamLeg.mode) }
          .foreach { leg: EmbodiedBeamLeg =>
            csvWriter.writeRow(
              Seq(personId) ++ embodiedBeamLegToStringSeq(leg) ++ Seq(planElementIndexes, alternatives.mkString(" : "))
            )
          }
      }
    } finally {
      Try(csvWriter.close())
    }
  }

  def main(args: Array[String]): Unit = {
    println(s"Current arguments: ${args.mkString(",")}")
    if (args.length < 3) {
      println("Expected following arguments: <path to beam config> <path to generated plans> <path to output csv>")
    } else {
      val pathToConfig = args(0)
      val pathToGeneratedPlans = args(1)
      val pathToOutputCSV = args(2)

      println(s"Generation of person walk transit trips from generatedPlans started.")
      println(s"Beam config to create router: $pathToConfig")
      println(s"Path to generated plans: $pathToGeneratedPlans")
      println(s"Path to output: $pathToOutputCSV")

      val personTrips = generateWalkTransitTrips(pathToConfig, pathToGeneratedPlans)
      writeTripsToFile(pathToOutputCSV, personTrips.toArray)
    }
  }
}
