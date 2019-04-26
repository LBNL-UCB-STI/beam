package beam.router.r5

import java.nio.file.Files.exists
import java.nio.file.Paths

import beam.sim.config.BeamConfig
import com.conveyal.r5.transit.{TransportNetwork, TripSchedule}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.network.{Network, NetworkWriter}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader

import scala.collection.JavaConverters._
import scala.collection.mutable

trait NetworkCoordinator extends LazyLogging {

  val beamConfig: BeamConfig

  var transportNetwork: TransportNetwork

  var network: Network

  val tripFleetSizeMap: mutable.HashMap[String, Integer] = mutable.HashMap.empty[String, Integer]

  protected def preprocessing(): Unit

  def init(): Unit = {
    tripFleetSizeMap.clear()
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
      transportNetwork = TransportNetwork.read(Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE).toFile)
      if (exists(Paths.get(beamConfig.matsim.modules.network.inputNetworkFile))) {
        network = NetworkUtils.createNetwork()
        new MatsimNetworkReader(network)
          .readFile(beamConfig.matsim.modules.network.inputNetworkFile)
      } else {
        createPhyssimNetwork()
      }
    } else { // Need to create the unpruned and pruned networks from directory
      logger.info(
        s"Initializing router by creating network from directory: ${Paths.get(beamConfig.beam.routing.r5.directory).toAbsolutePath}"
      )
      transportNetwork = TransportNetwork.fromDirectory(
        Paths.get(beamConfig.beam.routing.r5.directory).toFile,
        true,
        false
      ) // Uses the new signature Andrew created

      // FIXME HACK: It is not only creates PhysSim, but also fixes the speed and the length of `weird` links.
      // Please, fix me in the future
      createPhyssimNetwork()

      transportNetwork.write(Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE).toFile)
      transportNetwork = TransportNetwork.read(
        Paths.get(beamConfig.beam.routing.r5.directory, GRAPH_FILE).toFile
      ) // Needed because R5 closes DB on write
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
    estimateInUseFleet()
  }

  def estimateInUseFleet(): Unit = {
    val startStopsByTime: mutable.PriorityQueue[IdAndTime] =
      mutable.PriorityQueue[IdAndTime]()(Ordering.by(IdAndTimeOrder))
    val tripVehiclesEnRoute = mutable.HashMap.empty[String, mutable.Set[String]]
    transportNetwork.transitLayer.tripPatterns.asScala.foreach { tp =>
      if (tp.hasSchedules) {
        tp.tripSchedules.asScala.toVector foreach { ts =>
          val firstArrival: Int = ts.arrivals(0)
          val lastDeparture: Int = ts.departures(ts.getNStops - 1)
          startStopsByTime.enqueue(IdAndTime(firstArrival, ts.tripId, tp.routeId))
          startStopsByTime.enqueue(IdAndTime(lastDeparture, ts.tripId, tp.routeId))
        }
      }
      tripFleetSizeMap.put(tp.routeId, 0)
    }
    while (startStopsByTime.iterator.hasNext) {
      val nextId = startStopsByTime.dequeue()
      if (!tripVehiclesEnRoute.contains(nextId.route)) {
        tripVehiclesEnRoute.put(nextId.route, mutable.Set())
      }
      val theSet = tripVehiclesEnRoute(nextId.route)
      if (theSet.contains(nextId.id)) {
        tripFleetSizeMap.put(nextId.route, Math.max(theSet.size, tripFleetSizeMap(nextId.route)))
        theSet.remove(nextId.id)
      } else {
        theSet.add(nextId.id)
      }
    }
  }
  case class IdAndTime(time: Int, id: String, route: String)
  def IdAndTimeOrder(d: IdAndTime) = -d.time

}
