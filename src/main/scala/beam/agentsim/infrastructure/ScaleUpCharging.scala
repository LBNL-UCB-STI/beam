package beam.agentsim.infrastructure

import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.RefuelSessionEvent.NotApplicable
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ChargingNetwork.ChargingVehicle
import beam.agentsim.infrastructure.ChargingNetworkManager._
import beam.agentsim.infrastructure.ParkingInquiry.ParkingActivityType
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.BeamConfig.Beam.Agentsim
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
  private lazy val cnmConfig: Agentsim.ChargingNetworkManager = beamConfig.beam.agentsim.chargingNetworkManager
  private lazy val timeStepByHour = beamConfig.beam.agentsim.chargingNetworkManager.timeStepInSeconds / 3600.0
  private lazy val inquiryMap: TrieMap[Int, ParkingInquiry] = TrieMap()
  private lazy val vehicleRequests = mutable.HashMap.empty[(Id[TAZ], ParkingActivityType), List[VehicleRequestInfo]]

  /**
    * @param activityType ParkingActivityType
    * @return
    */
  private def scaleUpFactor(activityType: ParkingActivityType): Double = {
    activityType match {
      case _ if !cnmConfig.scaleUp.enabled => 1.0
      case ParkingActivityType.Home        => cnmConfig.scaleUp.expansionFactor_home_activity
      case ParkingActivityType.Init        => cnmConfig.scaleUp.expansionFactor_init_activity
      case ParkingActivityType.Work        => cnmConfig.scaleUp.expansionFactor_work_activity
      case ParkingActivityType.Charge      => cnmConfig.scaleUp.expansionFactor_charge_activity
      case ParkingActivityType.Wherever    => cnmConfig.scaleUp.expansionFactor_wherever_activity
      case _                               => cnmConfig.scaleUp.expansionFactor_wherever_activity
    }
  }

  override def loggedReceive: Receive = {
    case t @ TriggerWithId(PlanParkingInquiryTrigger(_, requestId), triggerId) =>
      log.debug(s"Received parking response: $t")
      sender ! CompletionNotice(triggerId)
      self ! inquiryMap(requestId)
    case t @ TriggerWithId(PlanChargingUnplugRequestTrigger(tick, beamVehicle, requestId), triggerId) =>
      log.debug(s"Received parking response: $t")
      sender ! CompletionNotice(triggerId)
      self ! ChargingUnplugRequest(tick, beamVehicle, triggerId)
      inquiryMap.remove(requestId)
    case response @ ParkingInquiryResponse(stall, requestId, triggerId) =>
      log.debug(s"Received parking response: $response")
      inquiryMap.get(requestId) match {
        case Some(parkingInquiry) if stall.chargingPointType.isDefined =>
          val beamVehicle = parkingInquiry.beamVehicle.get
          self ! ChargingPlugRequest(
            parkingInquiry.destinationUtm.time,
            beamVehicle,
            stall,
            parkingInquiry.personId.map(Id.create(_, classOf[Person])).getOrElse(Id.create("", classOf[Person])),
            triggerId,
            NotApplicable,
            None
          )
          val endTime = (parkingInquiry.destinationUtm.time + parkingInquiry.parkingDuration).toInt
          getScheduler ! ScheduleTrigger(
            PlanChargingUnplugRequestTrigger(endTime, beamVehicle, parkingInquiry.requestId),
            self
          )
        case Some(_) if stall.chargingPointType.isEmpty =>
          log.debug(s"parking inquiry with requestId $requestId returned a NoCharger stall")
        case _ =>
          log.warning(s"inquiryMap does not have this requestId $requestId that returned stall $stall")
      }
    case reply @ StartingRefuelSession(_, _) =>
      log.debug(s"Received parking response: $reply")
    case reply @ EndingRefuelSession(_, _, _) =>
      log.debug(s"Received parking response: $reply")
    case reply @ WaitingToCharge(_, _, _) =>
      log.debug(s"Received parking response: $reply")
    case reply @ UnhandledVehicle(_, _, _) =>
      log.debug(s"Received parking response: $reply")
    case reply @ UnpluggingVehicle(_, _, _) =>
      log.debug(s"Received parking response: $reply")
  }

  /**
    * Next Time poisson
    * @param rate rate of charging event
    * @return
    */
  private def nextTimePoisson(rate: Double): Double = 3600.0 * (-Math.log(1.0 - rand.nextDouble()) / rate)

  /**
    * @param timeBin current time bin
    * @param triggerId trigger di for the scheduler
    * @return
    */
  protected def simulateEventsIfScalingEnabled(timeBin: Int, triggerId: Long): Vector[ScheduleTrigger] = {
    if (beamConfig.beam.agentsim.chargingNetworkManager.scaleUp.enabled) {
      vehicleRequests
        .groupBy(_._1._1)
        .par
        .mapValues(_.map { case ((_, activityType), dataList) =>
          val durationList = dataList.map(_.durationToChargeInSec)
          val socList = dataList.map(x => x.remainingFuelInJoule / x.fuelCapacityInJoule)
          val energyList = dataList.map(_.energyToChargeInJoule)
          val fuelCapacityList = dataList.map(_.fuelCapacityInJoule)
          val meanDur = durationList.sum / dataList.size
          val meanSOC = socList.sum / dataList.size
          val meanEnergy = energyList.sum / energyList.size
          val meanFuelCapacity = fuelCapacityList.sum / fuelCapacityList.size
          val vehicleInfoSummary = VehicleInfoSummary(
            meanDur = meanDur,
            meanSOC = meanSOC,
            meanEnergy = meanEnergy,
            meanFuelCapacity = meanFuelCapacity,
            stdDevDur = Math.sqrt(durationList.map(_ - meanDur).map(t => t * t).sum / dataList.size),
            stdDevSOC = Math.sqrt(socList.map(_ - meanSOC).map(t => t * t).sum / dataList.size),
            stdDevEnergy = Math.sqrt(energyList.map(_ - meanEnergy).map(t => t * t).sum / dataList.size),
            stdFuelCapacity = Math.sqrt(fuelCapacityList.map(_ - meanFuelCapacity).map(t => t * t).sum / dataList.size)
          )
          activityType -> (dataList, vehicleInfoSummary)
        })
        .flatMap { case (tazId, activityType2chargingDataList) =>
          val powerAndEventScaledUp = activityType2chargingDataList.map { case (activityType, chargingDataList) =>
            val powerInKW = chargingDataList._1.map {
              case VehicleRequestInfo(energyToChargeInJoule, durationToChargeInSec, _, _) =>
                toPowerInKW(energyToChargeInJoule, durationToChargeInSec)
            }.sum
            (
              new CPair[ParkingActivityType, java.lang.Double](activityType, powerInKW * scaleUpFactor(activityType)),
              chargingDataList._1.size * scaleUpFactor(activityType)
            )
          }.toVector
          val partialTriggers = Vector.newBuilder[ScheduleTrigger]
          val pmf = powerAndEventScaledUp.map(_._1).asJava
          val totPowerInKWToSimulate = pmf.asScala.map(_.getValue.doubleValue()).sum
          val totNumberOfEvents = powerAndEventScaledUp.map(_._2).sum
          if (totPowerInKWToSimulate > 0.0) {
            val distribution = new EnumeratedDistribution[ParkingActivityType](mersenne, pmf)
            val rate = totNumberOfEvents / timeStepByHour
            var currentTotPowerInKWToSimulate: Double = 0
            var currentNumberOfEvents: Double = 0
            var timeStep = 0
            while (
              currentTotPowerInKWToSimulate <= totPowerInKWToSimulate && timeStep <= timeStepByHour * 3600 && currentNumberOfEvents <= totNumberOfEvents
            ) {
              timeStep = timeStep + MathUtils.roundUniformly(nextTimePoisson(rate), rand).toInt
              val startTime = timeBin + timeStep
              val activityType = distribution.sample()
              val (
                _,
                VehicleInfoSummary(
                  meanDur,
                  meanSOC,
                  meanEnergy,
                  meanFuelCapacity,
                  stdDevDur,
                  stdDevSOC,
                  stdDevEnergy,
                  stdFuelCapacity
                )
              ) = activityType2chargingDataList(activityType)
              val randNumber = rand.nextGaussian()
              val duration = Math.max(MathUtils.roundUniformly(meanDur + (randNumber * stdDevDur), rand).toInt, 0)
              val soc = Math.max(meanSOC + (randNumber * stdDevSOC), 0.0)
              val energyToCharge =
                Math.max(MathUtils.roundUniformly(meanEnergy + (randNumber * stdDevEnergy), rand).toInt, 0)
              val fuelCapacity = MathUtils
                .roundUniformly(
                  Math.max(
                    meanFuelCapacity + (randNumber * stdFuelCapacity),
                    if (soc == 1) energyToCharge
                    else energyToCharge / (1 - soc)
                  ),
                  rand
                )
                .toInt
              val taz = getBeamServices.beamScenario.tazTreeMap.getTAZ(tazId).get
              val destinationUtm = TAZTreeMap.randomLocationInTAZ(taz, rand)
              val vehicleType = getBeamVehicleType(fuelCapacity)
              val reservedFor = VehicleManager.AnyManager
              val beamVehicle = getBeamVehicle(vehicleType, reservedFor, soc)
              val vehicleId = beamVehicle.id.toString
              val personId = Id.create(vehicleId.replace(VIRTUAL_CAR_ALIAS, "VirtualPerson"), classOf[PersonAgent])
              val parkingInquiry = ParkingInquiry(
                SpaceTime(destinationUtm, startTime),
                activityType,
                reservedFor,
                Some(beamVehicle),
                None, // remainingTripData
                Some(personId),
                0.0, // valueOfTime
                duration,
                triggerId = triggerId
              )
              inquiryMap.put(parkingInquiry.requestId, parkingInquiry)
              partialTriggers += ScheduleTrigger(PlanParkingInquiryTrigger(startTime, parkingInquiry.requestId), self)
              currentTotPowerInKWToSimulate = currentTotPowerInKWToSimulate + toPowerInKW(energyToCharge, duration)
              currentNumberOfEvents = currentNumberOfEvents + 1
            }
          }
          partialTriggers.result()
        }
        .seq
        .toVector
    } else Vector.empty
  }

  /**
    * @param chargingVehicle the vehicle that just plugged in
    */
  protected def collectVehicleRequestInfo(chargingVehicle: ChargingVehicle): Unit = {
    if (cnmConfig.scaleUp.enabled && !isVirtualCar(chargingVehicle.vehicle.id)) {
      val (durationToCharge, energyToCharge) =
        chargingVehicle.vehicle.refuelingSessionDurationAndEnergyInJoulesForStall(
          Some(chargingVehicle.stall),
          sessionDurationLimit = None,
          stateOfChargeLimit = None,
          chargingPowerLimit = None
        )
      val requestInfo = VehicleRequestInfo(
        energyToCharge,
        durationToCharge,
        Math.max(chargingVehicle.vehicle.primaryFuelLevelInJoules, 0.0),
        chargingVehicle.vehicle.beamVehicleType.primaryFuelCapacityInJoule
      )
      val key = (chargingVehicle.stall.tazId, chargingVehicle.activityType)
      vehicleRequests.synchronized {
        vehicleRequests.put(key, vehicleRequests.getOrElse(key, List.empty) :+ requestInfo)
      }
    }
  }

  /**
    * @param energy Joules
    * @param duration Seconds
    * @return
    */
  private def toPowerInKW(energy: Double, duration: Int): Double = {
    if (duration > 0 && energy >= 0) (energy / 3.6e+6) / (duration / 3600.0)
    else 0
  }

  /**
    * get Beam Vehicle Type
    * @return BeamVehicleType
    */
  protected def getBeamVehicleType(fuelCapacityInJoule: Int): BeamVehicleType = {
    BeamVehicleType(
      id = Id.create(VIRTUAL_CAR_ALIAS + "Type", classOf[BeamVehicleType]),
      seatingCapacity = 4,
      standingRoomCapacity = 0,
      lengthInMeter = 4.1,
      primaryFuelType = FuelType.Electricity,
      primaryFuelConsumptionInJoulePerMeter = 1,
      primaryFuelCapacityInJoule = fuelCapacityInJoule,
      vehicleCategory = VehicleCategory.Car
    )
  }

  /**
    * get Beam Vehicle
    * @param vehicleType BeamVehicleType
    * @param reservedFor ReservedFor
    * @param soc State Of Charge In Double
    * @return
    */
  protected def getBeamVehicle(vehicleType: BeamVehicleType, reservedFor: ReservedFor, soc: Double): BeamVehicle = {
    val powerTrain = new Powertrain(vehicleType.primaryFuelConsumptionInJoulePerMeter)
    val nextId = VehicleIdGenerator.nextId
    val beamVehicle = new BeamVehicle(
      Id.create(VIRTUAL_CAR_ALIAS + "-" + nextId, classOf[BeamVehicle]),
      powerTrain,
      vehicleType,
      new AtomicReference(reservedFor.managerId),
      randomSeed = rand.nextInt
    )
    beamVehicle.initializeFuelLevels(soc)
    beamVehicle
  }

  /**
    * identify whether the vehicle has been created for scaling up or not
    * @param vehicleId vehicle Id
    * @return
    */
  protected def isVirtualCar(vehicleId: Id[BeamVehicle]): Boolean = vehicleId.toString.contains(VIRTUAL_CAR_ALIAS)
}

object ScaleUpCharging {
  private val VIRTUAL_CAR_ALIAS: String = "VirtualCar"
  case class PlanParkingInquiryTrigger(tick: Int, requestId: Int) extends Trigger
  case class PlanChargingUnplugRequestTrigger(tick: Int, beamVehicle: BeamVehicle, requestId: Int) extends Trigger

  case class VehicleRequestInfo(
    energyToChargeInJoule: Double,
    durationToChargeInSec: Int,
    remainingFuelInJoule: Double,
    fuelCapacityInJoule: Double
  )

  case class VehicleInfoSummary(
    meanDur: Double,
    meanSOC: Double,
    meanEnergy: Double,
    meanFuelCapacity: Double,
    stdDevDur: Double,
    stdDevSOC: Double,
    stdDevEnergy: Double,
    stdFuelCapacity: Double
  )
}
