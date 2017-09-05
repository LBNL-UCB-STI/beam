package beam.router.r5

import java.util

import akka.actor.{Actor, ActorLogging, Props}
import beam.router.r5.NetworkCoordinator.UpdateTravelTime
import beam.utils.Objects
import com.conveyal.r5.transit.TransportNetwork
import org.apache.log4j.Logger
import org.matsim.api.core.v01.Id
import org.matsim.core.trafficmonitoring.TravelTimeCalculator

/**
  * Created by salma_000 on 8/25/2017.
  */
class NetworkCoordinator extends Actor with ActorLogging {

  override def receive: Receive = {
    case networkUpdateRequest: UpdateTravelTime =>
      log.info("Received UpdateTravelTime")
      NetworkCoordinator.updateTimes(networkUpdateRequest.travelTimeCalculator)
      NetworkCoordinator.replaceNetwork

    case msg => {
        log.info(s"Unknown message[$msg] received by UpdateTransportNetwork Actor.")
    }
  }
}

object NetworkCoordinator {
  trait UpdateNetwork
  case class UpdateTravelTime(travelTimeCalculator: TravelTimeCalculator) extends UpdateNetwork
  var  transportNetwork: TransportNetwork = _

  var linkMap: util.Map[Int, Long] = new util.HashMap[Int, Long]()
  var copiedNetwork:TransportNetwork  = _
  val logger = Logger.getLogger("R5RoutingWorker")
  def replaceNetwork = {
    if(transportNetwork != copiedNetwork)
      transportNetwork = copiedNetwork
    else{
      /**To-do: allow switching if we just say warning or we should stop system to allow here
        * Log warning to stop or error to warning
        */
      /**
        * This case is might happen as we are operating non thread safe environment it might happen that
        * transportNetwork variable set by transportNetwork actor not possible visible to if it is not a
        * critical error as worker will be continue working on obsolete state
        */
      logger.warn("Router worker continue execution on obsolete state")
      logger.error("Router worker continue working on obsolete state")
      logger.info("Router worker continue execution on obsolete state")
    }
  }

  def updateTimes(travelTimeCalculator: TravelTimeCalculator) = {
    copiedNetwork = Objects.deepCopy(transportNetwork).asInstanceOf[TransportNetwork]
    linkMap.keySet().forEach((key) => {
      val edge = copiedNetwork.streetLayer.edgeStore.getCursor(key)
      val linkId = edge.getOSMID
      if(linkId > 0) {
        val avgTime = getAverageTime(linkId, travelTimeCalculator)
        val avgTime100 = (avgTime * 100).asInstanceOf[Short]
        edge.setSpeed(avgTime100)
      }
    })
  }

  def getAverageTime(linkId: Long, travelTimeCalculator: TravelTimeCalculator) = {
    var totalTime = 0.0
    val limit = 86400
    val step = 60
    val totalIterations = limit/step
    val link: Id[org.matsim.api.core.v01.network.Link] = Id.createLinkId(linkId)

    if(link != null) {
      for (i <- 0 until 86400 by 60) {
        totalTime = totalTime + travelTimeCalculator.getLinkTravelTime(link, i.toDouble)
      }
    }

    val avgTime = (totalTime/totalIterations)
    avgTime.toShort
  }

  def getOsmId(edgeIndex: Int): Long = {
    if(linkMap.containsKey(edgeIndex)){
      linkMap.get(edgeIndex)
    }else {
      val osmLinkId = NetworkCoordinator.transportNetwork.streetLayer.edgeStore.getCursor(edgeIndex).getOSMID
      linkMap.put(edgeIndex, osmLinkId)
      osmLinkId
    }
  }
  def getNetworkCoordinatorProps = Props(classOf[NetworkCoordinator])

}