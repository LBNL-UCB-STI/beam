package beam.metasim.playground.sid.sim

import akka.actor.{ActorRef, ActorSystem, Props}
import com.google.inject.Inject
import org.matsim.core.controler.MatsimServices
import org.matsim.core.mobsim.framework.Mobsim

/**
  * Beam actor simulation Application class
  *
  * Created by sfeygin on 1/28/17.
  */
object BeamActorSimulation {
  val _system = ActorSystem("BeamActorSimulation")
  val scheduler: ActorRef = _system.actorOf(Props[ActorScheduler])

}

class BeamActorSimulation(matsimServices: MatsimServices) extends Mobsim {
  override def run(): Unit = {

  }
}


