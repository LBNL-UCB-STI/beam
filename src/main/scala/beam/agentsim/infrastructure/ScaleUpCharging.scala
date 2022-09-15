package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.RefuelSessionEvent.NotApplicable
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ChargingNetworkManager._
import beam.agentsim.infrastructure.ParkingInquiry.{ParkingActivityType, ParkingSearchMode}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.utils.BeamVehicleUtils.toPowerInKW
import beam.utils.MathUtils.roundUniformly
import beam.utils.scenario.HouseholdId
import beam.utils.{FileUtils, MathUtils, VehicleIdGenerator}
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.random.MersenneTwister
import org.apache.commons.math3.util.{Pair => CPair}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.population.Person
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

  private lazy val rand: Random = new Random(beamConfig.matsim.modules.global.randomSeed)
  private lazy val timeStepByHour = beamConfig.beam.agentsim.chargingNetworkManager.timeStepInSeconds / 3600.0
  private lazy val virtualParkingInquiries: TrieMap[Int, ParkingInquiry] = TrieMap()
  private lazy val vehicleRequests = mutable.HashMap.empty[(Id[TAZ], ParkingActivityType), List[VehicleRequestInfo]]

  private lazy val scaleUpFactors: Map[ParkingActivityType, Double] = {
    if (!cnmConfig.scaleUp.enabled) Map()
    else {
      Map(
        ParkingActivityType.Home     -> cnmConfig.scaleUp.expansionFactor_home_activity,
        ParkingActivityType.Work     -> cnmConfig.scaleUp.expansionFactor_work_activity,
        ParkingActivityType.Charge   -> cnmConfig.scaleUp.expansionFactor_charge_activity,
        ParkingActivityType.Wherever -> cnmConfig.scaleUp.expansionFactor_wherever_activity
      )
    }
  }

  private lazy val activitiesLocationMap: Map[Id[TAZ], Vector[ActivityLocation]] = {
    ActivityLocation
      .readActivitiesLocation(cnmConfig.scaleUp.activitiesLocationFilePath)
      .filter(a => a.location.getX != 0 && a.location.getY != 0 && a.activityType.nonEmpty)
      .groupBy(_.tazId)
  }

  private lazy val defaultScaleUpFactor: Double =
    if (!cnmConfig.scaleUp.enabled) 1.0 else cnmConfig.scaleUp.expansionFactor_wherever_activity

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
          val endTime = (parkingInquiry.destinationUtm.time + parkingInquiry.parkingDuration).toInt
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
    * @param rate rate of charging event
    * @return
    */
  private def nextTimeStepUsingPoissonProcess(rate: Double): Double =
    3600.0 * (-Math.log(1.0 - rand.nextDouble()) / rate)

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
    * @param timeBin current time bin
    * @param triggerId trigger di for the scheduler
    * @return
    */
  protected def simulateEventsIfScalingEnabled(timeBin: Int, triggerId: Long): Vector[ScheduleTrigger] = {
    val allPersons = this.chargingNetworkHelper.allPersons
    vehicleRequests
      .groupBy(_._1._1)
      .par
      .mapValues(_.map { case ((_, parkingActivityType), data) =>
        val numObservation = data.size
        val totPowerInKW = data.map(x => toPowerInKW(x.energyToChargeInJoule, x.parkingDurationInSec)).sum
        val listDur = data.map(_.parkingDurationInSec)
        val totDurationInSec = listDur.sum
        val meanDur: Double = listDur.sum / numObservation.toDouble
        val varianceDur: Double = listDur.map(d => d - meanDur).map(t => t * t).sum / numObservation
        val listSOC = data.map(_.stateOfCharge)
        val meanSOC: Double = listSOC.sum / numObservation.toDouble
        val varianceSOC: Double = listSOC.map(soc => soc - meanSOC).map(t => t * t).sum / numObservation
        val listEnergy = data.map(_.energyToChargeInJoule)
        val meanEnergy: Double = listEnergy.sum / listEnergy.size.toDouble
        val varianceEnergy: Double = listEnergy.map(energy => energy - meanEnergy).map(t => t * t).sum / numObservation
        val pmfActivityType =
          data
            .groupBy(_.activityType)
            .map { case (activityType, elems) =>
              new CPair[String, java.lang.Double](activityType, elems.size.toDouble)
            }
            .toVector
        val pmfVehicleTypeInfo = data
          .groupBy(record => (record.vehicleType, record.vehicleAlias, record.reservedFor))
          .map { case ((vehicleType, vehicleAlias, reservedFor), elems) =>
            new CPair[VehicleTypeInfo, java.lang.Double](
              VehicleTypeInfo(vehicleType, vehicleAlias, reservedFor),
              elems.size.toDouble
            )
          }
          .toVector
        val vehicleInfoSummary = VehicleInfoSummary(
          numObservation = numObservation,
          totPowerInKW = totPowerInKW,
          totDurationInSec = totDurationInSec,
          meanDuration = meanDur,
          varianceDuration = varianceDur,
          meanSOC = meanSOC,
          varianceSOC = varianceSOC,
          meanEnergy = meanEnergy,
          varianceEnergy = varianceEnergy,
          pmfVehicleTypeInfo = pmfVehicleTypeInfo,
          pmfActivityTypeString = pmfActivityType
        )
        parkingActivityType -> (data, vehicleInfoSummary)
      })
      .flatMap { case (tazId, activityType2vehicleInfo) =>
        val parkingInquiriesTriggers = Vector.newBuilder[ScheduleTrigger]
        activityType2vehicleInfo.foldLeft((0.0, 0.0, Vector.empty[CPair[ParkingActivityType, java.lang.Double]])) {
          case ((powerAcc, numEventsAcc, pmfAcc), (parkingActivityType, (_, dataSummary))) =>
            val scaleUpFactor = scaleUpFactors.getOrElse(parkingActivityType, defaultScaleUpFactor) - 1
            val power = scaleUpFactor * dataSummary.totPowerInKW
            val pmf = new CPair[ParkingActivityType, java.lang.Double](parkingActivityType, power)
            val numEvents = scaleUpFactor * dataSummary.numObservation
            (powerAcc + power, numEventsAcc + numEvents, pmfAcc :+ pmf)
        } match {
          case (totPowerInKWToSimulate, totNumberOfEvents, pmf) if totPowerInKWToSimulate > 0 =>
            val mersenne: MersenneTwister = new MersenneTwister(beamConfig.matsim.modules.global.randomSeed)
            val distribution = new EnumeratedDistribution[ParkingActivityType](mersenne, pmf.asJava)
            val rate = totNumberOfEvents / timeStepByHour
            var cumulatedSimulatedPower = 0.0
            var timeStep = 0
            val activitiesLocationInCurrentTAZ: Vector[ActivityLocation] =
              activitiesLocationMap
                .get(tazId)
                .map(_.filterNot(a => allPersons.contains(a.personId)))
                .getOrElse(Vector.empty)
            while (cumulatedSimulatedPower < totPowerInKWToSimulate && timeStep < timeStepByHour * 3600) {
              val (_, summary) = activityType2vehicleInfo(distribution.sample())
              val duration = Math.max(
                summary.getDuration(rand),
                beamConfig.beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds.toInt
              )
              timeStep += roundUniformly(nextTimeStepUsingPoissonProcess(rate), rand).toInt
              val vehicleTypeInfoDistribution =
                new EnumeratedDistribution[VehicleTypeInfo](mersenne, summary.pmfVehicleTypeInfo.asJava)
              val activityTypeDistribution =
                new EnumeratedDistribution[String](mersenne, summary.pmfActivityTypeString.asJava)
              val vehicleTypeInfo = vehicleTypeInfoDistribution.sample()
              val soc = summary.meanSOC / 100.0
              val energyToCharge = summary.getEnergy(rand)
              val activityType = activityTypeDistribution.sample()
              val reservedFor = vehicleTypeInfo.reservedFor
              val beamVehicle = createBeamVehicle(vehicleTypeInfo, soc)
              val (personId, destinationUtm) =
                Option(activitiesLocationInCurrentTAZ.filter(_.activityType == activityType)) match {
                  case Some(activitiesLocation) if activitiesLocation.nonEmpty =>
                    val _ @ActivityLocation(_, personId, _, _, location) =
                      activitiesLocation(rand.nextInt(activitiesLocation.size))
                    (personId, getBeamServices.geo.wgs2Utm(location))
                  case _ =>
                    val taz = getBeamServices.beamScenario.tazTreeMap.getTAZ(tazId).get
                    (createPerson(beamVehicle.id), TAZTreeMap.randomLocationInTAZ(taz, rand))
                }
              val startTime = timeBin + timeStep
              val parkingInquiry = ParkingInquiry(
                SpaceTime(destinationUtm, startTime),
                activityType,
                reservedFor,
                Some(beamVehicle),
                None, // remainingTripData
                Some(personId),
                1.0, // valueOfTime
                duration,
                searchMode = ParkingSearchMode.DestinationCharging,
                triggerId = triggerId
              )
              cumulatedSimulatedPower += toPowerInKW(energyToCharge, duration)
              parkingInquiriesTriggers += ScheduleTrigger(PlanParkingInquiryTrigger(startTime, parkingInquiry), self)
            }
          case _ =>
            log.debug("The observed load is null. Most likely due to vehicles not needing to charge!")
        }
        parkingInquiriesTriggers.result()
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
    * @param stall ParkingStall
    */
  protected def collectVehicleRequestInfo(inquiry: ParkingInquiry, stall: ParkingStall): Unit = {
    if (cnmConfig.scaleUp.enabled && inquiry.beamVehicle.exists(v => !isVirtualCar(v.id))) {
      val vehicle = inquiry.beamVehicle.get
      val vehicleAlias =
        if (vehicle.isRideHail) ScaleUpCharging.RIDE_HAIL
        else if (vehicle.isSharedVehicle) ScaleUpCharging.SHARED
        else ScaleUpCharging.PERSONAL
      val reservedFor = if (vehicle.isRideHailCAV) {
        VehicleManager
          .getReservedFor(vehicle.vehicleManagerId.get())
          .getOrElse(throw new RuntimeException("Robot taxis need to have a vehicle manager id"))
      } else if (vehicle.isSharedVehicle) {
        VehicleManager.getReservedFor(vehicle.vehicleManagerId.get()).getOrElse(VehicleManager.AnyManager)
      } else VehicleManager.AnyManager
      val estimatedChargingDuration = Math.max(
        inquiry.parkingDuration.toInt,
        beamConfig.beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds.toInt
      )
      val (durationToCharge, energyToCharge) =
        vehicle.refuelingSessionDurationAndEnergyInJoulesForStall(
          Some(stall),
          sessionDurationLimit = Some(estimatedChargingDuration),
          stateOfChargeLimit = None,
          chargingPowerLimit = None
        )
      vehicleRequests.synchronized {
        val key = (stall.tazId, inquiry.parkingActivityType)
        val activityType =
          if (inquiry.activityType.startsWith(ChargingNetwork.EnRouteLabel))
            ParkingActivityType.Charge.entryName
          else inquiry.activityType
        vehicleRequests.put(
          key,
          vehicleRequests.getOrElse(key, List.empty) :+ VehicleRequestInfo(
            energyToCharge,
            Math.min(durationToCharge, estimatedChargingDuration),
            100 * Math.min(Math.max(vehicle.getStateOfCharge, 0), 1),
            activityType,
            vehicle.beamVehicleType,
            vehicleAlias,
            reservedFor
          )
        )
      }
    }
  }

  /**
    * get Beam Vehicle
    * @param vehicleTypeInfo VehicleTypeInfo
    * @param soc State Of Charge In Double
    * @return
    */
  protected def createBeamVehicle(vehicleTypeInfo: VehicleTypeInfo, soc: Double): BeamVehicle = {
    val powerTrain = new Powertrain(vehicleTypeInfo.vehicleType.primaryFuelConsumptionInJoulePerMeter)
    val nextId = VehicleIdGenerator.nextId
    val beamVehicle = new BeamVehicle(
      Id.create(VIRTUAL_ALIAS + "-" + vehicleTypeInfo.vehicleAlias + "-" + nextId, classOf[BeamVehicle]),
      powerTrain,
      vehicleTypeInfo.vehicleType,
      new AtomicReference(vehicleTypeInfo.reservedFor.managerId),
      randomSeed = rand.nextInt
    )
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

  case class VehicleRequestInfo(
    energyToChargeInJoule: Double,
    parkingDurationInSec: Int,
    stateOfCharge: Double,
    activityType: String,
    vehicleType: BeamVehicleType,
    vehicleAlias: String,
    reservedFor: ReservedFor
  )

  case class VehicleTypeInfo(
    vehicleType: BeamVehicleType,
    vehicleAlias: String,
    reservedFor: ReservedFor
  ) {
    override def toString: String = s"${vehicleType.id.toString}|$vehicleAlias|${reservedFor.toString}"
    override val hashCode: Int = toString.hashCode
  }

  case class VehicleInfoSummary(
    numObservation: Int,
    totPowerInKW: Double,
    totDurationInSec: Int,
    meanDuration: Double,
    varianceDuration: Double,
    meanSOC: Double,
    varianceSOC: Double,
    meanEnergy: Double,
    varianceEnergy: Double,
    pmfVehicleTypeInfo: Vector[CPair[VehicleTypeInfo, java.lang.Double]],
    pmfActivityTypeString: Vector[CPair[String, java.lang.Double]]
  ) {
    def getDuration(rand: Random): Int = logNormalDistribution(meanDuration, varianceDuration, rand).toInt
    def getEnergy(rand: Random): Double = logNormalDistribution(meanEnergy, varianceEnergy, rand)

    private def logNormalDistribution(mean: Double, variance: Double, rand: Random) /* mean and variance of Y */ = {
      val phi = Math.sqrt(variance + (mean * mean))
      val mu = if (mean <= 0) 0.0 else Math.log((mean * mean) / phi) /* mean of log(Y)    */
      val sigma = if (phi <= 0) 0.0 else Math.sqrt(Math.log((phi * phi) / (mean * mean))) /* std dev of log(Y) */
      val x = MathUtils.roundUniformly(Math.max(mu + (rand.nextGaussian() * sigma), 0.0), rand).toInt
      Math.exp(x)
    }
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
        case e: Exception => logger.info(s"issue with reading $filePath: $e")
      } finally {
        if (null != mapReader)
          mapReader.close()
      }
      res
    }
  }
}
