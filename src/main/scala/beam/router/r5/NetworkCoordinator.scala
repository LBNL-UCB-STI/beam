package beam.router.r5

import java.io.File
import java.nio.file.Files.exists
import java.nio.file.Paths

import akka.actor.{Actor, ActorLogging, Props}
import beam.router.BeamRouter.{InitializeRouter, RouterInitialized, UpdateTravelTime}
import beam.router.gtfs.FareCalculator
import beam.router.r5.NetworkCoordinator._
import beam.sim.BeamServices
import beam.utils.Objects.deepCopy
import beam.utils.reflection.RefectionUtils
import com.conveyal.gtfs.model.Stop
import com.conveyal.r5.streets.StreetLayer
import com.conveyal.r5.transit.TransportNetwork
import com.conveyal.r5.util.ExpandingMMFBytez
import org.matsim.api.core.v01.Id
import org.matsim.core.trafficmonitoring.TravelTimeCalculator

/**
  * Created by salma_000 on 8/25/2017.
  */
class NetworkCoordinator(val beamServices: BeamServices) extends Actor with ActorLogging {

  override def receive: Receive = {
    case InitializeRouter =>
      log.info("Initializing Router")
      init
      context.parent ! RouterInitialized
      sender() ! RouterInitialized
    case networkUpdateRequest: UpdateTravelTime =>
      log.info("Received UpdateTravelTime")
      updateTimes(networkUpdateRequest.travelTimeCalculator)
      replaceNetwork

    case msg => log.info(s"Unknown message[$msg] received by NetworkCoordinator Actor.")
  }

  def init: Unit = {
    loadNetwork
    FareCalculator.fromDirectory(Paths.get(beamServices.beamConfig.beam.routing.r5.directory))
    overrideR5EdgeSearchRadius(2000)
  }

  def loadNetwork = {
    val networkDir = beamServices.beamConfig.beam.routing.r5.directory
    val networkDirPath = Paths.get(networkDir)
    if (!exists(networkDirPath)) {
      Paths.get(networkDir).toFile.mkdir()
    }
    val networkFilePath = Paths.get(networkDir, GRAPH_FILE)
    val networkFile: File = networkFilePath.toFile
    if (exists(networkFilePath)) {
      log.debug(s"Initializing router by reading network from: ${networkFilePath.toAbsolutePath}")
      transportNetwork = TransportNetwork.read(networkFile)
    } else {
      log.debug(s"Network file [${networkFilePath.toAbsolutePath}] not found. ")
      log.debug(s"Initializing router by creating network from: ${networkDirPath.toAbsolutePath}")
      transportNetwork = TransportNetwork.fromDirectory(networkDirPath.toFile)

//      ExpandingMMFBytez.writeObjectToFile(new File(s"${networkDirPath}/stops.dat"), transportNetwork.transitLayer.stopForIndex)
      transportNetwork.write(networkFile)
      transportNetwork = TransportNetwork.read(networkFile) // Needed because R5 closes DB on write
//      transportNetwork.transitLayer.stopForIndex = ExpandingMMFBytez.readObjectFromFile(new File(s"${networkDirPath}/stops.dat"))
    }
    beamPathBuilder = new BeamPathBuilder(transportNetwork = transportNetwork, beamServices)
    val envelopeInUTM = beamServices.geo.wgs2Utm(transportNetwork.streetLayer.envelope)
    beamServices.geo.utmbbox.maxX = envelopeInUTM.getMaxX + beamServices.beamConfig.beam.spatial.boundingBoxBuffer
    beamServices.geo.utmbbox.maxY = envelopeInUTM.getMaxY + beamServices.beamConfig.beam.spatial.boundingBoxBuffer
    beamServices.geo.utmbbox.minX = envelopeInUTM.getMinX - beamServices.beamConfig.beam.spatial.boundingBoxBuffer
    beamServices.geo.utmbbox.minY = envelopeInUTM.getMinY - beamServices.beamConfig.beam.spatial.boundingBoxBuffer
  }

  def replaceNetwork = {
    if (transportNetwork != copiedNetwork)
      transportNetwork = copiedNetwork
    else {
      /** To-do: allow switching if we just say warning or we should stop system to allow here
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
      if (linkId > 0) {
        val avgTime = getAverageTime(linkId, travelTimeCalculator)
        val avgTimeShort = (avgTime * 100).asInstanceOf[Short]
        edge.setSpeed(avgTimeShort)
      }
    })
  }

  def getAverageTime(linkId: Long, travelTimeCalculator: TravelTimeCalculator) = {
    val limit = 86400
    val step = 60
    val totalIterations = limit / step
    val link: Id[org.matsim.api.core.v01.network.Link] = Id.createLinkId(linkId)

    val totalTime = if (link != null) (0 until limit by step).map(i => travelTimeCalculator.getLinkTravelTime(link, i.toDouble)).sum else 0.0
    val avgTime = (totalTime / totalIterations)
    avgTime.toShort
  }


  private def overrideR5EdgeSearchRadius(newRadius: Double): Unit =
    RefectionUtils.setFinalField(classOf[StreetLayer], "LINK_RADIUS_METERS", newRadius)
}

object NetworkCoordinator {
  val GRAPH_FILE = "/network.dat"

  var transportNetwork: TransportNetwork = _
  var copiedNetwork: TransportNetwork = _
  var linkMap: Map[Int, Long] = Map()
  var beamPathBuilder: BeamPathBuilder = _

  def getOsmId(edgeIndex: Int): Long = {
    linkMap.getOrElse(edgeIndex, {
      val osmLinkId = transportNetwork.streetLayer.edgeStore.getCursor(edgeIndex).getOSMID
      linkMap += edgeIndex -> osmLinkId
      osmLinkId
    })
  }

  def props(beamServices: BeamServices) = Props(classOf[NetworkCoordinator], beamServices)
}