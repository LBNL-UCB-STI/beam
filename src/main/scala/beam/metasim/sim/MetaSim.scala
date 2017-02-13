package beam.metasim.sim

import akka.actor.{ActorRef, ActorSystem, Props}
import beam.metasim.playground.sid.events.EventsSubscriber.{FinishProcessing, StartProcessing}
import beam.metasim.playground.sid.events.MetaSimEventsBus.MetaSimEvent
import beam.metasim.playground.sid.events.{EventsSubscriber, MetaSimEventsBus}
import com.google.inject.Inject
import com.typesafe.config.Config
import org.matsim.api.core.v01.events.{PersonEntersVehicleEvent, PersonLeavesVehicleEvent}
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.events.{IterationStartsEvent, ShutdownEvent, StartupEvent}
import org.matsim.core.controler.listener.{IterationStartsListener, ShutdownListener, StartupListener}

/**
  * MetaSim entrypoint.
  * Should instantiate the [[ActorSystem]], [[MetaSimServices]] and interact concurrently w/ the QSim.
  *
  * Created by sfeygin on 2/8/17.
  */
class MetaSim @Inject()(private val actorSystem: ActorSystem,
                        private val services: MetaSimServices,
                        private val config:Config
                        ) extends StartupListener with IterationStartsListener with ShutdownListener{

  val metaSimEventsBus = new MetaSimEventsBus
  val eventsManager: EventsManager = services.matsimServices.getEvents
  val eventSubscriber: ActorRef = actorSystem.actorOf(Props(classOf[EventsSubscriber],eventsManager),"MATSimEventsManagerService")
  val scenario: Scenario = services.matsimServices.getScenario



  override def notifyStartup(event: StartupEvent): Unit = {
    eventSubscriber ! StartProcessing
    // create specific channel for
    metaSimEventsBus.subscribe(eventSubscriber,"/metasim_events/travel_events")
  }

  override def notifyIterationStarts(event: IterationStartsEvent): Unit = {

    var popMap = scala.collection.JavaConversions.mapAsScalaMap(scenario.getPopulation.getPersons)
    popMap.values.foreach {(v)=>{
      metaSimEventsBus.publish(MetaSimEvent("/metasim_events/travel_events", new PersonEntersVehicleEvent(0,v.getId,Id.createVehicleId(v.getId))))
      metaSimEventsBus.publish(MetaSimEvent("/metasim_events/travel_events", new PersonLeavesVehicleEvent(0,v.getId,Id.createVehicleId(v.getId))))
    }}
  }

  override def notifyShutdown(event: ShutdownEvent): Unit = {
    eventSubscriber ! FinishProcessing
    actorSystem.stop(eventSubscriber)

    actorSystem.terminate()

  }
}
