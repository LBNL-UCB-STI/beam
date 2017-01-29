package beam.metasim.playground.sid.sim

import akka.actor.{ActorSystem, Props}

/**
  * Beam actor simulation Application class
  *
  * Created by sfeygin on 1/28/17.
  */
object BeamActorSimulation extends App{
  val _system = ActorSystem("BeamActorSimulation")
  val scheduler = _system.actorOf(Props[ActorScheduler])
}


