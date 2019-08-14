package beam.router.r5

import java.nio.file.Files.exists
import java.nio.file.Paths

import beam.sim.config.BeamConfig
import com.conveyal.r5.kryo.KryoNetworkSerializer
import com.conveyal.r5.transit.{TransportNetwork, TripSchedule}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.network.{Network, NetworkWriter}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader

import scala.collection.JavaConverters._

trait NetworkCoordinator extends LazyLogging {

  val beamConfig: BeamConfig

  var transportNetwork: TransportNetwork

  var network: Network

  protected def preprocessing(): Unit

  def init(): Unit = {
    preprocessing()
    loadNetwork()
    postProcessing()
  }

  protected def postProcessing(): Unit

  def loadNetwork(): Unit = {
    val GRAPH_FILE = "/network.dat"
    if (exists(Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE))) {
      logger.info(
        s"Initializing router by reading network from: ${Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE).toAbsolutePath}"
      )
      transportNetwork = KryoNetworkSerializer.read(Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE).toFile)
      if (exists(Paths.get(beamConfig.matsim.modules.network.inputNetworkFile))) {
        network = NetworkUtils.createNetwork()
        new MatsimNetworkReader(network)
          .readFile(beamConfig.matsim.modules.network.inputNetworkFile)
      } else {
        createPhyssimNetwork()
      }
    } else {
      logger.info(
        s"Initializing router by creating network from directory: ${Paths.get(beamConfig.beam.routing.r5.directory).toAbsolutePath}"
      )
      transportNetwork = TransportNetwork.fromDirectory(
        Paths.get(beamConfig.beam.routing.r5.directory).toFile,
        true,
        false
      )

      // FIXME HACK: It is not only creates PhysSim, but also fixes the speed and the length of `weird` links.
      // Please, fix me in the future
      createPhyssimNetwork()

      KryoNetworkSerializer.write(transportNetwork, Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE).toFile)

      // Needed because R5 closes DB on write
      transportNetwork = KryoNetworkSerializer.read(Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE).toFile)
    }
  }

  def createPhyssimNetwork(): Unit = {
    logger.info(s"Create the MATSim network from R5 network")
    val rmNetBuilder = new R5MnetBuilder(transportNetwork, beamConfig)
    rmNetBuilder.buildMNet()
    network = rmNetBuilder.getNetwork
    logger.info(s"MATSim network created")
    new NetworkWriter(network)
      .write(beamConfig.matsim.modules.network.inputNetworkFile)
    logger.info(s"MATSim network written")
  }

  def convertFrequenciesToTrips(): Unit = {
    transportNetwork.transitLayer.tripPatterns.asScala.foreach { tp =>
      if (tp.hasFrequencies) {
        val toAdd: Vector[TripSchedule] = tp.tripSchedules.asScala.toVector.flatMap { ts =>
          val tripStartTimes = ts.startTimes(0).until(ts.endTimes(0)).by(ts.headwaySeconds(0)).toVector
          tripStartTimes.zipWithIndex.map {
            case (startTime, ind) =>
              val tsNew = ts.clone()
              val newTripId = s"${tsNew.tripId}-$ind"
              val newArrivals = new Array[Int](ts.arrivals.length)
              val newDepartures = new Array[Int](ts.arrivals.length)
              for (i <- tsNew.arrivals.indices) {
                newArrivals(i) = tsNew.arrivals(i) + startTime
                newDepartures(i) = tsNew.departures(i) + startTime
              }
              tsNew.arrivals = newArrivals
              tsNew.departures = newDepartures
              tsNew.tripId = newTripId
              tsNew.frequencyEntryIds = null
              tsNew.headwaySeconds = null
              tsNew.startTimes = null
              tsNew.endTimes = null
              tsNew
          }
        }
        tp.tripSchedules.clear()
        toAdd.foreach(tp.tripSchedules.add(_))
        tp.hasFrequencies = false
        tp.hasSchedules = true
      }
    }
    transportNetwork.transitLayer.hasFrequencies = false
  }

}
