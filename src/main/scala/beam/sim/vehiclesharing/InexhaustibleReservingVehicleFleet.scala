package beam.sim.vehiclesharing
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.ask
import akka.pattern.pipe
import scala.concurrent.ExecutionContext.Implicits.global
import akka.util.Timeout
import beam.agentsim.agents.household.HouseholdActor.{MobilityStatusInquiry, MobilityStatusResponse, ReleaseVehicle}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ParkingManager.{ParkingInquiry, ParkingInquiryResponse}
import beam.agentsim.infrastructure.ParkingStall.NoNeed
import org.matsim.api.core.v01.Id

class InexhaustibleReservingVehicleFleet(val parkingManager: ActorRef) extends Actor with ActorLogging {

  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)

  var nextVehicleIndex = 0

  override def receive: Receive = {

    case MobilityStatusInquiry(whenWhere) =>
      // Create a vehicle out of thin air
      val vehicle = new BeamVehicle(
        Id.createVehicleId("inexhaustible-shared-vehicle-fleet-" + nextVehicleIndex),
        new Powertrain(0.0),
        None,
        BeamVehicleType.defaultCarBeamVehicleType
      )
      nextVehicleIndex += 1
      vehicle.manager = Some(self)
      vehicle.exclusiveAccess = true
      vehicle.spaceTime = whenWhere
      vehicle.becomeDriver(sender)

      // Park it and forward it to the customer
      (parkingManager ? parkingInquiry(whenWhere))
        .collect {
          case ParkingInquiryResponse(stall, _) =>
            vehicle.useParkingStall(stall)
            MobilityStatusResponse(Vector(vehicle))
        } pipeTo sender

    case ReleaseVehicle(_) =>
    // That's fine, nothing to do.

  }

  def parkingInquiry(whenWhere: SpaceTime) = ParkingInquiry(
    whenWhere.loc,
    whenWhere.loc,
    "wherever",
    0,
    NoNeed,
    0,
    0
  )

}
