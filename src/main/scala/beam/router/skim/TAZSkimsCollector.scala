package beam.router.skim

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.util.Timeout
import akka.pattern.ask
import akka.pattern.pipe
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.BeamServices
import beam.utils.DateUtils

import scala.concurrent.{ExecutionContext, Future}

class TAZSkimsCollector(scheduler: ActorRef, beamServices: BeamServices, vehicleManagers: Seq[ActorRef])
    extends Actor
    with ActorLogging {
  import TAZSkimsCollector._
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)
  private implicit val executionContext: ExecutionContext = context.dispatcher
  private val endOfSimulationTime: Int = DateUtils.getEndOfTime(beamServices.beamScenario.beamConfig)
  private val timeBin: Int = beamServices.beamConfig.beam.router.skim.taz_skimmer.timeBin

  override def receive: Receive = {
    case TriggerWithId(InitializeTrigger(_), triggerId) =>
      Future(scheduler ? ScheduleTrigger(TAZSkimsCollectionTrigger(timeBin), self))
        .map(_ => CompletionNotice(triggerId, Vector()))
        .pipeTo(sender())

    case TriggerWithId(TAZSkimsCollectionTrigger(tick), triggerId) =>
      vehicleManagers.foreach(_ ! TAZSkimsCollectionTrigger(tick))
      if (tick + timeBin <= endOfSimulationTime) {
        sender ! CompletionNotice(triggerId, Vector(ScheduleTrigger(TAZSkimsCollectionTrigger(tick + timeBin), self)))
      } else {
        sender ! CompletionNotice(triggerId)
      }

    case Finish =>
  }
}

object TAZSkimsCollector {
  case class TAZSkimsCollectionTrigger(tick: Int) extends Trigger

  def props(scheduler: ActorRef, services: BeamServices, actorsForTazSkimmer: Seq[ActorRef]): Props = {
    Props(new TAZSkimsCollector(scheduler, services, actorsForTazSkimmer))
  }
}
