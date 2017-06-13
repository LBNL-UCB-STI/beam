package beam.sim

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import beam.agentsim.agents.PersonAgent.PersonData
import beam.agentsim.agents.TaxiAgent.TaxiData
import beam.agentsim.agents._
import beam.agentsim.events.{EventsSubscriber, JsonFriendlyEventWriterXML, PathTraversalEvent, PointProcessEvent}
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{ScheduleTrigger, StartSchedule}
import beam.physsim.{DummyPhysSim, InitializePhysSim}
import beam.router.BeamRouter.InitializeRouter
import beam.router.DummyRouter
import beam.utils.JsonUtils
import com.google.inject.Inject
import glokka.Registry
import glokka.Registry.Created
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.{AgentWaitingForPtEvent, EventsManager, TeleportationArrivalEvent}
import org.matsim.core.controler.events.{IterationEndsEvent, IterationStartsEvent, ShutdownEvent, StartupEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, IterationStartsListener, ShutdownListener, StartupListener}
import org.matsim.core.events.EventsUtils
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.immutable.ListMap
import scala.collection.mutable
import scala.concurrent.Await
import scala.util.Random

/**
  * AgentSim entrypoint.
  * Should instantiate the [[ActorSystem]], [[BeamServices]] and interact concurrently w/ the QSim.
  *
  * Created by sfeygin on 2/8/17.
  */
class BeamSim @Inject()(private val actorSystem: ActorSystem,
                        private val services: BeamServices
                        ) extends StartupListener with IterationStartsListener with IterationEndsListener with ShutdownListener {

  private val logger: Logger = LoggerFactory.getLogger(classOf[BeamSim])
  val eventsManager: EventsManager = EventsUtils.createEventsManager()
  implicit val eventSubscriber: ActorRef = actorSystem.actorOf(Props(classOf[EventsSubscriber], eventsManager), "MATSimEventsManagerService")
  var writer: JsonFriendlyEventWriterXML = _
  var currentIter = 0

  private implicit val timeout = Timeout(5000, TimeUnit.SECONDS)

  override def notifyStartup(event: StartupEvent): Unit = {
    services.popMap = Some(ListMap(scala.collection.JavaConverters
      .mapAsScalaMap(services.matsimServices.getScenario.getPopulation.getPersons).toSeq.sortBy(_._1): _*))

    subscribe(ActivityEndEvent.EVENT_TYPE)
    subscribe(ActivityStartEvent.EVENT_TYPE)
    subscribe(PersonEntersVehicleEvent.EVENT_TYPE)
    subscribe(PersonLeavesVehicleEvent.EVENT_TYPE)
    subscribe(VehicleEntersTrafficEvent.EVENT_TYPE)
    subscribe(PathTraversalEvent.EVENT_TYPE)
    subscribe(VehicleLeavesTrafficEvent.EVENT_TYPE)
    subscribe(PersonDepartureEvent.EVENT_TYPE)
    subscribe(AgentWaitingForPtEvent.EVENT_TYPE)
    subscribe(TeleportationArrivalEvent.EVENT_TYPE)
    subscribe(PersonArrivalEvent.EVENT_TYPE)
    subscribe(PointProcessEvent.EVENT_TYPE)

    val schedulerFuture = services.registry ? Registry.Register("scheduler", Props(classOf[BeamAgentScheduler]))
    services.schedulerRef = Await.result(schedulerFuture, timeout.duration).asInstanceOf[Created].ref

    val routerFuture = services.registry ? Registry.Register("router", DummyRouter.props(services))
    services.beamRouter = Await.result(routerFuture, timeout.duration).asInstanceOf[Created].ref
    val routerInitFuture = services.beamRouter ? InitializeRouter
    Await.result(routerInitFuture, timeout.duration)

    val physSimFuture = services.registry ? Registry.Register("physSim", DummyPhysSim.props(services))
    services.physSim = Await.result(physSimFuture, timeout.duration).asInstanceOf[Created].ref
    val physSimInitFuture = services.physSim ? new InitializePhysSim()
    Await.result(physSimInitFuture, timeout.duration)

    val taxiManagerFuture = services.registry ? Registry.Register("taxiManager", TaxiManager.props(services))
    services.taxiManager = Await.result(taxiManagerFuture, timeout.duration).asInstanceOf[Created].ref

  }

  override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
    // TODO replace magic numbers
    currentIter = event.getIteration
    writer = new JsonFriendlyEventWriterXML(services.matsimServices.getControlerIO.getIterationFilename(currentIter, "events.xml.gz"))
    eventsManager.addHandler(writer)
    resetPop(event.getIteration)
    eventsManager.initProcessing()
    Await.result(services.schedulerRef ? StartSchedule(3600*9.0, 300.0), timeout.duration)
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    cleanupWriter()
  }

  private def cleanupWriter() = {
    eventsManager.finishProcessing()
    writer.closeFile()
    eventsManager.removeHandler(writer)
    writer = null
    JsonUtils.processEventsFileVizData(services.matsimServices.getControlerIO.getIterationFilename(currentIter, "events.xml.gz"),
      services.matsimServices.getControlerIO.getOutputFilename("trips.json"))
  }

  override def notifyShutdown(event: ShutdownEvent): Unit = {

    if (writer != null && event.isUnexpected) {
      cleanupWriter()
    }
    actorSystem.stop(eventSubscriber)
    actorSystem.stop(services.schedulerRef)
    actorSystem.terminate()
  }

  def resetPop(iter: Int): Unit = {
    val behaviorsToIncludeInPop: mutable.HashSet[Class[_]] = mutable.HashSet(CanUseTaxi.getClass)
    for ((k, v) <- services.popMap.take(services.beamConfig.beam.agentsim.numAgents).flatten) {
      val props = PersonAgent.props(k,PersonData(v.getSelectedPlan),services,behaviorsToIncludeInPop)
      val ref: ActorRef = actorSystem.actorOf(props, s"${k.toString}_$iter")
      services.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), ref)
    }
    // Generate taxis and intialize them to be located within ~initialLocationJitter km of a subset of agents
    //TODO put these in config
    val taxiFraction = 0.1
    val initialLocationJitter = 2000 // meters
    for((k,v) <- services.popMap.get.take(math.round(taxiFraction * services.popMap.size).toInt)){
      val personInitialLocation: Coord = v.getSelectedPlan.getPlanElements.iterator().next().asInstanceOf[Activity].getCoord
      val taxiInitialLocation: Coord = new Coord(personInitialLocation.getX + initialLocationJitter * 2.0 * (Random.nextDouble() - 0.5),personInitialLocation.getY + initialLocationJitter * 2.0 * (Random.nextDouble() - 0.5))
      val props = Props(classOf[TaxiAgent], Id.create(k.toString,TaxiAgent.getClass), TaxiData(taxiInitialLocation))
      val ref: ActorRef = actorSystem.actorOf(props, s"taxi_${k.toString}_$iter")
      services.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), ref)
    }
  }


  def subscribe(eventType: String): Unit = {
    services.agentSimEventsBus.subscribe(eventSubscriber, eventType)
  }


}



