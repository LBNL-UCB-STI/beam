package beam.agentsim.infrastructure

import akka.pattern.pipe
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.RefuelSessionEvent.NotApplicable
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ChargingNetworkManager.{
  ChargingPlugRequest,
  ChargingUnplugRequest,
  EndingRefuelSession,
  StartingRefuelSession,
  UnhandledVehicle,
  UnpluggingVehicle,
  WaitingToCharge
}
import beam.agentsim.infrastructure.ParkingInquiry.ParkingActivityType
import beam.agentsim.infrastructure.parking.{ParkingType, ParkingZoneId}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.BeamConfig.Beam.Agentsim
import beam.utils.logging.pattern.ask
import beam.utils.{MathUtils, VehicleIdGenerator}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Random
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps

trait ScaleUpCharging extends {
  this: ChargingNetworkManager =>
  import ScaleUpCharging._

  private lazy val rand: Random = new Random(beamConfig.matsim.modules.global.randomSeed)
  private lazy val cnmConfig: Agentsim.ChargingNetworkManager = beamConfig.beam.agentsim.chargingNetworkManager
  private lazy val timeStepByHour = beamConfig.beam.agentsim.chargingNetworkManager.timeStepInSeconds / 3600.0

  private lazy val scaleUpFactorMaybe: Option[Double] =
    if (cnmConfig.scaleUpExpansionFactor <= 1.0) None
    else Some(cnmConfig.scaleUpExpansionFactor)

  protected lazy val inquiryMap: mutable.Map[Int, ChargingDataInquiry] = mutable.Map()
  protected lazy val simulatedEvents: mutable.Map[Id[ParkingZoneId], ChargingData] = mutable.Map()

