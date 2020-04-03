package beam.router.r5

import java.io.File
import java.nio.file.Files.exists
import java.nio.file.{Files, Paths}

import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Physsim
import beam.utils.BeamVehicleUtils
import com.conveyal.r5.kryo.KryoNetworkSerializer
import com.conveyal.r5.streets.EdgeStore
import com.conveyal.r5.transit.{TransportNetwork, TripSchedule}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.{Link, Network, NetworkWriter}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

case class LinkParam(linkId: Int, capacity: Option[Double], freeSpeed: Option[Double], length: Option[Double]) {

  def overwriteFor(link: Link, cursor: EdgeStore#Edge): Unit = {
    capacity.foreach(value => link.setCapacity(value))
    freeSpeed.foreach { value =>
      // !!! The speed for R5 is rounded (m/s * 100) (2 decimal places)
      cursor.setSpeed((value * 100).toShort)
      link.setFreespeed(value)
    }
    length.foreach { value =>
      // Provided length is in meters, convert them to millimeters
      cursor.setLengthMm((value * 1000).toInt)
      link.setLength(value)
    }
  }
}

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

  def overwriteLinkParams(
    overwriteLinkParamMap: scala.collection.Map[Int, LinkParam],
    transportNetwork: TransportNetwork,
    network: Network
  ): Unit = {
    overwriteLinkParamMap.foreach {
      case (linkId, param) =>
        val link = network.getLinks.get(Id.createLinkId(linkId))
        require(link != null, s"Could not find link with id $linkId")
        val edge = transportNetwork.streetLayer.edgeStore.getCursor(linkId)
        // Overwrite params
        param.overwriteFor(link, edge)
    }
  }

  def createPhyssimNetwork(): Unit = {
    logger.info(s"Create the MATSim network from R5 network")
    val rmNetBuilder = new R5MnetBuilder(
      transportNetwork,
      beamConfig,
      NetworkCoordinator.createHighwaySetting(beamConfig.beam.physsim.network.overwriteRoadTypeProperties)
    )
    rmNetBuilder.buildMNet()
    network = rmNetBuilder.getNetwork

    // Overwrite link stats if needed
    overwriteLinkParams(getOverwriteLinkParam(beamConfig), transportNetwork, network)

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

  private def getOverwriteLinkParam(beamConfig: BeamConfig): scala.collection.Map[Int, LinkParam] = {
    val path = beamConfig.beam.physsim.overwriteLinkParamPath
    val filePath = new File(path).toPath
    if (path.nonEmpty && Files.exists(filePath) && Files.isRegularFile(filePath)) {
      try {
        BeamVehicleUtils.readCsvFileByLine(path, scala.collection.mutable.HashMap[Int, LinkParam]()) {
          case (line: java.util.Map[String, String], z) =>
            val linkId = line.get("link_id").toInt
            val capacity = Option(line.get("capacity")).map(_.toDouble)
            val freeSpeed = Option(line.get("free_speed")).map(_.toDouble)
            val length = Option(line.get("length")).map(_.toDouble)
            val lp = LinkParam(linkId, capacity, freeSpeed, length)
            z += ((linkId, lp))
        }
      } catch {
        case NonFatal(ex) =>
          logger.error(s"Could not load link's params from ${path}: ${ex.getMessage}", ex)
          Map.empty
      }
    } else {
      Map.empty
    }
  }

}

object NetworkCoordinator {
  private[r5] def createHighwaySetting(highwayType: Physsim.Network.OverwriteRoadTypeProperties): HighwaySetting = {
    if (!highwayType.enabled) {
      HighwaySetting.empty()
    } else {
      val speeds = getSpeeds(highwayType)
      val capacities = getCapacities(highwayType)
      val lanes = getLanes(highwayType)
      new HighwaySetting(speeds, capacities, lanes)
    }
  }

