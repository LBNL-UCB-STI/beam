package beam.metasim.sim

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import beam.metasim.agents.PersonAgent
import beam.metasim.agents.PersonAgent.PersonData
import beam.metasim.playground.sid.events.EventsSubscriber
import beam.metasim.playground.sid.events.EventsSubscriber.{FinishProcessing, StartProcessing}
import com.google.inject.Inject
import com.typesafe.config.Config
import glokka.Registry
import glokka.Registry.{Created, Found}
import org.matsim.api.core.v01.Scenario
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.events.{IterationStartsEvent, ShutdownEvent, StartupEvent}
import org.matsim.core.controler.listener.{IterationStartsListener, ShutdownListener, StartupListener}

import scala.concurrent.Await

/**
  * MetaSim entrypoint.
  * Should instantiate the [[ActorSystem]], [[MetasimServices]] and interact concurrently w/ the QSim.
  *
  * Created by sfeygin on 2/8/17.
  */
class Metasim @Inject()(private val actorSystem: ActorSystem,
                        private val services: MetasimServices,
                        private val config: Config
                       ) extends StartupListener with IterationStartsListener with ShutdownListener {
  import MetasimServices._

  val eventsManager: EventsManager = services.matsimServices.getEvents
  val eventSubscriber: ActorRef = actorSystem.actorOf(Props(classOf[EventsSubscriber], eventsManager), "MATSimEventsManagerService")
  val scenario: Scenario = services.matsimServices.getScenario

  private implicit val timeout = Timeout(60, TimeUnit.SECONDS)

  override def notifyStartup(event: StartupEvent): Unit = {
    eventSubscriber ! StartProcessing
    // create specific channel for travel events, say
    metaSimEventsBus.subscribe(eventSubscriber, "/metasim_events/matsim_events")
    var popMap = scala.collection.JavaConverters.mapAsScalaMap(scenario.getPopulation.getPersons)
    for ((k, v) <- popMap) {

      val props = Props(classOf[PersonAgent], k, PersonData(v.getSelectedPlan))
      val future = registry ? Registry.Register(k.toString, props)
      val result = Await.result(future, timeout.duration).asInstanceOf[AnyRef]
      val ok = result.asInstanceOf[Created]
      println(s"${ok.name}")
    }
  }

  override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
    var popMap = scala.collection.JavaConverters.mapAsScalaMap(scenario.getPopulation.getPersons)
    for ((k, v) <- popMap) {
      val future = registry ? Registry.Lookup(k.toString)
      val result = Await.result(future, timeout.duration).asInstanceOf[AnyRef]
      val ok = result.asInstanceOf[Found]
      println(s"${ok.name}")
    }

  }

  override def notifyShutdown(event: ShutdownEvent): Unit = {
    eventSubscriber ! FinishProcessing
    actorSystem.stop(eventSubscriber)

    actorSystem.terminate()

  }
}
