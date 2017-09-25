package beam.router.r5

import java.io.File
import java.nio.file.Files.exists
import java.nio.file.Paths

import akka.actor.{Actor, ActorLogging, Props}
import beam.router.BeamRouter.{InitializeRouter, RouterInitialized, UpdateTravelTime}
import beam.router.gtfs.FareCalculator
import beam.router.r5.NetworkCoordinator.{copiedNetwork, _}
import beam.sim.BeamServices
import beam.utils.Objects.deepCopy
import beam.utils.reflection.ReflectionUtils
import com.conveyal.r5.streets.StreetLayer
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
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
  }

  def loadNetwork = {
    val networkDir = beamServices.beamConfig.beam.routing.r5.directory
    val networkDirPath = Paths.get(networkDir)
    if (!exists(networkDirPath)) {
      Paths.get(networkDir).toFile.mkdir()
    }

    val unprunedNetworkFilePath = Paths.get(networkDir, UNPRUNED_GRAPH_FILE)  // The first R5 network, created w/out island pruning
    val unprunedNetworkFile: File = unprunedNetworkFilePath.toFile
    val prunedNetworkFilePath = Paths.get(networkDir, PRUNED_GRAPH_FILE)  // The final R5 network that matches the cleaned (pruned) MATSim network
    val prunedNetworkFile: File = prunedNetworkFilePath.toFile
    if (exists(prunedNetworkFilePath)) {
      log.debug(s"Initializing router by reading network from: ${prunedNetworkFilePath.toAbsolutePath}")
      transportNetwork = TransportNetwork.read(prunedNetworkFile)
    } else {  // Need to create the unpruned and pruned networks from directory
      log.debug(s"Network file [${prunedNetworkFilePath.toAbsolutePath}] not found. ")
      log.debug(s"Initializing router by creating unpruned network from: ${networkDirPath.toAbsolutePath}")
      unprunedTransportNetwork = TransportNetwork.fromDirectory(networkDirPath.toFile, false, false) // Uses the new signature Andrew created
      unprunedTransportNetwork.write(unprunedNetworkFile)

      ////
      // Run R5MnetBuilder to create the pruned R5 network and matching MATSim network
      ////
      log.debug(s"Create the cleaned MATSim network from unpuned R5 network")
      val osmFilePath = beamServices.beamConfig.beam.routing.r5.osmFile
      // TODO - implement option to use EdgeFlags (AAC 17/09/19)
      rmNetBuilder = new R5MnetBuilder(unprunedNetworkFile.toString, beamServices.beamConfig.beam.routing.r5.osmMapdbFile)
      rmNetBuilder.buildMNet()
      rmNetBuilder.cleanMnet()
      log.debug(s"Pruned MATSim network created and written")
      rmNetBuilder.writeMNet(beamServices.beamConfig.matsim.modules.network.inputNetworkFile)
      log.debug(s"Prune the R5 network")
      rmNetBuilder.pruneR5()
      transportNetwork = rmNetBuilder.getR5Network

      // Now network has been pruned
      transportNetwork.write(prunedNetworkFile)
      //beamServices.beamConfig.matsim.modules.network.inputNetworkFile
//      beamServices.reloadMATSimNetwork = true
      transportNetwork = TransportNetwork.read(prunedNetworkFile) // Needed because R5 closes DB on write
    }
    //
    transportNetwork.rebuildTransientIndexes()
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
        val avgTime = getAverageTime(Id.createLinkId(linkId), travelTimeCalculator)
        val avgTimeShort = (avgTime * 100).asInstanceOf[Short]
        edge.setSpeed(avgTimeShort)
      }
    })
  }

//    val edgeStore=copiedNetwork.streetLayer.edgeStore;

  def getAverageTime(linkId: Id[Link], travelTimeCalculator: TravelTimeCalculator) = {
    val limit = 86400
    val step = 60
    val totalIterations = limit / step

    val totalTime = if (linkId != null) (0 until limit by step).map(i => travelTimeCalculator.getLinkTravelTime(linkId, i.toDouble)).sum else 0.0
    val avgTime = (totalTime / totalIterations)
    avgTime
  }


  private def overrideR5EdgeSearchRadius(newRadius: Double): Unit =
    ReflectionUtils.setFinalField(classOf[StreetLayer], "LINK_RADIUS_METERS", newRadius)
}

object NetworkCoordinator {
  val PRUNED_GRAPH_FILE = "/pruned_network.dat"
  val UNPRUNED_GRAPH_FILE = "/unpruned_network.dat"

  var transportNetwork: TransportNetwork = _
  var unprunedTransportNetwork: TransportNetwork = _
  var copiedNetwork: TransportNetwork = _
  var rmNetBuilder: R5MnetBuilder = _
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