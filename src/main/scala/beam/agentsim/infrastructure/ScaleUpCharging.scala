package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.RefuelSessionEvent.NotApplicable
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ChargingNetwork.ChargingVehicle
import beam.agentsim.infrastructure.ChargingNetworkManager._
import beam.agentsim.infrastructure.ParkingInquiry.{activityTypeStringToEnum, ParkingActivityType}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.BeamConfig.Beam.Agentsim
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
  private lazy val cnmConfig: Agentsim.ChargingNetworkManager = beamConfig.beam.agentsim.chargingNetworkManager
  private lazy val timeStepByHour = beamConfig.beam.agentsim.chargingNetworkManager.timeStepInSeconds / 3600.0
  private lazy val virtualParkingInquiries: TrieMap[Int, ParkingInquiry] = TrieMap()
  private lazy val vehicleRequests = mutable.HashMap.empty[(Id[TAZ], ParkingActivityType), List[VehicleRequestInfo]]

  private lazy val scaleUpFactors: Map[ParkingActivityType, Double] = {
    if (!cnmConfig.scaleUp.enabled) Map()
    else {
      Map(
        ParkingActivityType.Home     -> cnmConfig.scaleUp.expansionFactor_home_activity,
        ParkingActivityType.Init     -> cnmConfig.scaleUp.expansionFactor_init_activity,
        ParkingActivityType.Work     -> cnmConfig.scaleUp.expansionFactor_work_activity,
        ParkingActivityType.Charge   -> cnmConfig.scaleUp.expansionFactor_charge_activity,
        ParkingActivityType.Wherever -> cnmConfig.scaleUp.expansionFactor_wherever_activity
      )
    }
  }

  private lazy val defaultScaleUpFactor: Double =
    if (!cnmConfig.scaleUp.enabled) 1.0 else cnmConfig.scaleUp.expansionFactor_wherever_activity

  override def loggedReceive: Receive = {
    case t @ TriggerWithId(PlanParkingInquiryTrigger(_, requestId), triggerId) =>
      log.debug(s"Received parking response: $t")
      virtualParkingInquiries.get(requestId) match {
        case Some(inquiry) => self ! inquiry
        case _ =>
          log.error(
            s"Something is broken in ScaleUpCharging. Request $requestId is not present in virtualParkingInquiries"
          )
      }
      sender ! CompletionNotice(triggerId)
    case t @ TriggerWithId(PlanChargingUnplugRequestTrigger(tick, beamVehicle, requestId), triggerId) =>
      log.debug(s"Received parking response: $t")
      self ! ChargingUnplugRequest(tick, beamVehicle, triggerId)
      virtualParkingInquiries.remove(requestId)
    case response @ ParkingInquiryResponse(stall, requestId, triggerId) =>
      log.debug(s"Received parking response: $response")
      virtualParkingInquiries.get(requestId) match {
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
    case reply @ UnhandledVehicle(_, _, triggerId) =>
      log.debug(s"Received parking response: $reply")
      getScheduler ! CompletionNotice(triggerId)
    case reply @ UnpluggingVehicle(_, _, triggerId) =>
      log.debug(s"Received parking response: $reply")
      getScheduler ! CompletionNotice(triggerId)
  }

  /**
    * Next Time poisson
    * @param rate rate of charging event
    * @return
    */
  private def nextTimeStepUsingPoissonProcess(rate: Double): Double =
    3600.0 * (-Math.log(1.0 - rand.nextDouble()) / rate)

  /**
    * @param timeBin current time bin
    * @param triggerId trigger di for the scheduler
    * @return
    */
  protected def simulateEventsIfScalingEnabled(timeBin: Int, triggerId: Long): Vector[ScheduleTrigger] = {
    val results = vehicleRequests
      .groupBy(_._1._1)
      .par
      .mapValues(_.map { case ((_, parkingActivityType), data) =>
        val numObservation = data.size
        val totPowerInKW = data.map(x => toPowerInKW(x.energyToChargeInJoule, x.durationToChargeInSec)).sum
        val durationList = data.map(_.durationToChargeInSec)
        val socList = data.map(x => x.remainingFuelInJoule / x.fuelCapacityInJoule)
        val energyList = data.map(_.energyToChargeInJoule)
        val fuelCapacityList = data.map(_.fuelCapacityInJoule)
        val meanDur: Double = durationList.sum / numObservation.toDouble
        val meanSOC: Double = socList.sum / numObservation.toDouble
        val meanEnergy: Double = energyList.sum / energyList.size.toDouble
        val meanFuelCapacity: Double = fuelCapacityList.sum / numObservation.toDouble
        val pmfActivityType =
          data
            .groupBy(_.activityType)
            .map { case (activityType, elems) =>
              new CPair[String, java.lang.Double](activityType, elems.size.toDouble)
            }
            .toVector
        val vehicleInfoSummary = VehicleInfoSummary(
          numObservation = numObservation,
          totPowerInKW = totPowerInKW,
          meanDur = meanDur,
          meanSOC = meanSOC,
          meanEnergy = meanEnergy,
          meanFuelCapacity = meanFuelCapacity,
          stdDevDur = Math.sqrt(durationList.map(_ - meanDur).map(t => t * t).sum / numObservation),
          stdDevSOC = Math.sqrt(socList.map(_ - meanSOC).map(t => t * t).sum / numObservation),
          stdDevEnergy = Math.sqrt(energyList.map(_ - meanEnergy).map(t => t * t).sum / numObservation),
          stdFuelCapacity = Math.sqrt(fuelCapacityList.map(_ - meanFuelCapacity).map(t => t * t).sum / numObservation),
          new EnumeratedDistribution[String](mersenne, pmfActivityType.asJava)
        )
        parkingActivityType -> (data, vehicleInfoSummary)
      })
      .flatMap { case (tazId, activityType2vehicleInfo) =>
        val partialTriggersAndInquiries = Vector.newBuilder[(ScheduleTrigger, ParkingInquiry)]
        activityType2vehicleInfo.foldLeft((0.0, 0.0, Vector.empty[CPair[ParkingActivityType, java.lang.Double]])) {
          case ((powerAcc, numEventsAcc, pmfAcc), (activityType, (_, dataSummary))) =>
            val power = (scaleUpFactors.getOrElse(activityType, defaultScaleUpFactor) - 1) * dataSummary.totPowerInKW
            val pmf = new CPair[ParkingActivityType, java.lang.Double](activityType, power)
            val numEvents =
              (scaleUpFactors.getOrElse(activityType, defaultScaleUpFactor) - 1) * dataSummary.numObservation
            (powerAcc + power, numEventsAcc + numEvents, pmfAcc :+ pmf)
        } match {
          case (totPowerInKWToSimulate, totNumberOfEvents, pmf) if totPowerInKWToSimulate > 0 =>
            val distribution = new EnumeratedDistribution[ParkingActivityType](mersenne, pmf.asJava)
            val rate = totNumberOfEvents / timeStepByHour
            var cumulatedSimulatedPower = 0.0
            var timeStep = 0
            while (cumulatedSimulatedPower < totPowerInKWToSimulate && timeStep < timeStepByHour * 3600) {
              val (_, summary) = activityType2vehicleInfo(distribution.sample())
              val duration = summary.getDuration(rand)
              if (duration > 0) {
                timeStep += roundUniformly(nextTimeStepUsingPoissonProcess(rate), rand).toInt
                val soc = summary.getSOC(rand)
                val energyToCharge = summary.getEnergy(rand)
                val taz = getBeamServices.beamScenario.tazTreeMap.getTAZ(tazId).get
                val destinationUtm = TAZTreeMap.randomLocationInTAZ(taz, rand)
                val vehicleType = getBeamVehicleType(summary.getFuelCapacity(rand, energyToCharge, soc))
                val reservedFor = VehicleManager.AnyManager
                val beamVehicle = getBeamVehicle(vehicleType, reservedFor, soc)
                val personId = getPerson(beamVehicle.id)
                val startTime = timeBin + timeStep
                val parkingInquiry = ParkingInquiry(
                  SpaceTime(destinationUtm, startTime),
                  summary.activityTypeDistribution.sample(),
                  reservedFor,
                  Some(beamVehicle),
                  None, // remainingTripData
                  Some(personId),
                  1.0, // valueOfTime
                  duration,
                  triggerId = triggerId
                )
                val trigger = ScheduleTrigger(PlanParkingInquiryTrigger(startTime, parkingInquiry.requestId), self)
                cumulatedSimulatedPower += toPowerInKW(energyToCharge, duration)
                partialTriggersAndInquiries += ((trigger, parkingInquiry))
              }
            }
          case _ =>
            log.warning("The observed load is null. Most likely due to vehicles not needing to charge!")
        }
        partialTriggersAndInquiries.result()
      }
      .seq
      .toVector
    results.foreach(x => virtualParkingInquiries.put(x._2.requestId, x._2))
    val triggers = results.map(_._1)
    vehicleRequests.clear()
    triggers
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
      vehicleRequests.synchronized {
        val key = (chargingVehicle.stall.tazId, activityTypeStringToEnum(chargingVehicle.activityType))
        vehicleRequests.put(
          key,
          vehicleRequests.getOrElse(key, List.empty) :+ VehicleRequestInfo(
            energyToCharge,
            durationToCharge,
            Math.max(chargingVehicle.vehicle.primaryFuelLevelInJoules, 0.0),
            chargingVehicle.vehicle.beamVehicleType.primaryFuelCapacityInJoule,
            chargingVehicle.activityType
          )
        )
      }
    }
  }

  /**
    * get Beam Vehicle Type
    * @return BeamVehicleType
    */
  protected def getBeamVehicleType(fuelCapacityInJoule: Double): BeamVehicleType = {
    BeamVehicleType(
      id = Id.create(VIRTUAL_CAR_ALIAS + "Type", classOf[BeamVehicleType]),
      seatingCapacity = 4,
      standingRoomCapacity = 0,
      lengthInMeter = 4.1,
      primaryFuelType = FuelType.Electricity,
      primaryFuelConsumptionInJoulePerMeter = 626,
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
    * @param vehicleId vehicle Id
    * @return
    */
  protected def getPerson(vehicleId: Id[BeamVehicle]): Id[Person] = {
    Id.create(vehicleId.toString.replace(VIRTUAL_CAR_ALIAS, "VirtualPerson"), classOf[Person])
  }

  /**
    * identify whether the vehicle has been created for scaling up or not
    * @param vehicleId vehicle Id
    * @return
    */
  protected def isVirtualCar(vehicleId: Id[BeamVehicle]): Boolean = vehicleId.toString.contains(VIRTUAL_CAR_ALIAS)
}

object ScaleUpCharging {
  val VIRTUAL_CAR_ALIAS: String = "VirtualCar"
  case class PlanParkingInquiryTrigger(tick: Int, requestId: Int) extends Trigger
  case class PlanChargingUnplugRequestTrigger(tick: Int, beamVehicle: BeamVehicle, requestId: Int) extends Trigger

  case class VehicleRequestInfo(
    energyToChargeInJoule: Double,
    durationToChargeInSec: Int,
    remainingFuelInJoule: Double,
    fuelCapacityInJoule: Double,
    activityType: String
  )

  case class VehicleInfoSummary(
    numObservation: Int,
    totPowerInKW: Double,
    meanDur: Double,
    meanSOC: Double,
    meanEnergy: Double,
    meanFuelCapacity: Double,
    stdDevDur: Double,
    stdDevSOC: Double,
    stdDevEnergy: Double,
    stdFuelCapacity: Double,
    activityTypeDistribution: EnumeratedDistribution[String]
  ) {

    def getDuration(rand: Random): Int = {
      MathUtils.roundUniformly(Math.max(meanDur + (rand.nextGaussian() * stdDevDur), 0.0), rand).toInt
    }

    def getSOC(rand: Random): Double = Math.max(meanSOC + (rand.nextGaussian() * stdDevSOC), 0.0)

    def getEnergy(rand: Random): Double = Math.max(meanEnergy + (rand.nextGaussian() * stdDevEnergy), 0.0)

    def getFuelCapacity(rand: Random, energy: Double, soc: Double): Double = {
      Math.max(meanFuelCapacity + (rand.nextGaussian() * stdFuelCapacity), if (soc == 1) energy else energy / (1 - soc))
    }
  }

  /**
    * @param energy Joules
    * @param duration Seconds
    * @return
    */
  def toPowerInKW(energy: Double, duration: Int): Double = {
    if (duration > 0 && energy >= 0) (energy / 3.6e+6) / (duration / 3600.0)
    else 0
  }
}
