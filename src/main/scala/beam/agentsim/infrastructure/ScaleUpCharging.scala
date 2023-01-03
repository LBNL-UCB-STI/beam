package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.RefuelSessionEvent.NotApplicable
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ChargingNetworkManager._
import beam.agentsim.infrastructure.ParkingInquiry.ParkingSearchMode.{DestinationCharging, EnRouteCharging}
import beam.agentsim.infrastructure.ParkingInquiry.{activityTypeStringToEnum, ParkingActivityType, ParkingSearchMode}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.utils.MathUtils.roundUniformly
import beam.utils.scenario.HouseholdId
import beam.utils.{FileUtils, MathUtils, VehicleIdGenerator}
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.random.MersenneTwister
import org.apache.commons.math3.util.{Pair => CPair}
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import java.util.concurrent.atomic.AtomicReference
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.language.postfixOps
import scala.util.Random

trait ScaleUpCharging extends {
  this: ChargingNetworkManager =>

  import ScaleUpCharging._

  private val virtualParkingInquiries: TrieMap[Int, ParkingInquiry] = TrieMap()
  private val vehicleRequests = mutable.HashSet.empty[ChargingEvent]

  private lazy val timeStepByHour = beamConfig.beam.agentsim.chargingNetworkManager.timeStepInSeconds / 3600.0

  private lazy val scaleUpFactor: Double =
    if (!cnmConfig.scaleUp.enabled) 0.0
    else if (cnmConfig.scaleUp.expansionFactor >= 1.0) cnmConfig.scaleUp.expansionFactor - 1.0
    else throw new RuntimeException(s"scaleUp.expansionFactor == ${cnmConfig.scaleUp.expansionFactor} < 1.0")

  private lazy val tazTreeMap = getBeamServices.beamScenario.tazTreeMap

  private lazy val activitiesLocationMap: Map[Id[TAZ], Map[String, Vector[ActivityLocation]]] = {
    val persons = getBeamServices.matsimServices.getScenario.getPopulation.getPersons.asScala
    val activitiesByTAZ = ActivityLocation
      .readActivitiesLocation(cnmConfig.scaleUp.activitiesLocationFilePath)
      .filter(a =>
        a.location.getX != 0 && a.location.getY != 0 && a.activityType.nonEmpty && !persons.contains(a.personId)
      )
      .groupBy(_.tazId)
    tazTreeMap.getTAZs.foldLeft(Map.empty[Id[TAZ], Map[String, Vector[ActivityLocation]]]) { case (acc, taz) =>
      val locationsByActivityType: Map[String, Vector[ActivityLocation]] =
        activitiesByTAZ.get(taz.tazId).map(_.groupBy(_.activityType)).getOrElse(Map.empty)
      acc + (taz.tazId -> locationsByActivityType)
    }
  }

