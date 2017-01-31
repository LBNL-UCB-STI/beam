package beam.metasim.playground.sid.events

import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.StartupEvent

/**
  * Created by sfeygin on 1/28/17.
  */
object ActorSimulationEvents {
  trait BeamActorSimEvent
  trait MATSimEvent{
    val event: Event
  }

  case object Start extends BeamActorSimEvent

//  case class StartActorSim(event: StartupEvent) extends MATSimEvent

}
