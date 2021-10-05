package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.RefuelSessionEvent.NotApplicable
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingPlugRequest
import beam.agentsim.infrastructure.ParkingInquiry.ParkingActivityType
import beam.agentsim.infrastructure.parking.{ParkingType, ParkingZoneId}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.BeamConfig.Beam.Agentsim
import beam.utils.{MathUtils, VehicleIdGenerator}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Random

trait ScaleUpCharging extends {
  this: ChargingNetworkManager =>
  import ScaleUpCharging._

  private lazy val rand: Random = new Random(beamConfig.matsim.modules.global.randomSeed)
  private lazy val cnmConfig: Agentsim.ChargingNetworkManager = beamConfig.beam.agentsim.chargingNetworkManager

  private lazy val scaleUpFactor = {
    if (cnmConfig.scaleUpExpansionFactor <= 1.0) 0.0
    else {
      val popExpansionFactor = 1.0 / beamConfig.beam.agentsim.agentSampleSizeAsFractionOfPopulation
      Math.max(cnmConfig.scaleUpExpansionFactor, popExpansionFactor) - 1
    }
  }

  protected lazy val inquiryMap: mutable.Map[Int, ChargingDataInquiry] = mutable.Map()
  protected lazy val simulatedEvents: mutable.Map[Id[ParkingZoneId], ChargingData] = mutable.Map()

  override def loggedReceive: Receive = {
    case TriggerWithId(PlanParkingInquiryTrigger(_, inquiry), triggerId) =>
      self ! inquiry
      getScheduler ! CompletionNotice(triggerId, Vector())
    case response @ ParkingInquiryResponse(stall, requestId, triggerId) =>
      log.debug(s"Received parking response: $response")
      val request = ChargingPlugRequest(
        inquiryMap(requestId).startTime,
        inquiryMap(requestId).parkingInquiry.beamVehicle.get,
        stall,
        inquiryMap(requestId).personId,
        triggerId,
        NotApplicable,
        None
      )
      self ! request
  }

  /**
    * Next Time poisson
    * @param rate rate of charging event
    * @return
    */
  private def nextTimePoisson(rate: Double): Double = -Math.log(1.0 - rand.nextDouble()) / rate

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
      val scaledUpNumEvents = MathUtils.roundUniformly(data.rate * 0.25, rand).toInt
      (1 to scaledUpNumEvents).foldLeft(0.0) { case (acc, elem) =>
        val timeStep = acc + nextTimePoisson(elem)
        val startTime = MathUtils.roundUniformly(timeBin + timeStep, rand).toInt
        val duration = MathUtils.roundUniformly(rand.nextGaussian() * data.avgDuration * data.sdDuration, rand).toInt
        val parkingZone = getAppropriateChargingNetwork(data.managerId).chargingZones(parkingZoneId)
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
          seatingCapacity = 1,
          standingRoomCapacity = 1,
          lengthInMeter = 3,
          primaryFuelType = FuelType.Electricity,
          primaryFuelConsumptionInJoulePerMeter = 0.1,
          primaryFuelCapacityInJoule = 0.1,
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
        timeStep
      }
    }
    triggers
  }

  /**
    * summarizeAndSkimOrGetChargingData
    * @return map
    */
  protected def summarizeAndSkimOrGetChargingData(): Map[Id[ParkingZoneId], ChargingDataSummary] = {
    val chargingDataSummary = simulatedEvents.par
      .map { case (parkingZoneId, data) =>
        val mean: Double = data.durations.sum.toDouble / data.durations.size
        val stdDev: Double = Math.sqrt(data.durations.map(_ - mean).map(t => t * t).sum / data.durations.size)
        val rate = data.durations.size * scaleUpFactor / 0.25
        parkingZoneId -> ChargingDataSummary(rate, mean, stdDev, data.parkingZoneId, data.managerId)
      }
      .seq
      .toMap
    simulatedEvents.clear()
    chargingDataSummary
  }

  /**
    * Collect Charging Data
    * @param stall Parking Stall
    * @param vehicle Beam Vehicle
    */
  protected def collectChargingData(stall: ParkingStall, vehicle: BeamVehicle): Unit = {
    if (!simulatedEvents.contains(stall.parkingZoneId)) {
      simulatedEvents.put(
        stall.parkingZoneId,
        ChargingData(ListBuffer.empty[Int], stall.parkingZoneId, stall.reservedFor.managerId)
      )
    }
    val data = simulatedEvents(stall.parkingZoneId)
    val (chargingDuration, _) = vehicle.refuelingSessionDurationAndEnergyInJoulesForStall(Some(stall), None, None, None)
    simulatedEvents.put(stall.parkingZoneId, data.copy(durations = data.durations :+ chargingDuration))
  }
}

object ScaleUpCharging {
  case class PlanParkingInquiryTrigger(tick: Int, inquiry: ParkingInquiry) extends Trigger
  case class PlanChargingPlugRequestTrigger(tick: Int, request: ChargingPlugRequest) extends Trigger
  case class ChargingData(durations: ListBuffer[Int], parkingZoneId: Id[ParkingZoneId], managerId: Id[VehicleManager])

  case class ChargingDataSummary(
    rate: Double,
    avgDuration: Double,
    sdDuration: Double,
    parkingZoneId: Id[ParkingZoneId],
    managerId: Id[VehicleManager]
  )
  case class ChargingDataInquiry(startTime: Int, personId: Id[Person], parkingInquiry: ParkingInquiry)
}
