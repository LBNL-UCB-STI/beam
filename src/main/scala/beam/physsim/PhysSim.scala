package beam.physsim

import akka.actor.{Actor, Props}
import beam.agentsim.sim.AgentsimServices
import org.slf4j.{Logger, LoggerFactory}

trait PhysSim extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)
}

object PhysSim {
  case object InitializePhysSim

  case object PhysSimInitialized

}