  override def loggedReceive: Receive = {
    case t @ TriggerWithId(PlanParkingInquiryTrigger(_, inquiry), triggerId) =>
      log.debug(s"Received PlanParkingInquiryTrigger: $t")
      virtualParkingInquiries.put(inquiry.requestId, inquiry)
      self ! inquiry.copy(triggerId = triggerId)
    case t @ TriggerWithId(PlanChargingUnplugRequestTrigger(tick, beamVehicle, personId), triggerId) =>
      log.debug(s"Received PlanChargingUnplugRequestTrigger: $t")
      self ! ChargingUnplugRequest(tick, personId, beamVehicle, triggerId)
      sender ! CompletionNotice(triggerId)
    case response @ ParkingInquiryResponse(stall, requestId, triggerId) =>
      log.debug(s"Received ParkingInquiryResponse: $response")
      val msgToScheduler = virtualParkingInquiries.remove(requestId) match {
        case Some(parkingInquiry) if stall.chargingPointType.isDefined =>
          log.debug(s"parking inquiry with requestId $requestId returned a stall with charging point.")
          val beamVehicle = parkingInquiry.beamVehicle.get
          val endTime = (parkingInquiry.destinationUtm.time + parkingInquiry.parkingDuration).toInt
          val personId =
            parkingInquiry.personId.map(Id.create(_, classOf[Person])).getOrElse(Id.create("", classOf[Person]))
          self ! ChargingPlugRequest(
            parkingInquiry.destinationUtm.time,
            beamVehicle,
            stall,
            personId,
            triggerId,
            self,
            NotApplicable,
            None
          )
          ScheduleTrigger(PlanChargingUnplugRequestTrigger(endTime, beamVehicle, personId), self)
        case Some(_) if stall.chargingPointType.isEmpty =>
          log.debug(s"parking inquiry with requestId $requestId returned a NoCharger stall")
          CompletionNotice(triggerId)
        case _ =>
          log.error(s"inquiryMap does not have this requestId $requestId that returned stall $stall")
          CompletionNotice(triggerId)
      }
      getScheduler ! msgToScheduler
    case reply: StartingRefuelSession =>
      log.debug(s"Received StartingRefuelSession: $reply")
      getScheduler ! CompletionNotice(reply.triggerId)
    case reply: WaitingToCharge =>
      log.debug(s"Received WaitingToCharge: $reply")
      getScheduler ! CompletionNotice(reply.triggerId)
    case reply: EndingRefuelSession =>
      log.debug(s"Received EndingRefuelSession: $reply")
    case reply @ UnhandledVehicle(tick, personId, vehicle, _) =>
      log.error(s"Received UnhandledVehicle: $reply")
      handleReleasingParkingSpot(tick, personId, vehicle, None)
    case reply @ UnpluggingVehicle(tick, personId, vehicle, _, energyCharged) =>
      log.debug(s"Received UnpluggingVehicle: $reply")
      handleReleasingParkingSpot(tick, personId, vehicle, Some(energyCharged))
  }

  /**
    * Next Time poisson
    *
    * @param rate rate of charging event
    * @return
    */
  private def nextTimeStepUsingPoissonProcess(rate: Double, rand: Random): Int =
    roundUniformly(3600.0 * (-Math.log(1.0 - rand.nextDouble()) / rate), rand).toInt

  private def handleReleasingParkingSpot(
    tick: Int,
    personId: Id[_],
    vehicle: BeamVehicle,
    energyChargedMaybe: Option[Double]
  ): Unit = {
    ParkingNetworkManager.handleReleasingParkingSpot(
      tick,
      vehicle,
      energyChargedMaybe,
      personId,
      getParkingManager,
      getBeamServices.matsimServices.getEvents
    )
  }

