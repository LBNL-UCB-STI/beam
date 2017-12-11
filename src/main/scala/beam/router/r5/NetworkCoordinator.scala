package beam.router.r5

import java.nio.file.Files.exists
import java.nio.file.Paths
import java.util

import akka.actor.{Actor, ActorLogging, Props, Status}
import beam.router.r5.NetworkCoordinator._
import beam.sim.BeamServices
import beam.utils.reflection.ReflectionUtils
import com.conveyal.r5.streets.{EdgeStore, StreetLayer}
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.vehicles.Vehicles

class NetworkCoordinator(val transitVehicles: Vehicles, val beamServices: BeamServices) extends Actor with ActorLogging {

  loadNetwork

  // Propagate exceptions to sender
  // Default Akka behavior on an Exception in any Actor does _not_ involve sending _any_ reply.
  // This appears to be by design: A lot goes on in that case (reconfigurable restart strategy, logging, etc.),
  // but sending a reply to the sender of the message which _caused_ the exception must be done explicitly, e.g. like this:
  override def preRestart(reason:Throwable, message:Option[Any]) {
    super.preRestart(reason, message)
    sender() ! Status.Failure(reason)
  }
  // Status.Failure is a special message which causes the Future on the side of the sender to fail,
  // i.e. Await.result re-throws the Exception! In the case of this Actor here, this is a good thing,
  // see the place in BeamSim where the InitTransit message is sent: We want the failure to happen _there_, not _here_.
  //
  // Something like this must be done in every place in the code where one Actor may wait forever for a specific answer.
  // Otherwise, the result is that the default failure mode of our software is to hang, not crash.
  // If we want this particular behavior in several Actors, we can make a trait of it.
  //
  // https://stackoverflow.com/questions/29794454/resolving-akka-futures-from-ask-in-the-event-of-a-failure

  override def receive: Receive = {
    case msg => log.info(s"Unknown message[$msg] received by NetworkCoordinator Actor.")
  }

  def loadNetwork = {
    if (exists(Paths.get(beamServices.beamConfig.beam.routing.r5.directory, GRAPH_FILE))) {
      log.debug(s"Initializing router by reading network from: ${Paths.get(beamServices.beamConfig.beam.routing.r5.directory, GRAPH_FILE).toAbsolutePath}")
      transportNetwork = TransportNetwork.read(Paths.get(beamServices.beamConfig.beam.routing.r5.directory, GRAPH_FILE).toFile)
    } else {  // Need to create the unpruned and pruned networks from directory
      log.debug(s"Initializing router by creating network from directory: ${Paths.get(beamServices.beamConfig.beam.routing.r5.directory).toAbsolutePath}")
      transportNetwork = TransportNetwork.fromDirectory(Paths.get(beamServices.beamConfig.beam.routing.r5.directory).toFile, true, false) // Uses the new signature Andrew created
      transportNetwork.write(Paths.get(beamServices.beamConfig.beam.routing.r5.directory, GRAPH_FILE).toFile)
      transportNetwork = TransportNetwork.read(Paths.get(beamServices.beamConfig.beam.routing.r5.directory, GRAPH_FILE).toFile) // Needed because R5 closes DB on write

      log.debug(s"Create the MATSim network from R5 network")
      val rmNetBuilder = new R5MnetBuilder(transportNetwork, beamServices.beamConfig.beam.routing.r5.osmMapdbFile, util.EnumSet.of(EdgeStore.EdgeFlag.ALLOWS_CAR))
      rmNetBuilder.buildMNet()
      log.debug(s"MATSim network created and written")
      rmNetBuilder.writeMNet(beamServices.beamConfig.matsim.modules.network.inputNetworkFile)
    }
    //
    beamPathBuilder = new BeamPathBuilder(transportNetwork = transportNetwork, beamServices)
    val envelopeInUTM = beamServices.geo.wgs2Utm(transportNetwork.streetLayer.envelope)
    beamServices.geo.utmbbox.maxX = envelopeInUTM.getMaxX + beamServices.beamConfig.beam.spatial.boundingBoxBuffer
    beamServices.geo.utmbbox.maxY = envelopeInUTM.getMaxY + beamServices.beamConfig.beam.spatial.boundingBoxBuffer
    beamServices.geo.utmbbox.minX = envelopeInUTM.getMinX - beamServices.beamConfig.beam.spatial.boundingBoxBuffer
    beamServices.geo.utmbbox.minY = envelopeInUTM.getMinY - beamServices.beamConfig.beam.spatial.boundingBoxBuffer
  }

  private def overrideR5EdgeSearchRadius(newRadius: Double): Unit =
    ReflectionUtils.setFinalField(classOf[StreetLayer], "LINK_RADIUS_METERS", newRadius)

}

object NetworkCoordinator {
  val GRAPH_FILE = "/network.dat"

  var transportNetwork: TransportNetwork = _
  var linkMap: Map[Int, Long] = Map()
  var beamPathBuilder: BeamPathBuilder = _

  def getOsmId(edgeIndex: Int): Long = {
    linkMap.getOrElse(edgeIndex, {
      val osmLinkId = transportNetwork.streetLayer.edgeStore.getCursor(edgeIndex).getOSMID
      linkMap += edgeIndex -> osmLinkId
      osmLinkId
    })
  }

  def props(transitVehicles: Vehicles, beamServices: BeamServices) = Props(classOf[NetworkCoordinator], transitVehicles, beamServices)
}