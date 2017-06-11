package beam.router

import akka.actor.Actor
import Modes.BeamMode
import Modes.BeamMode._
import beam.agentsim.events.SpaceTime
import beam.sim.BeamServices
import org.matsim.api.core.v01.Coord
import org.slf4j.{Logger, LoggerFactory}

trait BeamRouter extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)
}

object BeamRouter{
}