  /**
    * @param timeBin   current time bin
    * @param triggerId trigger di for the scheduler
    * @return
    */
  protected def simulateEventsIfScalingEnabled(timeBin: Int, triggerId: Long): Vector[ScheduleTrigger] = {
    vehicleRequests
      .groupBy(t => tazTreeMap.getTAZ(t.tazId))
      .par
      .mapValues { data =>
        val rand = new Random(beamConfig.matsim.modules.global.randomSeed)
        val pmfObservedActivities =
          data
            .groupBy(_.activityType)
            .map { case (activityType, elems) =>
              val listDur = elems.map(_.parkingDurationInSec)
              val numObservation = listDur.size
              val totDurationInSec = listDur.sum
              val meanDur: Double = listDur.sum / numObservation.toDouble
              val varianceDur: Double = listDur.map(d => d - meanDur).map(t => t * t).sum / numObservation
              val parkingActivityType: ParkingActivityType = activityTypeStringToEnum(activityType)
              val scaledUpObservation: Double = scaleUpFactor * numObservation
              val activities = ObservedActivities(
                activityType,
                numObservation,
                totDurationInSec,
                meanDur,
                varianceDur,
                parkingActivityType,
                scaledUpObservation,
                rand
              )
              new CPair[ObservedActivities, java.lang.Double](activities, activities.numObservation.toDouble)
            }
            .toVector
        val pmfObservedVehicleTypes = data
          .groupBy(record => (record.vehicleType, record.vehicleAlias, record.reservedFor))
          .map { case ((vehicleType, vehicleAlias, reservedFor), elems) =>
            val listRemainingRange = elems.map(_.remainingRangeInMeters)
            val numObservation = listRemainingRange.size
            val meanRangeInMeters: Double = listRemainingRange.sum / numObservation.toDouble
            val varianceRange: Double =
              listRemainingRange.map(range => range - meanRangeInMeters).map(t => t * t).sum / numObservation
            val vehicleTypes =
              ObservedVehicleTypes(
                vehicleType,
                vehicleAlias,
                reservedFor,
                numObservation,
                meanRangeInMeters,
                varianceRange,
                rand
              )
            new CPair[ObservedVehicleTypes, java.lang.Double](vehicleTypes, vehicleTypes.numObservation.toDouble)
          }
          .toVector
        val totNumberOfEvents = pmfObservedActivities.map(_.getKey.scaledUpNumObservation).sum
        (totNumberOfEvents, pmfObservedActivities, pmfObservedVehicleTypes, rand)
      }
      .flatMap {
        case (tazMaybe, (totNumberOfEvents, pmfObservedActivities, pmfObservedVehicleTypes, rand))
            if tazMaybe.isDefined && totNumberOfEvents > 0 =>
          val parkingInquiriesTriggers = Vector.newBuilder[ScheduleTrigger]
          val mersenne: MersenneTwister = new MersenneTwister(beamConfig.matsim.modules.global.randomSeed)
          val activitiesDistribution =
            new EnumeratedDistribution[ObservedActivities](mersenne, pmfObservedActivities.asJava)
          val vehicleTypesDistribution =
            new EnumeratedDistribution[ObservedVehicleTypes](mersenne, pmfObservedVehicleTypes.asJava)
          val rate = totNumberOfEvents / timeStepByHour
          val taz = tazMaybe.get
          val activitiesLocationInCurrentTAZ: Map[String, Vector[ActivityLocation]] =
            activitiesLocationMap.getOrElse(taz.tazId, Map.empty)
          var cumulatedNumberOfEvents = 0
          var timeStep = 0
          while (cumulatedNumberOfEvents < totNumberOfEvents && timeStep < timeStepByHour * 3600) {
            val activitiesSample = activitiesDistribution.sample()
            val vehicleTypesSample = vehicleTypesDistribution.sample()
            val duration = Math.max(
              activitiesSample.getDuration,
              beamConfig.beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds.toInt
            )
            timeStep += nextTimeStepUsingPoissonProcess(rate, rand)
            val remainingRangeInMeters = vehicleTypesSample.getRemainingRangeInMeters
            val reservedFor = vehicleTypesSample.reservedFor
            val activityType = activitiesSample.activityType
            val beamVehicle = createBeamVehicle(vehicleTypesSample, remainingRangeInMeters, rand)
            val (personId, destinationUtm) =
              activitiesLocationInCurrentTAZ
                .get(activityType)
                .map { activities =>
                  val location = activities(rand.nextInt(activities.size)).location
                  (createPerson(beamVehicle.id), getBeamServices.geo.wgs2Utm(location))
                }
                .getOrElse((createPerson(beamVehicle.id), TAZTreeMap.randomLocationInTAZ(taz, rand)))
            val startTime = timeBin + timeStep
            val updatedDuration =
              if ((startTime + duration) >= endOfSimulationTime)
                endOfSimulationTime - startTime
              else
                duration
            val parkingInquiry = ParkingInquiry(
              SpaceTime(destinationUtm, startTime),
              activityType,
              reservedFor,
              Some(beamVehicle),
              None, // remainingTripData
              Some(personId),
              1.0, // valueOfTime
              updatedDuration,
              searchMode = ParkingSearchMode.DestinationCharging,
              triggerId = triggerId
            )
            cumulatedNumberOfEvents += 1
            parkingInquiriesTriggers += ScheduleTrigger(PlanParkingInquiryTrigger(startTime, parkingInquiry), self)
          }
          parkingInquiriesTriggers.result()
        case (tazId, _) =>
          log.warning(s"The current TAZ $tazId is unrecognizable")
          Vector()
      }
      .seq
      .toVector
      .map { triggers =>
        vehicleRequests.clear()
        triggers
      }
  }

