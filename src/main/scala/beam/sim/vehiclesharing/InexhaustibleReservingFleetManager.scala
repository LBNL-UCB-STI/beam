package beam.sim.vehiclesharing
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.ask
import akka.pattern.pipe

import scala.concurrent.ExecutionContext.Implicits.global
import akka.util.Timeout
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.household.HouseholdActor.{MobilityStatusInquiry, MobilityStatusResponse, ReleaseVehicle}
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.ActualVehicle
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.{ParkingInquiry, ParkingInquiryResponse}
import beam.agentsim.scheduler.BeamAgentScheduler.CompletionNotice
import beam.agentsim.scheduler.Trigger.TriggerWithId
import org.matsim.api.core.v01.Id

import scala.util.Random

private[vehiclesharing] class InexhaustibleReservingFleetManager(
  val parkingManager: ActorRef,
  vehicleType: BeamVehicleType,
  randomSeed: Long
) extends Actor
    with ActorLogging {

  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)
  private val rand: Random = new Random(randomSeed)

  var nextVehicleIndex = 0

  override def receive: Receive = {
    case TriggerWithId(InitializeTrigger(_), triggerId) =>
      sender ! CompletionNotice(triggerId)

    case MobilityStatusInquiry(_, whenWhere, _) =>
      // Create a vehicle out of thin air
      val vehicle = new BeamVehicle(
        Id.createVehicleId(self.path.name + "-" + nextVehicleIndex),
        new Powertrain(0.0),
        vehicleType,
        rand.nextInt()
      )
      nextVehicleIndex += 1
      vehicle.setManager(Some(self))
      vehicle.spaceTime = whenWhere
      vehicle.becomeDriver(sender)

      // Park it and forward it to the customer
      (parkingManager ? parkingInquiry(whenWhere))
        .collect {
          case ParkingInquiryResponse(stall, _) =>
            vehicle.useParkingStall(stall)
            MobilityStatusResponse(Vector(ActualVehicle(vehicle)))
        } pipeTo sender

    case ReleaseVehicle(_) =>
    // That's fine, nothing to do.

  }

  def parkingInquiry(whenWhere: SpaceTime): ParkingInquiry = ParkingInquiry(whenWhere.loc, "wherever")

}
