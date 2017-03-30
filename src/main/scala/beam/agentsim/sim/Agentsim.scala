package beam.agentsim.sim

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents.BeamAgentScheduler.{ScheduleTrigger, StartSchedule}
import beam.agentsim.agents.PersonAgent.PersonData
import beam.agentsim.agents.{BeamAgentScheduler, InitializeTrigger, PersonAgent}
import beam.agentsim.events.{EventsSubscriber, JsonFriendlyEventWriterXML, PathTraversalEvent}
import beam.agentsim.routing.RoutingMessages.InitializeRouter
import beam.agentsim.routing.opentripplanner.OpenTripPlannerRouter
import beam.agentsim.utils.JsonUtils
import com.google.inject.Inject
import glokka.Registry
import glokka.Registry.Created
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.events.{IterationEndsEvent, IterationStartsEvent, ShutdownEvent, StartupEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, IterationStartsListener, ShutdownListener, StartupListener}
import org.matsim.core.events.EventsUtils
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.immutable.ListMap
import scala.concurrent.Await

/**
  * AgentSim entrypoint.
  * Should instantiate the [[ActorSystem]], [[AgentsimServices]] and interact concurrently w/ the QSim.
  *
  * Created by sfeygin on 2/8/17.
  */
class Agentsim @Inject()(private val actorSystem: ActorSystem,
                         private val services: AgentsimServices
                        ) extends StartupListener  with IterationStartsListener with IterationEndsListener with ShutdownListener {

  import AgentsimServices._

  private val logger: Logger = LoggerFactory.getLogger(classOf[Agentsim])
  private val popMap: Map[Id[Person], Person] = ListMap(scala.collection.JavaConverters.mapAsScalaMap(services.matsimServices.getScenario.getPopulation.getPersons).toSeq.sortBy(_._1): _*)
  val eventsManager: EventsManager = EventsUtils.createEventsManager()
  val eventSubscriber: ActorRef = actorSystem.actorOf(Props(classOf[EventsSubscriber], eventsManager), "MATSimEventsManagerService")
  var writer: JsonFriendlyEventWriterXML = _
  var currentIter = 0


  private implicit val timeout = Timeout(1200, TimeUnit.SECONDS)

  override def notifyStartup(event: StartupEvent): Unit = {

    val schedulerFuture = registry ? Registry.Register("scheduler", Props(classOf[BeamAgentScheduler]))
    schedulerRef = Await.result(schedulerFuture, timeout.duration).asInstanceOf[Created].ref

    val routerFuture = registry ? Registry.Register("router", Props(classOf[OpenTripPlannerRouter], services))
    beamRouter = Await.result(routerFuture, timeout.duration).asInstanceOf[Created].ref
    val routerInitFuture = beamRouter ? InitializeRouter
    Await.result(routerInitFuture, timeout.duration)

    agentSimEventsBus.subscribe(eventSubscriber, "actend")
    agentSimEventsBus.subscribe(eventSubscriber, "actstart")
    agentSimEventsBus.subscribe(eventSubscriber, "PersonEntersVehicle")
    agentSimEventsBus.subscribe(eventSubscriber, "PersonLeavesVehicle")
    agentSimEventsBus.subscribe(eventSubscriber, "vehicle enters traffic")
    agentSimEventsBus.subscribe(eventSubscriber, PathTraversalEvent.EVENT_TYPE)
    agentSimEventsBus.subscribe(eventSubscriber, "vehicle leaves traffic")
    agentSimEventsBus.subscribe(eventSubscriber, "VehicleArrivesAtFacility")
    agentSimEventsBus.subscribe(eventSubscriber, "VehicleDepartsAtFacility")
    agentSimEventsBus.subscribe(eventSubscriber, "departure")
    agentSimEventsBus.subscribe(eventSubscriber, "waitingForPt")
    agentSimEventsBus.subscribe(eventSubscriber, "travelled")
    agentSimEventsBus.subscribe(eventSubscriber, "arrival")
  }

  override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
    // TODO replace magic numbers
    currentIter = event.getIteration
    writer = new JsonFriendlyEventWriterXML(services.matsimServices.getControlerIO.getIterationFilename(currentIter,"events.xml.gz"))
    eventsManager.addHandler(writer)
    resetPop(event.getIteration)
    eventsManager.initProcessing()
    Await.result(schedulerRef ? StartSchedule(120000.0, 300.0), timeout.duration)
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    cleanupWriter()

//    Await.result(eventSubscriber ? EndIteration(event.getIteration),timeout.duration)
  }

  private def cleanupWriter() = {
    eventsManager.finishProcessing()
    writer.closeFile()
    eventsManager.removeHandler(writer)
    writer = null
    JsonUtils.processEventsFileVizData(services.matsimServices.getControlerIO.getIterationFilename(currentIter,"events.xml.gz"),
      services.matsimServices.getControlerIO.getIterationFilename(currentIter,"events.json"))
  }

  override def notifyShutdown(event: ShutdownEvent): Unit = {

    if(writer!=null && event.isUnexpected){
      cleanupWriter()
    }
    actorSystem.stop(eventSubscriber)
    actorSystem.stop(schedulerRef)
    actorSystem.terminate()
  }

  def resetPop(iter: Int): Unit = {
    for ((k, v) <- popMap) {
      val props = Props(classOf[PersonAgent], k, PersonData(v.getSelectedPlan))
      val ref: ActorRef = actorSystem.actorOf(props, s"${k.toString}_$iter")
      schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), ref)
    }
  }

}



