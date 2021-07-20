package beam.sim.vehiclesharing

import akka.actor.{ActorLogging, ActorRef}
import akka.pattern.pipe
import akka.util.Timeout
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.household.HouseholdActor._
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.ActualVehicle
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleManager}
import beam.agentsim.infrastructure.{ParkingInquiry, ParkingInquiryResponse}
import beam.agentsim.scheduler.BeamAgentScheduler.CompletionNotice
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.BeamConfig.Beam.Debug
import beam.utils.logging.LoggingMessageActor
import beam.utils.logging.pattern.ask
import org.matsim.api.core.v01.Id

import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random

private[vehiclesharing] class InexhaustibleReservingFleetManager(
  vehicleManagerId: Id[VehicleManager],
  val parkingManager: ActorRef,
  vehicleType: BeamVehicleType,
  randomSeed: Long,
  implicit val debug: Debug
) extends LoggingMessageActor
    with ActorLogging {

  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)
  private val rand: Random = new Random(randomSeed)

  var nextVehicleIndex = 0

  override def loggedReceive: Receive = {
    case TriggerWithId(InitializeTrigger(_), triggerId) =>
      sender ! CompletionNotice(triggerId)

    case GetVehicleTypes(triggerId) =>
      sender() ! VehicleTypesResponse(Set(vehicleType), triggerId)

    case MobilityStatusInquiry(_, whenWhere, _, triggerId) =>
      // Create a vehicle out of thin air
      val vehicle = new BeamVehicle(
        Id.createVehicleId(self.path.name + "-" + nextVehicleIndex),
        new Powertrain(0.0),
        vehicleType,
        vehicleManagerId = vehicleManagerId,
        rand.nextInt()
      )
      nextVehicleIndex += 1
      vehicle.setManager(Some(self))
      vehicle.spaceTime = whenWhere
      vehicle.becomeDriver(sender)

      // Park it and forward it to the customer
      (parkingManager ? ParkingInquiry.init(whenWhere, "wherever", vehicleManagerId, triggerId = triggerId))
        .collect { case ParkingInquiryResponse(stall, _, triggerId) =>
          vehicle.useParkingStall(stall)
          MobilityStatusResponse(Vector(ActualVehicle(vehicle)), triggerId)
        } pipeTo sender

    case ReleaseVehicle(_, _) =>
    // That's fine, nothing to do.

  }
}
