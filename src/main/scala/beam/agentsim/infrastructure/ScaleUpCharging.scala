package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.RefuelSessionEvent.NotApplicable
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ChargingNetworkManager._
import beam.agentsim.infrastructure.ParkingInquiry.{activityTypeStringToEnum, ParkingActivityType, ParkingSearchMode}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.utils.BeamVehicleUtils.toPowerInKW
import beam.utils.MathUtils.roundUniformly
import beam.utils.{MathUtils, VehicleIdGenerator}
import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.random.MersenneTwister
import org.apache.commons.math3.util.{Pair => CPair}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person

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
  private lazy val mersenne: MersenneTwister = new MersenneTwister(beamConfig.matsim.modules.global.randomSeed)
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

  private lazy val defaultScaleUpFactor: Double =
    if (!cnmConfig.scaleUp.enabled) 1.0 else cnmConfig.scaleUp.expansionFactor_wherever_activity

  override def loggedReceive: Receive = {
    case t @ TriggerWithId(PlanParkingInquiryTrigger(_, inquiry), triggerId) =>
      log.debug(s"Received PlanParkingInquiryTrigger: $t")
      virtualParkingInquiries.put(inquiry.requestId, inquiry)
      self ! inquiry
      sender ! CompletionNotice(triggerId)
    case t @ TriggerWithId(PlanChargingUnplugRequestTrigger(tick, beamVehicle, personId), triggerId) =>
      log.debug(s"Received PlanChargingUnplugRequestTrigger: $t")
      self ! ChargingUnplugRequest(tick, personId, beamVehicle, triggerId)
      sender ! CompletionNotice(triggerId)
    case response @ ParkingInquiryResponse(stall, requestId, triggerId) =>
      log.debug(s"Received ParkingInquiryResponse: $response")
      val triggers = virtualParkingInquiries.remove(requestId) match {
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
          Vector(ScheduleTrigger(PlanChargingUnplugRequestTrigger(endTime, beamVehicle, personId), self))
        case Some(_) if stall.chargingPointType.isEmpty =>
          log.debug(s"parking inquiry with requestId $requestId returned a NoCharger stall")
          Vector()
        case _ =>
          log.error(s"inquiryMap does not have this requestId $requestId that returned stall $stall")
          Vector()
      }
      triggers.foreach(getScheduler ! _)
    case reply @ StartingRefuelSession(_, _, _, _) =>
      log.debug(s"Received StartingRefuelSession: $reply")
    case reply @ WaitingToCharge(_, _, _, _, _) =>
      log.debug(s"Received WaitingToCharge: $reply")
    case reply @ EndingRefuelSession(_, _, _) =>
      log.debug(s"Received EndingRefuelSession: $reply")
    case reply @ UnhandledVehicle(tick, personId, vehicle, triggerId) =>
      log.error(s"Received UnhandledVehicle: $reply")
      handleReleasingParkingSpot(tick, personId, vehicle, None, triggerId)
    case reply @ UnpluggingVehicle(tick, personId, vehicle, energyCharged, triggerId) =>
      log.debug(s"Received UnpluggingVehicle: $reply")
      handleReleasingParkingSpot(tick, personId, vehicle, Some(energyCharged), triggerId)
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
    energyChargedMaybe: Option[Double],
    triggerId: Long
  ): Unit = {
    ParkingNetworkManager.handleReleasingParkingSpot(
      tick,
      vehicle,
      energyChargedMaybe,
      personId,
      getParkingManager,
      getBeamServices.matsimServices.getEvents,
      triggerId
    )
  }

  /**
    * @param timeBin current time bin
    * @param triggerId trigger di for the scheduler
    * @return
    */
  protected def simulateEventsIfScalingEnabled(timeBin: Int, triggerId: Long): Vector[ScheduleTrigger] = {
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
          new EnumeratedDistribution[VehicleTypeInfo](mersenne, pmfVehicleTypeInfo.asJava)
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
            val distribution = new EnumeratedDistribution[ParkingActivityType](mersenne, pmf.asJava)
            val rate = totNumberOfEvents / timeStepByHour
            var cumulatedSimulatedPower = 0.0
            var timeStep = 0
            while (cumulatedSimulatedPower < totPowerInKWToSimulate && timeStep < timeStepByHour * 3600) {
              val parkingActivityType = distribution.sample()
              val (_, summary) = activityType2vehicleInfo(parkingActivityType)
              val duration = Math.max(
                summary.getDuration(rand),
                beamConfig.beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds.toInt
              )
              timeStep += roundUniformly(nextTimeStepUsingPoissonProcess(rate), rand).toInt
              val vehicleTypeInfo = summary.vehicleTypeInfoDistribution.sample()
              val soc = summary.meanSOC / 100.0
              val energyToCharge = summary.getEnergy(rand)
              val taz = getBeamServices.beamScenario.tazTreeMap.getTAZ(tazId).get
              val destinationUtm = TAZTreeMap.randomLocationInTAZ(taz, rand)
              val reservedFor = vehicleTypeInfo.reservedFor
              val beamVehicle = getBeamVehicle(vehicleTypeInfo, soc)
              val personId = getPerson(beamVehicle.id)
              val startTime = timeBin + timeStep
              val parkingInquiry = ParkingInquiry(
                SpaceTime(destinationUtm, startTime),
                parkingActivityType.entryName,
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
      val reservedFor = if (vehicle.isRideHail && vehicle.beamVehicleType.isFullSelfDriving) {
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
        vehicleRequests.put(
          key,
          vehicleRequests.getOrElse(key, List.empty) :+ VehicleRequestInfo(
            energyToCharge,
            Math.min(durationToCharge, estimatedChargingDuration),
            100 * Math.min(Math.max(vehicle.getStateOfCharge, 0), 1),
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
  protected def getBeamVehicle(vehicleTypeInfo: VehicleTypeInfo, soc: Double): BeamVehicle = {
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
  protected def getPerson(vehicleId: Id[BeamVehicle]): Id[Person] = {
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
    vehicleTypeInfoDistribution: EnumeratedDistribution[VehicleTypeInfo]
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
}
