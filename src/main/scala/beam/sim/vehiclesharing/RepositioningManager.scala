package beam.sim.vehiclesharing
import akka.actor.ActorRef
import akka.event.LoggingAdapter
import beam.agentsim.agents.HasTickAndTrigger
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.router.BeamSkimmer
import beam.router.BeamSkimmer.SkimPlus
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id

class RepositioningManager(
  val log: LoggingAdapter,
  val vehicleSharingManagerRef: ActorRef,
  val vehicleSharingManager: FixedNonReservingFleetManager,
  val scheduler: ActorRef,
  val services: BeamServices,
  val skimmer: BeamSkimmer
) extends HasTickAndTrigger {

  val timeStep: Int = 15 * 60

  def relocate(tick: Int, triggerId: Long): Unit = {
    val nextTick = tick + timeStep
    val skims = skimmer.getSkimPlusValues(tick, nextTick, Id.create(4, classOf[TAZ]), "default")
    if(skims.nonEmpty) {
      val minAvailability = skims.map(_.availableVehicles).min
      val avgDemand = skims
      .foldLeft((0.0, 1)) {
        case ((avg, idx), next) => (avg + (next.demand - avg) / idx, idx + 1)
      }
      ._1
      println(
        s"reposition tick ======> $tick | minAvailability: $minAvailability | avgDemand: $avgDemand"
      )
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