  /**
    * @param inquiry ParkingInquiry
    * @param stall   ParkingStall
    */
  protected def collectChargingRequests(inquiry: ParkingInquiry, stall: ParkingStall): Unit = {
    val scaleUp = cnmConfig.scaleUp.enabled
    val isNotVirtual = inquiry.beamVehicle.exists(v => !isVirtualCar(v.id))
    val isReservation = inquiry.reserveStall
    val isChargingRequest = List(DestinationCharging, EnRouteCharging).contains(inquiry.searchMode)
    val isChargingStall = stall.chargingPointType.isDefined
    if (scaleUp && isNotVirtual && isReservation && isChargingRequest && isChargingStall) {
      val vehicle = inquiry.beamVehicle.get
      import ScaleUpCharging.{PERSONAL, RIDE_HAIL, SHARED}
      val vehicleAlias = if (vehicle.isRideHail) RIDE_HAIL else if (vehicle.isSharedVehicle) SHARED else PERSONAL
      val reservedFor = if (vehicle.isRideHailCAV) {
        VehicleManager
          .getReservedFor(vehicle.vehicleManagerId.get())
          .getOrElse(throw new RuntimeException("Robot taxis need to have a vehicle manager id"))
      } else if (vehicle.isSharedVehicle) {
        VehicleManager.getReservedFor(vehicle.vehicleManagerId.get()).getOrElse(VehicleManager.AnyManager)
      } else VehicleManager.AnyManager
      val estimatedParkingDuration = Math.max(
        inquiry.parkingDuration.toInt,
        beamConfig.beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds.toInt
      )
      val remainingRangeInMeters = vehicle.getRemainingRange._1
      val activityType =
        if (inquiry.activityType.startsWith(ChargingNetwork.EnRouteLabel))
          ParkingActivityType.Charge.entryName
        else inquiry.activityType
      vehicleRequests.add(
        ChargingEvent(
          stall.tazId,
          estimatedParkingDuration,
          remainingRangeInMeters,
          activityType,
          inquiry.parkingActivityType,
          vehicle.beamVehicleType,
          vehicleAlias,
          reservedFor
        )
      )
    }
  }

  /**
    * get Beam Vehicle
    *
    * @param vehicleTypesSample     ObservedVehicleTypes
    * @param remainingRangeInMeters remaining range in meters
    * @return
    */
  protected def createBeamVehicle(
    vehicleTypesSample: ObservedVehicleTypes,
    remainingRangeInMeters: Double,
    rand: Random
  ): BeamVehicle = {
    val powerTrain = new Powertrain(vehicleTypesSample.vehicleType.primaryFuelConsumptionInJoulePerMeter)
    val nextId = VehicleIdGenerator.nextId
    val beamVehicle = new BeamVehicle(
      Id.create(VIRTUAL_ALIAS + "-" + vehicleTypesSample.vehicleAlias + "-" + nextId, classOf[BeamVehicle]),
      powerTrain,
      vehicleTypesSample.vehicleType,
      new AtomicReference(vehicleTypesSample.reservedFor.managerId),
      randomSeed = rand.nextInt
    )
    val remainingFuelLevel = remainingRangeInMeters * powerTrain.estimateConsumptionInJoules(1)
    val soc = Math.min(remainingFuelLevel / vehicleTypesSample.vehicleType.primaryFuelCapacityInJoule, 1.0)
    beamVehicle.initializeFuelLevels(soc)
    beamVehicle
  }

  /**
    * @param vehicleId vehicle Id
    * @return
    */
  protected def createPerson(vehicleId: Id[BeamVehicle]): Id[Person] = {
    Id.create(vehicleId.toString, classOf[Person])
  }

  /**
    * identify whether the vehicle has been created for scaling up or not
    *
    * @param vehicleId vehicle Id
    * @return
    */
  protected def isVirtualCar(vehicleId: Id[BeamVehicle]): Boolean = isVirtualEntity(vehicleId)

  private def isVirtualEntity(entity: Id[_]): Boolean = entity.toString.startsWith(VIRTUAL_ALIAS)
}

object ScaleUpCharging {
  val VIRTUAL_ALIAS: String = "virtual"
  private val RIDE_HAIL: String = "rideHailVehicle"
  private val SHARED: String = "sharedVehicle"
  private val PERSONAL: String = "personalVehicle"

