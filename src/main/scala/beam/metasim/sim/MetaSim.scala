package beam.metasim.sim

import akka.actor.{ActorRef, ActorSystem, Props}
import beam.metasim.agents.BeamAgentScheduler
import beam.metasim.playground.sid.events.EventsSubscriber.{FinishProcessing, StartProcessing}
import beam.metasim.playground.sid.events.MetaSimEventsBus.MetaSimEvent
import beam.metasim.playground.sid.events.{EventsSubscriber, MetaSimEventsBus}
import com.google.inject.Inject
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.events.{IterationStartsEvent, ShutdownEvent, StartupEvent}
import org.matsim.core.controler.listener.{IterationStartsListener, ShutdownListener, StartupListener}
import org.matsim.core.router.util.TravelTime

/**
  * The correct choice of TravelTime will be decided upon by configuration.
  *
  * Created by sfeygin on 2/8/17.
  */
class MetaSim @Inject()(private val actorSystem: ActorSystem,
                        private val eventsManager: EventsManager,
                        private val scenario:Scenario,
                        private val travelTime: TravelTime) extends StartupListener with IterationStartsListener with ShutdownListener{

  val metaSimEventsBus = new MetaSimEventsBus
  val scheduler: ActorRef = actorSystem.actorOf(Props[BeamAgentScheduler], "BeamAgentScheduler")
  val eventSubscriber: ActorRef = actorSystem.actorOf(Props(classOf[EventsSubscriber],eventsManager,metaSimEventsBus),"MATSimEventsManagerService")


  override def notifyStartup(event: StartupEvent): Unit = {

    eventSubscriber ! StartProcessing
    metaSimEventsBus.subscribe(eventSubscriber,"/metasim_events")
  }

  override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
    val popFactory = scenario.getPopulation.getFactory
    val person = popFactory.createPerson(Id.createPersonId(0))
    metaSimEventsBus.publish(MetaSimEvent("/metasim_events", new PersonEntersVehicleEvent(0,person.getId,Id.createVehicleId(0))))
  }

  override def notifyShutdown(event: ShutdownEvent): Unit = {
    eventSubscriber ! FinishProcessing
    actorSystem.stop(eventSubscriber)
    actorSystem.stop(scheduler)
    actorSystem.terminate()

  }
}
