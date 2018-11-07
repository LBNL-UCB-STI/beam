package beam.router.r5

import java.nio.file.Files.exists
import java.nio.file.Paths

import beam.sim.config.BeamConfig
import com.conveyal.r5.analyst.scenario.Scenario
import com.conveyal.r5.transit.{TransportNetwork, TripSchedule}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.network.{Network, NetworkWriter}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader

import scala.collection.JavaConverters._

class NetworkCoordinator(beamConfig: BeamConfig) extends LazyLogging {


  var transportNetwork: TransportNetwork = _
  var network: Network = _

  def loadNetwork(scenario:Scenario):Unit={

    loadNetwork()

    transportNetwork = scenario.applyToTransportNetwork(transportNetwork)
  }

  def loadNetwork(): Unit = {
    val GRAPH_FILE = "/network.dat"
    if (exists(Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE))) {
      logger.info(
        s"Initializing router by reading network from: ${Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE).toAbsolutePath}"
      )
      transportNetwork = TransportNetwork.read(Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE).toFile)
      network = NetworkUtils.createNetwork()
      new MatsimNetworkReader(network)
        .readFile(beamConfig.matsim.modules.network.inputNetworkFile)
    } else { // Need to create the unpruned and pruned networks from directory
      logger.info(
        s"Initializing router by creating network from directory: ${Paths.get(beamConfig.beam.routing.r5.directory).toAbsolutePath}"
      )
      transportNetwork = TransportNetwork.fromDirectory(
        Paths.get(beamConfig.beam.routing.r5.directory).toFile,
        true,
        false
      ) // Uses the new signature Andrew created
      transportNetwork.write(Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE).toFile)
      transportNetwork = TransportNetwork.read(
        Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE).toFile
      ) // Needed because R5 closes DB on write
      logger.info(s"Create the MATSim network from R5 network")
      val rmNetBuilder = new R5MnetBuilder(transportNetwork, beamConfig)
      rmNetBuilder.buildMNet()
      network = rmNetBuilder.getNetwork
      logger.info(s"MATSim network created")
      new NetworkWriter(network)
        .write(beamConfig.matsim.modules.network.inputNetworkFile)
      logger.info(s"MATSim network written")
    }
  }

  def convertFrequenciesToTrips() = {
    transportNetwork.transitLayer.tripPatterns.asScala.foreach { tp =>
      if (tp.hasFrequencies) {
        val toAdd: Vector[TripSchedule] = tp.tripSchedules.asScala.toVector.flatMap { ts =>
          val tripStartTimes = ts.startTimes(0).until(ts.endTimes(0)).by(ts.headwaySeconds(0)).toVector
          tripStartTimes.zipWithIndex.map { case (startTime, ind) =>
            val tsNew = ts.clone().asInstanceOf[TripSchedule]
            val newTripId = s"${tsNew.tripId}-$ind"
            val newArrivals = new Array[Int](ts.arrivals.size)
            val newDepartures = new Array[Int](ts.arrivals.size)
            for (i <- 0.until(tsNew.arrivals.length)) {
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
//            tsNew.serviceCode = 2
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
