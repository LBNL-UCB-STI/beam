package beam.physsim.jdeqsim.akka

import java.io.File

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Stash, UntypedActor}
import beam.utils.DebugLib
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Network
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.events.handler.EventHandler
import org.matsim.core.trafficmonitoring.TravelTimeCalculator
import java.util

import akka.event.LoggingReceive
import beam.sim.BeamServices
import org.matsim.core.events.algorithms.EventWriterXML
import org.matsim.core.network.NetworkUtils


object EventManagerActor {
  val LAST_MESSAGE = "lastMessage"
  def REGISTER_JDEQSIM_REF = "registerJDEQSimREF"
  def props(beamServices: BeamServices): Props = Props.create(classOf[EventManagerActor], beamServices)
}

class EventManagerActor(var beamServices: BeamServices) extends Actor with Stash with ActorLogging {

  resetEventsActor()
  var jdeqsimActorREF: ActorRef = _
  var travelTimeCalculator: TravelTimeCalculator = _
  var eventsManager: EventsManager = _
  var eventsWriterXML: EventWriterXML = _

  private def resetEventsActor(): Unit = {
    eventsManager = new EventsManagerImpl
    val ttccg = new TravelTimeCalculatorConfigGroup
    travelTimeCalculator = new TravelTimeCalculator(beamServices.matsimServices.getScenario.getNetwork, ttccg)
    eventsManager.addHandler(travelTimeCalculator)
    addEventWriter
  }

  private def addEventWriter = {
    val writeEventsInterval = beamServices.beamConfig.beam.outputs.writePhysSimEventsInterval
    val iteration = beamServices.matsimServices.getIterationNumber

    if (writeEventsInterval == 1 || (writeEventsInterval > 0 && beamServices.matsimServices.getIterationNumber / writeEventsInterval == 0)) {
      createNetworkFile
      eventsWriterXML = new EventWriterXML(beamServices.matsimServices.getControlerIO.getIterationFilename(iteration, "physSimEvents.xml.gz"))
      eventsManager.addHandler(eventsWriterXML)
    }
  }

  private def createNetworkFile = {
    val physSimNetworkFilePath = beamServices.matsimServices.getControlerIO.getOutputFilename("physSimNetwork.xml.gz")
    if (!(new File(physSimNetworkFilePath)).exists()) {
      NetworkUtils.writeNetwork(beamServices.matsimServices.getScenario.getNetwork, physSimNetworkFilePath)
    }
  }

  override def receive = LoggingReceive{

    case event: Event => eventsManager.processEvent(event)
    case s: String => {
      if (s.equalsIgnoreCase(EventManagerActor.LAST_MESSAGE)) {
        jdeqsimActorREF.tell(travelTimeCalculator, self)
        if (eventsWriterXML!=null){
          eventsWriterXML.closeFile()
        }
      }
      else if (s.equalsIgnoreCase(EventManagerActor.REGISTER_JDEQSIM_REF)) jdeqsimActorREF = sender
      else DebugLib.stopSystemAndReportUnknownMessageType()
    }
    case _ => DebugLib.stopSystemAndReportUnknownMessageType()
  }
}
