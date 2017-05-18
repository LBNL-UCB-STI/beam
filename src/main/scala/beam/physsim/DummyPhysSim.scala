package beam.physsim

import akka.actor.Props
import beam.agentsim.sim.AgentsimServices
import beam.physsim.PhysSim.{InitializePhysSim, PhysSimInitialized}

/**
  * BEAM
  */
class DummyPhysSim (agentsimServices: AgentsimServices) extends PhysSim {

  override def receive: Receive = {
    case InitializePhysSim =>
      log.info("Initializing DummyPhysSim")
      sender() ! PhysSimInitialized
    case msg =>
      log.info(s"Unknown message received by DummyPhysSim: $msg")
  }
}

object DummyPhysSim {
  def props(agentsimServices: AgentsimServices) = Props(classOf[DummyPhysSim], agentsimServices)
}