  private[r5] def getSpeeds(
    highwayType: Physsim.Network.OverwriteRoadTypeProperties
  ): java.util.HashMap[HighwayType, java.lang.Double] = {
    val map = new java.util.HashMap[HighwayType, java.lang.Double]()
    highwayType.motorway.speed.foreach(speed => map.put(HighwayType.Motorway, speed))
    highwayType.motorwayLink.speed.foreach(speed => map.put(HighwayType.MotorwayLink, speed))

    highwayType.primary.speed.foreach(speed => map.put(HighwayType.Primary, speed))
    highwayType.primaryLink.speed.foreach(speed => map.put(HighwayType.PrimaryLink, speed))

    highwayType.trunk.speed.foreach(speed => map.put(HighwayType.Trunk, speed))
    highwayType.trunkLink.speed.foreach(speed => map.put(HighwayType.TrunkLink, speed))

    highwayType.secondary.speed.foreach(speed => map.put(HighwayType.Secondary, speed))
    highwayType.secondaryLink.speed.foreach(speed => map.put(HighwayType.SecondaryLink, speed))

    highwayType.tertiary.speed.foreach(speed => map.put(HighwayType.Tertiary, speed))
    highwayType.tertiaryLink.speed.foreach(speed => map.put(HighwayType.TertiaryLink, speed))

    highwayType.minor.speed.foreach(speed => map.put(HighwayType.Minor, speed))
    highwayType.residential.speed.foreach(speed => map.put(HighwayType.Residential, speed))
    highwayType.livingStreet.speed.foreach(speed => map.put(HighwayType.LivingStreet, speed))
    highwayType.unclassified.speed.foreach(speed => map.put(HighwayType.Unclassified, speed))
    map
  }

  private[r5] def getCapacities(
    highwayType: Physsim.Network.OverwriteRoadTypeProperties
  ): java.util.HashMap[HighwayType, java.lang.Integer] = {
    val map = new java.util.HashMap[HighwayType, java.lang.Integer]()
    highwayType.motorway.capacity.foreach(capacity => map.put(HighwayType.Motorway, capacity))
    highwayType.motorwayLink.capacity.foreach(capacity => map.put(HighwayType.MotorwayLink, capacity))

    highwayType.primary.capacity.foreach(capacity => map.put(HighwayType.Primary, capacity))
    highwayType.primaryLink.capacity.foreach(capacity => map.put(HighwayType.PrimaryLink, capacity))

    highwayType.trunk.capacity.foreach(capacity => map.put(HighwayType.Trunk, capacity))
    highwayType.trunkLink.capacity.foreach(capacity => map.put(HighwayType.TrunkLink, capacity))

    highwayType.secondary.capacity.foreach(capacity => map.put(HighwayType.Secondary, capacity))
    highwayType.secondaryLink.capacity.foreach(capacity => map.put(HighwayType.SecondaryLink, capacity))

    highwayType.tertiary.capacity.foreach(capacity => map.put(HighwayType.Tertiary, capacity))
    highwayType.tertiaryLink.capacity.foreach(capacity => map.put(HighwayType.TertiaryLink, capacity))

    highwayType.minor.capacity.foreach(capacity => map.put(HighwayType.Minor, capacity))
    highwayType.residential.capacity.foreach(capacity => map.put(HighwayType.Residential, capacity))
    highwayType.livingStreet.capacity.foreach(capacity => map.put(HighwayType.LivingStreet, capacity))
    highwayType.unclassified.capacity.foreach(capacity => map.put(HighwayType.Unclassified, capacity))
    map
  }

  private[r5] def getLanes(
    highwayType: Physsim.Network.OverwriteRoadTypeProperties
  ): java.util.HashMap[HighwayType, java.lang.Integer] = {
    val map = new java.util.HashMap[HighwayType, java.lang.Integer]()
    highwayType.motorway.lanes.foreach(lanes => map.put(HighwayType.Motorway, lanes))
    highwayType.motorwayLink.lanes.foreach(lanes => map.put(HighwayType.MotorwayLink, lanes))

    highwayType.primary.lanes.foreach(lanes => map.put(HighwayType.Primary, lanes))
    highwayType.primaryLink.lanes.foreach(lanes => map.put(HighwayType.PrimaryLink, lanes))

    highwayType.trunk.lanes.foreach(lanes => map.put(HighwayType.Trunk, lanes))
    highwayType.trunkLink.lanes.foreach(lanes => map.put(HighwayType.TrunkLink, lanes))

    highwayType.secondary.lanes.foreach(lanes => map.put(HighwayType.Secondary, lanes))
    highwayType.secondaryLink.lanes.foreach(lanes => map.put(HighwayType.SecondaryLink, lanes))

    highwayType.tertiary.lanes.foreach(lanes => map.put(HighwayType.Tertiary, lanes))
    highwayType.tertiaryLink.lanes.foreach(lanes => map.put(HighwayType.TertiaryLink, lanes))

    highwayType.minor.lanes.foreach(lanes => map.put(HighwayType.Minor, lanes))
    highwayType.residential.lanes.foreach(lanes => map.put(HighwayType.Residential, lanes))
    highwayType.livingStreet.lanes.foreach(lanes => map.put(HighwayType.LivingStreet, lanes))
    highwayType.unclassified.lanes.foreach(lanes => map.put(HighwayType.Unclassified, lanes))
    map
  }
}
