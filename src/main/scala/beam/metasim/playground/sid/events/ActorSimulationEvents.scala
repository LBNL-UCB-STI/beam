package beam.metasim.playground.sid.events

import org.matsim.core.controler.events.{ControlerEvent, StartupEvent}

/**
  * Created by sfeygin on 1/28/17.
  */
object ActorSimulationEvents {
  sealed trait Event
  trait ActorSimEvent extends Event
  trait MATSimEvent[E<: ControlerEvent] extends Event


  case object StartSimulation extends ActorSimEvent
  case object Await extends ActorSimEvent
  case object Start extends ActorSimEvent
  case object FinishLeg extends ActorSimEvent

  case object StartActorSim extends MATSimEvent[StartupEvent]

}
