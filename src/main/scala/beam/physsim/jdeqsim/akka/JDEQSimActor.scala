package beam.physsim.jdeqsim.akka

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Stash, UntypedActor}
import akka.event.LoggingReceive
import beam.router.BeamRouter
import beam.utils.DebugLib
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.Population
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup
import org.matsim.core.trafficmonitoring.TravelTimeCalculator
import org.matsim.core.utils.collections.Tuple


object JDEQSimActor {
  def START_PHYSSIM: String = "startPhyssim"
  def ALL_MESSAGES_PROCESSED: String = "allMessagesProcssed?"
  var allMessagesProcessed: Boolean =false;

  def props(config: JDEQSimConfigGroup, scenario: Scenario, events: EventsManager, beamRouterRef: ActorRef): Props = Props.create(classOf[JDEQSimActor], config, scenario, events, beamRouterRef)
}

class JDEQSimActor(var config: JDEQSimConfigGroup, var agentSimScenario: Scenario, var events: EventsManager, var beamRouterRef: ActorRef)
  extends Actor with Stash with ActorLogging {

  var jdeqSimulation: JDEQSimulation = _
  var jdeqSimPopulation: Population = _

  private def runPhysicalSimulation(): Unit = {

    jdeqSimulation = new JDEQSimulation(config, jdeqSimPopulation, events, agentSimScenario.getNetwork, agentSimScenario.getConfig.plans.getActivityDurationInterpretation)
    jdeqSimulation.run()
    events.finishProcessing()
  }

  override def receive = LoggingReceive{

    case tuple: Tuple[_, _] => {

      val messageString = tuple.getFirst.asInstanceOf[String]

      if (messageString.equalsIgnoreCase(JDEQSimActor.START_PHYSSIM)) {

        this.jdeqSimPopulation = tuple.getSecond.asInstanceOf[Population]
        runPhysicalSimulation()
      }
      else DebugLib.stopSystemAndReportUnknownMessageType()
    }

    case message:String => {
      if (message.equalsIgnoreCase(JDEQSimActor.ALL_MESSAGES_PROCESSED)) {
          if (JDEQSimActor.allMessagesProcessed){
            stash()
          } else {
            sender()!"done"
          }
      }
    }


    case travelTimeCalculator: TravelTimeCalculator => {
      beamRouterRef.tell(new BeamRouter.UpdateTravelTime(travelTimeCalculator.getLinkTravelTimes), self)
      JDEQSimActor.allMessagesProcessed=true;
      unstashAll()
    }
    case _ => DebugLib.stopSystemAndReportUnknownMessageType()
  }
}