package beam.router.r5

import akka.actor.{Actor, ActorLogging, Props}
import beam.router.r5.NetworkCoordinator.{UpdateTravelTime, copiedNetwork, linkMap, transportNetwork}
import beam.sim.BeamServices
import beam.utils.Objects.deepCopy
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Id
import org.matsim.core.trafficmonitoring.TravelTimeCalculator

/**
  * Created by salma_000 on 8/25/2017.
  */
class NetworkCoordinator extends Actor with ActorLogging {

  override def receive: Receive = {
    case networkUpdateRequest: UpdateTravelTime =>
      log.info("Received UpdateTravelTime")
      updateTimes(networkUpdateRequest.travelTimeCalculator)
      replaceNetwork

    case msg => log.info(s"Unknown message[$msg] received by NetworkCoordinator Actor.")
  }

  def replaceNetwork = {
    if(transportNetwork != copiedNetwork)
      transportNetwork = copiedNetwork
    else {
      /**To-do: allow switching if we just say warning or we should stop system to allow here
        * Log warning to stop or error to warning
        */
      /**
        * This case is might happen as we are operating non thread safe environment it might happen that
        * transportNetwork variable set by transportNetwork actor not possible visible to if it is not a
        * critical error as worker will be continue working on obsolete state
        */
      log.warning("Router worker continue execution on obsolete state")
      log.error("Router worker continue working on obsolete state")
      log.info("Router worker continue execution on obsolete state")
    }
  }

  def updateTimes(travelTimeCalculator: TravelTimeCalculator) = {
    copiedNetwork = deepCopy(transportNetwork).asInstanceOf[TransportNetwork]
    linkMap.keys.foreach(key => {
      val edge = copiedNetwork.streetLayer.edgeStore.getCursor(key)
      val linkId = edge.getOSMID
      if(linkId > 0) {
        val avgTime = getAverageTime(linkId, travelTimeCalculator)
        val avgTimeShort = (avgTime * 100).asInstanceOf[Short]
        edge.setSpeed(avgTimeShort)
      }
    })
  }

  def getAverageTime(linkId: Long, travelTimeCalculator: TravelTimeCalculator) = {
    val limit = 86400
    val step = 60
    val totalIterations = limit/step
    val link: Id[org.matsim.api.core.v01.network.Link] = Id.createLinkId(linkId)

    val totalTime = if(link != null) (0 until limit by step).map(i => travelTimeCalculator.getLinkTravelTime(link, i.toDouble)).sum else 0.0
    val avgTime = (totalTime/totalIterations)
    avgTime.toShort
  }
}

object NetworkCoordinator {
  trait UpdateNetwork
  case class UpdateTravelTime(travelTimeCalculator: TravelTimeCalculator) extends UpdateNetwork

  val GRAPH_FILE = "/network.dat"

  var  transportNetwork: TransportNetwork = _
  var copiedNetwork:TransportNetwork  = _
  var linkMap: Map[Int, Long] = _

  def getOsmId(edgeIndex: Int): Long = {
    linkMap.getOrElse(edgeIndex, {
      val osmLinkId = transportNetwork.streetLayer.edgeStore.getCursor(edgeIndex).getOSMID
      linkMap += edgeIndex -> osmLinkId
      osmLinkId})
  }

  def props = Props(classOf[NetworkCoordinator])
}