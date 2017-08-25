package beam.router.r5

import akka.actor.{Actor, ActorLogging, Props}
import beam.sim.BeamServices
import org.matsim.core.trafficmonitoring.TravelTimeCalculator

/**
  * Created by salma_000 on 8/25/2017.
  */
class TransportNetworkWorker(beamServices: BeamServices) extends Actor with ActorLogging {
  var services: BeamServices = beamServices

  override def receive: Receive = {
    case calc: TravelTimeCalculator =>
      log.info("Received TravelTimeCalculator")
      R5RoutingWorker.updateTimes(calc)
    case msg => {
      log.info(s"Received message[$msg] received by UpdateTransportNetwork Actor.")
      if (msg.equals("REPLACE_NETWORK")) {
        R5RoutingWorker.replaceNetwork
        System.out.println("Router Actor Path " + akka.serialization.Serialization.serializedActorPath(self))
        sender() ! "NETWORK_REPLACEMENT_DONE"
      }
      else
        log.info(s"Unknown message[$msg] received by UpdateTransportNetwork Actor.")
    }
  }
}
object TransportNetworkWorker {

  def getUpdateTransportNetworkProps(beamServices: BeamServices) = Props(classOf[TransportNetworkWorker], beamServices)

}