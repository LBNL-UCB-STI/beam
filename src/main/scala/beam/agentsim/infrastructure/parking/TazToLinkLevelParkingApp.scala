package beam.agentsim.infrastructure.parking

import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ZoneSearchTree
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.network.Link
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader

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

  val argsMap = parseArgs(args)

  if (argsMap.size != 4) {
    println(
      "Usage: --taz-parking test/input/beamville/parking/taz-parking.csv" +
      " --network test/input/beamville/physsim-network.xml" +
      " --taz-centers test/input/beamville/taz-centers.csv --out test/input/beamville/parking/link-parking.csv"
    )
    System.exit(1)
  }
  logger.info("args = {}", argsMap)

  val tazMap = TAZTreeMap.getTazTreeMap(argsMap("taz-centers"))

  val network = {
    val network = NetworkUtils.createNetwork()
    new MatsimNetworkReader(network).readFile(argsMap("network"))
    network
  }

  val (parkingZones: Array[ParkingZone[TAZ]], zoneSearchTree: ZoneSearchTree[TAZ]) =
    ParkingZoneFileUtils.fromFile[TAZ](argsMap("taz-parking"), new Random())

  val linkToTaz = LinkLevelOperations.getLinkToTazMapping(network, tazMap)

  logger.info(s"Number of links in the network: ${linkToTaz.size}")

  val tazToLinks: Map[TAZ, List[Link]] = linkToTaz.groupBy(_._2).mapValues(_.keys.toList)

  val zonesLink: Iterable[ParkingZone[Link]] = tazToLinks.flatMap {
    case (taz, links) =>
      distributeParking(taz, links, parkingZones, zoneSearchTree)
  }

  val zoneArrayLink: Array[ParkingZone[Link]] = zonesLink
    .filter(_.maxStalls > 0)
    .zipWithIndex
    .map {
      case (zone, idx) =>
        new ParkingZone[Link](
          parkingZoneId = idx,
          geoId = zone.geoId,
          parkingType = zone.parkingType,
          stallsAvailable = zone.stallsAvailable,
          maxStalls = zone.maxStalls,
          reservedFor = zone.reservedFor,
          vehicleManager = zone.vehicleManager,
          chargingPointType = zone.chargingPointType,
          pricingModel = zone.pricingModel,
          timeRestrictions = zone.timeRestrictions,
          parkingZoneName = zone.parkingZoneName,
          landCostInUSDPerSqft = zone.landCostInUSDPerSqft,
        )
    }
    .toArray

  val zoneSearchTreeLink = zoneArrayLink
    .groupBy(_.geoId)
    .mapValues { zones =>
      zones
        .groupBy(zone => zone.parkingType)
        .mapValues(zonesByType => zonesByType.map(_.parkingZoneId).toVector)
    }

  logger.info("Generated {} zones", zoneArrayLink.length)
  logger.info("with {} parking stalls", zoneArrayLink.map(_.stallsAvailable.toLong).sum)
  ParkingZoneFileUtils.writeParkingZoneFile(zoneSearchTreeLink, zoneArrayLink, argsMap("out"))

  private def distributeParking(
    taz: TAZ,
    links: List[Link],
    parkingZones: Array[ParkingZone[TAZ]],
    zoneSearchTree: ZoneSearchTree[TAZ]
  ) = {
    val totalLength = links.map(_.getLength).sum
    val tazParkingZones = for {
      parkingTypesSubtree <- zoneSearchTree.get(taz.tazId).toList
      parkingType         <- ParkingType.AllTypes
      parkingZoneIds      <- parkingTypesSubtree.get(parkingType).toList
      parkingZoneId       <- parkingZoneIds
      parkingZone         <- ParkingZone.getParkingZone(parkingZones, parkingZoneId)
    } yield {
      parkingZone
    }

    links.flatMap { link =>
//      take random n zones for each link and scale their parking slot number
//      val n = 3
//      val randomZones = Random.shuffle(tazParkingZones).take(n)
      val randomZones = tazParkingZones
      val multiplier = randomZones.size.toDouble / tazParkingZones.size
      randomZones.map { zone =>
        val zonesPerMeter = zone.maxStalls * multiplier / totalLength
        val numZones = Math.round(zonesPerMeter * link.getLength).toInt
        new ParkingZone[Link](
          parkingZoneId = -1,
          geoId = link.getId,
          parkingType = zone.parkingType,
          stallsAvailable = numZones,
          maxStalls = numZones,
          reservedFor = zone.reservedFor,
          vehicleManager = zone.vehicleManager,
          chargingPointType = zone.chargingPointType,
          pricingModel = zone.pricingModel,
          timeRestrictions = zone.timeRestrictions,
          parkingZoneName = zone.parkingZoneName,
          landCostInUSDPerSqft = zone.landCostInUSDPerSqft,
        )
      }
    }

  }

}
