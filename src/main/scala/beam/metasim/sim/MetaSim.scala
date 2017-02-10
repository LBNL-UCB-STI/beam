package beam.metasim.playground.sid.sim

import akka.actor.ActorSystem
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
class MetaSim @Inject()(actorSystem: ActorSystem,
                        eventsManager: EventsManager,
                        scenario:Scenario,
                        travelTime: TravelTime) extends Mobsim with StartupListener{

  override def run(): Unit = {

  }

  override def notifyStartup(event: StartupEvent): Unit = {

  }
}
