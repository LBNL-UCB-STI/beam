package beam.agentsim.infrastructure

import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.RefuelSessionEvent.NotApplicable
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ChargingNetworkManager._
import beam.agentsim.infrastructure.ParkingInquiry.ParkingActivityType
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.BeamConfig.Beam.Agentsim
import beam.utils.{MathUtils, ParkingManagerIdGenerator, VehicleIdGenerator}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person

import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.language.postfixOps
import scala.util.Random

trait ScaleUpCharging extends {
  this: ChargingNetworkManager =>
  import ScaleUpCharging._

  private lazy val rand: Random = new Random(beamConfig.matsim.modules.global.randomSeed)
  private lazy val cnmConfig: Agentsim.ChargingNetworkManager = beamConfig.beam.agentsim.chargingNetworkManager
  private lazy val timeStepByHour = beamConfig.beam.agentsim.chargingNetworkManager.timeStepInSeconds / 3600.0

  private lazy val scaleUpFactorMaybe: Option[Double] =
    if (cnmConfig.scaleUpExpansionFactor <= 1.0) None
    else Some(cnmConfig.scaleUpExpansionFactor)

  protected lazy val inquiryMap: TrieMap[Int, ParkingInquiry] = TrieMap()
  protected lazy val simulatedEvents: TrieMap[(Id[TAZ], ChargingPointType), ChargingData] = TrieMap()

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
      log.info(s"Received parking response: $response")
      inquiryMap.get(requestId) match {
        case Some(parkingInquiry) if stall.chargingPointType.isDefined =>
          val beamVehicle = parkingInquiry.beamVehicle.get
          self ! ChargingPlugRequest(
            parkingInquiry.destinationUtm.time,
            beamVehicle,
            stall,
            Id.create(parkingInquiry.personId.toString, classOf[Person]),
            triggerId,
            NotApplicable,
            None
          )
          val endTime = (parkingInquiry.destinationUtm.time + parkingInquiry.parkingDuration).toInt
          getScheduler ! ScheduleTrigger(
            PlanChargingUnplugRequestTrigger(endTime, beamVehicle, parkingInquiry.requestId),
            self
          )
        case _ =>
          log.warning(s"inquiryMap does not have this requestId $requestId")
      }
    case reply @ StartingRefuelSession(_, _) =>
      log.info(s"Received parking response: $reply")
    case reply @ EndingRefuelSession(_, _, _) =>
      log.info(s"Received parking response: $reply")
    case reply @ WaitingToCharge(_, _, _) =>
      log.debug(s"Received parking response: $reply")
    case reply @ UnhandledVehicle(_, _, _) =>
      log.debug(s"Received parking response: $reply")
    case reply @ UnpluggingVehicle(_, _, _) =>
      log.info(s"Received parking response: $reply")
  }

  /**
    * Next Time poisson
    * @param rate rate of charging event
    * @return
    */
  private def nextTimePoisson(rate: Double): Double = 3600.0 * (-Math.log(1.0 - rand.nextDouble()) / rate)

  /**
    * @param chargingDataSummaryMap
    * @param timeBin
    * @param triggerId
    * @return
    */
  protected def simulateEvents(
    chargingDataSummaryMap: Map[(Id[TAZ], ChargingPointType), ChargingDataSummary],
    timeBin: Int,
    triggerId: Long
  ): Vector[ScheduleTrigger] = {
    import MathUtils._
    val s = System.currentTimeMillis
    log.info(s"Simulate event - timeBin: $timeBin - chargingDataSummaryMap size: ${chargingDataSummaryMap.size}")
    val allTriggers = chargingDataSummaryMap.par.flatMap { case ((tazId, chargingType), data) =>
      val partialTriggers = (1 to roundUniformly(data.rate * timeStepByHour, rand).toInt)
        .foldLeft((timeBin, Vector.empty[ScheduleTrigger])) { case ((prevStartTime, triggers), i) =>
          try {
            val startTime = prevStartTime + roundUniformly(nextTimePoisson(data.rate), rand).toInt
            log.info(
              s"tazId $tazId - chargingType: $chargingType - index: $i - timBin: $startTime - triggers: ${triggers.size}"
            )
            val duration = roundUniformly(data.meanDuration + (rand.nextGaussian() * data.sdDuration), rand).toInt
            val soc = data.meanSOC + (rand.nextGaussian() * data.sdSOC)
            val activityType = getActivityType(chargingType)
            val taz = getBeamServices.beamScenario.tazTreeMap.getTAZ(tazId).get
            val destinationUtm = TAZTreeMap.randomLocationInTAZ(taz, rand)
            val vehicleType = getBeamVehicleType()
            val reservedFor = data.reservedFor.managerType match {
              case VehicleManager.TypeEnum.Household => VehicleManager.AnyManager
              case _                                 => data.reservedFor
            }
            val beamVehicle = getBeamVehicle(vehicleType, reservedFor, soc)
            val vehicleId = beamVehicle.id.toString
            val personId = Id.create(vehicleId.replace("VirtualCar", "VirtualPerson"), classOf[PersonAgent])
            val requestId = ParkingManagerIdGenerator.nextId
            log.info(s"tazId $tazId - chargingType: $chargingType - index: $i - requestId: $requestId")
            inquiryMap.put(
              requestId,
              ParkingInquiry(
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
            )
            log.info(
              s"tazId $tazId - chargingType: $chargingType - index: $i - spaceTime: ${inquiryMap(requestId).destinationUtm}"
            )
            (startTime, triggers :+ ScheduleTrigger(PlanParkingInquiryTrigger(startTime, requestId), self))
          } catch {
            case t: Throwable =>
              log.info(s"WHAT HAPPENED ?: $t")
              (0, Vector.empty)
          }
        }
        ._2
      log.info(s"tazId $tazId - chargingType: $chargingType - DONE}")
      partialTriggers
    }.toVector
    val e = System.currentTimeMillis()
    log.info(s"Simulate event end: $timeBin. triggers size: ${allTriggers.size}. runtime: ${(e - s) / 1000.0}")
    allTriggers
  }

  /**
    * get activity type
    * @param chargingType ChargingPointType
    * @return
    */
  protected def getActivityType(chargingType: ChargingPointType): ParkingActivityType = {
    if (chargingType.toString.toLowerCase.contains("home"))
      ParkingActivityType.Home
    else if (chargingType.toString.toLowerCase.contains("work"))
      ParkingActivityType.Work
    else
      ParkingActivityType.Wherever
  }

  /**
    * get Beam Vehicle Type
    * @return
    */
  protected def getBeamVehicleType(): BeamVehicleType = {
    BeamVehicleType(
      id = Id.create("VirtualCar", classOf[BeamVehicleType]),
      seatingCapacity = 4,
      standingRoomCapacity = 0,
      lengthInMeter = 4.1,
      primaryFuelType = FuelType.Electricity,
      primaryFuelConsumptionInJoulePerMeter = 626,
      primaryFuelCapacityInJoule = 302234052,
      vehicleCategory = VehicleCategory.Car
    )
  }

  /**
    * get Beam Vehicle
    * @param vehicleType BeamVehicleType
    * @param reservedFor ReservedFor
    * @param fuel Double
    * @return
    */
  protected def getBeamVehicle(vehicleType: BeamVehicleType, reservedFor: ReservedFor, soc: Double): BeamVehicle = {
    val powerTrain = new Powertrain(vehicleType.primaryFuelConsumptionInJoulePerMeter)
    val nextId = VehicleIdGenerator.nextId
    val beamVehicle = new BeamVehicle(
      Id.create("VirtualCar-" + nextId, classOf[BeamVehicle]),
      powerTrain,
      vehicleType,
      new AtomicReference(reservedFor.managerId),
      randomSeed = rand.nextInt
    )
    beamVehicle.initializeFuelLevels(soc)
    beamVehicle
  }

  /**
    * summarizeAndSkimOrGetChargingData
    * @return map
    */
  protected def summarizeAndSkimOrGetChargingData(): Map[(Id[TAZ], ChargingPointType), ChargingDataSummary] = {
    scaleUpFactorMaybe match {
      case Some(scaleUpFactor) if simulatedEvents.nonEmpty =>
        val s = System.currentTimeMillis
        log.info(s"summarizeAndSkimOrGetChargingData")
        val chargingDataSummary = simulatedEvents.par
          .map { case (key, data) =>
            val rate = data.durations.size * scaleUpFactor / timeStepByHour
            // Adding 60 seconds to avoid null duration
            val meanDur = 60 + (data.durations.sum / data.durations.size)
            val stdDevDur = Math.sqrt(data.durations.map(_ - meanDur).map(t => t * t).sum / data.durations.size)
            val meanFuel = data.soc.sum / data.soc.size
            val stdDevFuel = Math.sqrt(data.soc.map(_ - meanFuel).map(t => t * t).sum / data.soc.size)
            key -> ChargingDataSummary(rate, meanDur, stdDevDur, meanFuel, stdDevFuel, data.reservedFor)
          }
          .seq
          .toMap
        val e = System.currentTimeMillis()
        log.info(
          s"summarizeAndSkimOrGetChargingData. simulatedEvents size: ${simulatedEvents.size}. runtime: ${(e - s) / 1000.0}"
        )
        simulatedEvents.clear()
        chargingDataSummary
      case _ => Map.empty[(Id[TAZ], ChargingPointType), ChargingDataSummary]
    }
  }

  /**
    * Collect Charging Data
    * @param stall Parking Stall
    * @param vehicle Beam Vehicle
    */
  protected def collectChargingData(stall: ParkingStall, vehicle: BeamVehicle): Unit = {
    scaleUpFactorMaybe match {
      case Some(_) if !vehicle.id.toString.contains("VirtualCar") =>
        val s = System.currentTimeMillis
        log.info(s"collectChargingData. vehicle $vehicle")
        val key = (stall.tazId, stall.chargingPointType.get)
        if (!simulatedEvents.contains(key)) {
          simulatedEvents.put(key, ChargingData(ListBuffer.empty[Int], ListBuffer.empty[Double], stall.reservedFor))
        }
        val (duration, _) = vehicle.refuelingSessionDurationAndEnergyInJoulesForStall(Some(stall), None, None, None)
        simulatedEvents(key).durations.append(duration)
        val soc = vehicle.primaryFuelLevelInJoules / vehicle.beamVehicleType.primaryFuelCapacityInJoule
        simulatedEvents(key).soc.append(soc)
        val e = System.currentTimeMillis()
        log.info(
          s"collectChargingData. simulatedEvents size: ${simulatedEvents.size}. runtime: ${(e - s) / 1000.0}"
        )
      case _ =>
    }
  }
}

object ScaleUpCharging {
  case class PlanParkingInquiryTrigger(tick: Int, requestId: Int) extends Trigger
  case class PlanChargingUnplugRequestTrigger(tick: Int, beamVehicle: BeamVehicle, requestId: Int) extends Trigger

  case class ChargingData(durations: ListBuffer[Int], soc: ListBuffer[Double], reservedFor: ReservedFor)

  case class ChargingDataSummary(
    rate: Double,
    meanDuration: Int,
    sdDuration: Double,
    meanSOC: Double,
    sdSOC: Double,
    reservedFor: ReservedFor
  )
}
