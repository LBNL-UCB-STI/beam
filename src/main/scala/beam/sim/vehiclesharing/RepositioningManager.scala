package beam.sim.vehiclesharing
import akka.actor.ActorRef
import akka.event.LoggingAdapter
import beam.agentsim.agents.HasTickAndTrigger
import beam.agentsim.agents.household.HouseholdActor.ReleaseVehicleAndReply
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.router.BeamSkimmer
import beam.sim.BeamServices
import com.vividsolutions.jts.index.quadtree.Quadtree

class RepositioningManager(
  val log: LoggingAdapter,
  val vehicleSharingManagerRef: ActorRef,
  val vehicleSharingManager: FixedNonReservingFleetManager,
  val scheduler: ActorRef,
  val services: BeamServices,
  val skimmer: BeamSkimmer
) extends HasTickAndTrigger {

  val timeStep: Int = 15 * 60

  def teleport(vehicle: BeamVehicle, tick: Int): Unit = {
    vehicle.manager.get ! ReleaseVehicleAndReply(vehicle, Some(tick))
  }

  def reposition(tick: Int, triggerId: Long, vehicles: Quadtree): Unit = {
    val nextTick = tick + timeStep
    new AvailabilityBasedRepositioning(skimmer, services, vehicles).
      getVehiclesForReposition(tick, nextTick).foreach {
      x => x.vehicle.manager.get ! x
    }
    scheduleNextRelocation(nextTick, triggerId)
  }

  def scheduleNextRelocation(nextTick: Int, triggerId: Long): Unit = {
    scheduler.tell(
      CompletionNotice(
        triggerId,
        Vector(ScheduleTrigger(VehicleSharingRepositioningTrigger(nextTick), vehicleSharingManagerRef))
      ),
      vehicleSharingManagerRef
    )
  }

}

case class VehicleSharingRepositioningTrigger(tick: Int) extends Trigger
case class VehicleSharingTeleportationTrigger(tick: Int, vehicle: BeamVehicle) extends Trigger
case class VehiclesForReposition(vehicle: BeamVehicle, where: SpaceTime)
