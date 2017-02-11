package beam.metasim.sim

import akka.actor.{ActorSystem, Props}
import beam.metasim.agents.Scheduler
import beam.metasim.playground.colin.EventsManagerService
import com.google.inject.Inject
import org.matsim.api.core.v01.Scenario
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.events.StartupEvent
import org.matsim.core.controler.listener.StartupListener
import org.matsim.core.mobsim.framework.Mobsim
import org.matsim.core.router.util.TravelTime

/**
  * The correct choice of TravelTime will be decided upon by configuration.
  *
  * Created by sfeygin on 2/8/17.
  */
class MetaSim @Inject()(private val actorSystem: ActorSystem,
                        private val eventsManager: EventsManager,
                        private val scenario:Scenario,
                        private val travelTime: TravelTime) extends Mobsim with StartupListener{

  override def run(): Unit = {
    val scheduler = actorSystem.actorOf(Props[Scheduler], "BeamAgent Scheduler")
    val ems = actorSystem.actorOf(Props(classOf[EventsManagerService],eventsManager),"MATSim Events Manager Service")

  }

  override def notifyStartup(event: StartupEvent): Unit = {
    run()
  }
}