  case class PlanParkingInquiryTrigger(tick: Int, inquiry: ParkingInquiry) extends Trigger

  case class PlanChargingUnplugRequestTrigger(tick: Int, beamVehicle: BeamVehicle, personId: Id[Person]) extends Trigger

  case class ChargingEvent(
    tazId: Id[TAZ],
    parkingDurationInSec: Int,
    remainingRangeInMeters: Double,
    activityType: String,
    parkingActivityType: ParkingActivityType,
    vehicleType: BeamVehicleType,
    vehicleAlias: String,
    reservedFor: ReservedFor
  )

  trait ObservedData {

    /* mean and variance of Y */
    protected def logNormalDistribution(mean: Double, variance: Double, rand: Random): Double = {
      val phi = Math.sqrt(variance + (mean * mean))
      val mu = if (mean <= 0) 0.0 else Math.log((mean * mean) / phi)
      /* mean of log(Y)    */
      val sigma = if (phi <= 0) 0.0 else Math.sqrt(Math.log((phi * phi) / (mean * mean)))
      /* std dev of log(Y) */
      val x = MathUtils.roundUniformly(Math.max(mu + (rand.nextGaussian() * sigma), 0.0), rand).toInt
      Math.exp(x)
    }
  }

  case class ObservedVehicleTypes(
    vehicleType: BeamVehicleType,
    vehicleAlias: String,
    reservedFor: ReservedFor,
    numObservation: Int,
    meanRangeInMeters: Double,
    varianceRange: Double,
    rand: Random
  ) extends ObservedData {
    def getRemainingRangeInMeters: Double = logNormalDistribution(meanRangeInMeters, varianceRange, rand)

    override def toString: String = s"${vehicleType.id.toString}|$vehicleAlias|${reservedFor.toString}"

    override val hashCode: Int = toString.hashCode
  }

  case class ObservedActivities(
    activityType: String,
    numObservation: Int,
    totDurationInSec: Int,
    meanDuration: Double,
    varianceDuration: Double,
    parkingActivityType: ParkingActivityType,
    scaledUpNumObservation: Double,
    rand: Random
  ) extends ObservedData {
    def getDuration: Int = logNormalDistribution(meanDuration, varianceDuration, rand).toInt

    override def toString: String = activityType

    override val hashCode: Int = toString.hashCode
  }

  case class ActivityLocation(
    tazId: Id[TAZ],
    personId: Id[Person],
    householdId: Id[HouseholdId],
    activityType: String,
    location: Coord
  )

  object ActivityLocation extends LazyLogging {
    private val fileHeader = Seq[String]("TAZ", "person_id", "household_id", "ActivityType", "x", "y")

    def readActivitiesLocation(filePath: String): Vector[ActivityLocation] = {
      var res = Vector.empty[ActivityLocation]
      var mapReader: CsvMapReader = null
      try {
        mapReader = new CsvMapReader(FileUtils.readerFromFile(filePath), CsvPreference.STANDARD_PREFERENCE)
        val header = mapReader.getHeader(true)
        var line: java.util.Map[String, String] = mapReader.read(header: _*)
        while (null != line) {
          val idz = Id.create(line.getOrDefault(fileHeader(0), ""), classOf[TAZ])
          val idp = Id.create(line.getOrDefault(fileHeader(1), ""), classOf[Person])
          val idh = Id.create(line.getOrDefault(fileHeader(2), ""), classOf[HouseholdId])
          val activityType = line.getOrDefault(fileHeader(3), "")
          val x = line.getOrDefault(fileHeader(4), "0.0").toDouble
          val y = line.getOrDefault(fileHeader(5), "0.0").toDouble
          res = res :+ ActivityLocation(idz, idp, idh, activityType, new Coord(x, y))
          line = mapReader.read(header: _*)
        }
      } catch {
        case t: Throwable if filePath.nonEmpty => logger.info(s"Cannot read with reading $filePath: $t")
        case t: Throwable                      => logger.debug(s"File Path of activities location is empty in CNM.scaleup. Cause $t")
      } finally {
        if (null != mapReader)
          mapReader.close()
      }
      res
    }
  }
}
