package beam.agentsim.infrastructure.parking

import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ZoneSearchTree
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.util.Random

/**
  * @author Dmitry Openkov
  */
object TazToLinkLevelParkingApp extends App with StrictLogging {

  def parseArgs(args: Array[String]) = {
    args
      .sliding(2, 2)
      .toList
      .collect {
        case Array("--taz-parking", filePath: String) => ("taz-parking", filePath)
        case Array("--network", filePath: String)     => ("network", filePath)
        case Array("--taz-centers", filePath: String) => ("taz-centers", filePath)
        case Array("--out", filePath: String)         => ("out", filePath)
        case arg @ _ =>
          throw new IllegalArgumentException(arg.mkString(" "))
      }
      .toMap
  }

  private val argsMap = parseArgs(args)

  if (argsMap.size != 4) {
    println(
      "Usage: --taz-parking beam.sim.test/input/beamville/parking/taz-parking.csv" +
      " --network beam.sim.test/input/beamville/r5/physsim-network.xml" +
      " --taz-centers beam.sim.test/input/beamville/taz-centers.csv --out beam.sim.test/input/beamville/parking/link-parking.csv"
    )
    System.exit(1)
  }
  logger.info("args = {}", argsMap)

  private val tazMap = TAZTreeMap.getTazTreeMap(argsMap("taz-centers"))

  private val network = {
    val network = NetworkUtils.createNetwork()
    new MatsimNetworkReader(network).readFile(argsMap("network"))
    network
  }

  private val (parkingZones: Map[Id[ParkingZoneId], ParkingZone[TAZ]], _: ZoneSearchTree[TAZ]) =
    ParkingZoneFileUtils.fromFile[TAZ](argsMap("taz-parking"), new Random(), None, None)

  private val linkToTaz = LinkLevelOperations.getLinkToTazMapping(network, tazMap)

  logger.info(s"Number of links in the network: ${linkToTaz.size}")

  private val linkQuadTree: QuadTree[Link] =
    LinkLevelOperations.getLinkTreeMap(network.getLinks.values().asScala.toSeq)

  private val zoneArrayLink: Map[Id[ParkingZoneId], ParkingZone[Link]] =
    ParkingZoneFileUtils.generateLinkParkingOutOfTazParking(parkingZones, linkQuadTree, linkToTaz, tazMap)

  private val zoneSearchTreeLink = zoneArrayLink.values
    .groupBy(_.geoId)
    .mapValues { zones =>
      zones
        .groupBy(zone => zone.parkingType)
        .mapValues(zonesByType => zonesByType.map(_.parkingZoneId).toVector)
    }

  logger.info("Generated {} zones", zoneArrayLink.size)
  logger.info("with {} parking stalls", zoneArrayLink.map(_._2.stallsAvailable.toLong).sum)
  ParkingZoneFileUtils.writeParkingZoneFile(zoneSearchTreeLink, zoneArrayLink, argsMap("out"))

}