  override def loggedReceive: Receive = {
    case TriggerWithId(PlanParkingInquiryTrigger(_, inquiry), triggerId) =>
      self ! inquiry
      getScheduler ! CompletionNotice(triggerId, Vector())
    case TriggerWithId(PlanChargingUnplugRequestTrigger(tick, requestId), triggerId) =>
      self ! ChargingUnplugRequest(tick, inquiryMap(requestId).parkingInquiry.beamVehicle.get, triggerId)
      getScheduler ! CompletionNotice(triggerId, Vector())
      inquiryMap.remove(requestId)
    case response @ ParkingInquiryResponse(stall, requestId, triggerId) =>
      log.debug(s"Received parking response: $response")
      if (stall.chargingPointType.isDefined) {
        val inquiryEntity = inquiryMap(requestId)
        self ! ChargingPlugRequest(
          inquiryEntity.startTime,
          inquiryEntity.parkingInquiry.beamVehicle.get,
          stall,
          inquiryEntity.personId,
          triggerId,
          NotApplicable,
          None
        )
        val endTime = (inquiryEntity.startTime + inquiryEntity.parkingInquiry.parkingDuration).toInt
        (getScheduler ? ScheduleTrigger(PlanChargingUnplugRequestTrigger(endTime, requestId), self)).pipeTo(sender())
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
    * @param chargingDataSummaryMap
    * @param timeBin
    * @param triggerId
    * @return
    */
  protected def simulateEvents(
    chargingDataSummaryMap: Map[Id[ParkingZoneId], ChargingDataSummary],
    timeBin: Int,
    triggerId: Long
  ): Vector[ScheduleTrigger] = {
    var triggers = Vector.empty[ScheduleTrigger]
    chargingDataSummaryMap.par.map { case (parkingZoneId, data) =>
      val scaledUpNumEvents = MathUtils.roundUniformly(data.rate * timeStepByHour, rand).toInt
      (1 to scaledUpNumEvents).foldLeft(timeBin) { case (acc, _) =>
        val startTime = MathUtils.roundUniformly(acc + nextTimePoisson(data.rate), rand).toInt
        val duration = MathUtils.roundUniformly(data.avgDuration + (rand.nextGaussian() * data.sdDuration), rand).toInt
        val fuel = MathUtils.roundUniformly(data.avgFuel + (rand.nextGaussian() * data.sdFuel), rand).toInt
        val parkingZone = chargingNetworkHelper.get(data.managerId).chargingZones(parkingZoneId)
        val activityType = parkingZone.parkingType match {
          case ParkingType.Residential => ParkingActivityType.Home
          case ParkingType.Public      => ParkingActivityType.Wherever
          case ParkingType.Workplace   => ParkingActivityType.Work
        }
        val tazId = parkingZone.geoId.asInstanceOf[Id[TAZ]]
        val taz = getBeamServices.beamScenario.tazTreeMap.getTAZ(tazId).get
        val destinationUtm = TAZTreeMap.randomLocationInTAZ(taz, rand)
        val vehicleType = BeamVehicleType(
          id = Id.create("VirtualCar", classOf[BeamVehicleType]),
          seatingCapacity = 4,
          standingRoomCapacity = 0,
          lengthInMeter = 4.1,
          primaryFuelType = FuelType.Electricity,
          primaryFuelConsumptionInJoulePerMeter = 626,
          primaryFuelCapacityInJoule = 302234052,
          vehicleCategory = VehicleCategory.Car
        )
        val powerTrain = new Powertrain(vehicleType.primaryFuelConsumptionInJoulePerMeter)
        val nextId = VehicleIdGenerator.nextId
        val beamVehicle =
          new BeamVehicle(
            Id.create("VirtualCar-" + nextId, classOf[BeamVehicle]),
            powerTrain,
            vehicleType,
            new AtomicReference(data.managerId),
            randomSeed = rand.nextInt
          )
        beamVehicle.initializeFuelLevels(fuel)
        val inquiry = ParkingInquiry(
          SpaceTime(destinationUtm, startTime),
          activityType,
          VehicleManager.getReservedFor(data.managerId).get,
          Some(beamVehicle),
          None, // remainingTripData
          0.0, // valueOfTime
          duration,
          triggerId = triggerId
        )
        val personId = Id.create("VirtualPerson-" + nextId, classOf[Person])
        inquiryMap.put(inquiry.requestId, ChargingDataInquiry(startTime, personId, inquiry))
        triggers = triggers :+ ScheduleTrigger(PlanParkingInquiryTrigger(startTime, inquiry), self)
        startTime
      }
    }
    triggers
  }

  /**
    * summarizeAndSkimOrGetChargingData
    * @return map
    */
  protected def summarizeAndSkimOrGetChargingData(): Map[Id[ParkingZoneId], ChargingDataSummary] = {
    scaleUpFactorMaybe match {
      case Some(scaleUpFactor) if simulatedEvents.nonEmpty =>
        val chargingDataSummary = simulatedEvents.par
          .map { case (parkingZoneId, data) =>
            val rate = data.durations.size * scaleUpFactor / timeStepByHour
            val meanDur = data.durations.sum / data.durations.size
            val stdDevDur = Math.sqrt(data.durations.map(_ - meanDur).map(t => t * t).sum / data.durations.size)
            val meanFuel = data.fuel.sum / data.fuel.size
            val stdDevFuel = Math.sqrt(data.fuel.map(_ - meanFuel).map(t => t * t).sum / data.fuel.size)
            parkingZoneId -> ChargingDataSummary(
              rate,
              meanDur,
              stdDevDur,
              meanFuel,
              stdDevFuel,
              data.parkingZoneId,
              data.managerId
            )
          }
          .seq
          .toMap
        simulatedEvents.clear()
        chargingDataSummary
      case _ => Map.empty[Id[ParkingZoneId], ChargingDataSummary]
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
        if (!simulatedEvents.contains(stall.parkingZoneId)) {
          simulatedEvents.put(
            stall.parkingZoneId,
            ChargingData(
              ListBuffer.empty[Int],
              ListBuffer.empty[Double],
              stall.parkingZoneId,
              stall.reservedFor.managerId
            )
          )
        }
        val data = simulatedEvents(stall.parkingZoneId)
        val (chargingDuration, _) =
          vehicle.refuelingSessionDurationAndEnergyInJoulesForStall(Some(stall), None, None, None)
        simulatedEvents.put(
          stall.parkingZoneId,
          data.copy(
            durations = data.durations :+ chargingDuration,
            fuel = data.fuel :+ vehicle.primaryFuelLevelInJoules
          )
        )
      case _ =>
    }
  }
}

object ScaleUpCharging {
  case class PlanParkingInquiryTrigger(tick: Int, inquiry: ParkingInquiry) extends Trigger
  case class PlanChargingUnplugRequestTrigger(tick: Int, requestId: Int) extends Trigger

  case class ChargingData(
    durations: ListBuffer[Int],
    fuel: ListBuffer[Double],
    parkingZoneId: Id[ParkingZoneId],
    managerId: Id[VehicleManager]
  )

  case class ChargingDataSummary(
    rate: Double,
    avgDuration: Int,
    sdDuration: Double,
    avgFuel: Double,
    sdFuel: Double,
    parkingZoneId: Id[ParkingZoneId],
    managerId: Id[VehicleManager]
  )
  case class ChargingDataInquiry(startTime: Int, personId: Id[Person], parkingInquiry: ParkingInquiry)
}
