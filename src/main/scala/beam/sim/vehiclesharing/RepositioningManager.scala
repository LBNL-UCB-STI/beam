package beam.sim.vehiclesharing
import akka.actor.ActorRef
import akka.event.LoggingAdapter
import beam.agentsim.agents.HasTickAndTrigger
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.sim.config.BeamConfig

class RepositioningManager(
  val log: LoggingAdapter,
  val vehicleSharingManagerRef: ActorRef,
  val vehicleSharingManager: FixedNonReservingFleetManager,
  val scheduler: ActorRef,
  val beamConfig: BeamConfig
) extends HasTickAndTrigger {

  var allTriggersInWave: Vector[ScheduleTrigger] = Vector()

  def sendCompletionAndScheduleNewTimeout(batchDispatchType: VehicleSharingDispatchType, tick: Int): Unit = {
    val (currentTick, triggerId) = releaseTickAndTriggerId()
    val timerTrigger = batchDispatchType match {
      case Reposition =>
        VehicleSharingRepositioningTrigger(
          currentTick + 15 * 60
        )
      case _ =>
        throw new RuntimeException("?")
    }
    scheduler.tell(
      CompletionNotice(triggerId, allTriggersInWave :+ ScheduleTrigger(timerTrigger, vehicleSharingManagerRef)),
      vehicleSharingManagerRef
    )
    allTriggersInWave = Vector()
  }

  def repositioningScheduleAckReceived(
    triggersToSchedule: Vector[BeamAgentScheduler.ScheduleTrigger],
    tick: Int
  ): Unit = {
    sendCompletionAndScheduleNewTimeout(Reposition, tick)
  }

  def startWaveOfRepositioningOrBatchedReservationRequests(tick: Int, triggerId: Long): Unit = {
    holdTickAndTriggerId(tick, triggerId)
  }

}

sealed trait VehicleSharingDispatchType
case object Reposition extends VehicleSharingDispatchType