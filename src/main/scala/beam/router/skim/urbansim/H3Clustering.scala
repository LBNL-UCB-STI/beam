package beam.router.skim.urbansim

import beam.agentsim.infrastructure.geozone._
import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.sim.common.GeoUtils
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.population.{Activity, Population}

import scala.collection.JavaConverters._

trait GeoClustering

class TAZClustering(val tazTreeMap: TAZTreeMap) extends GeoClustering {}

object TAZClustering {

  def getGeoIndexCenters(src: TAZIndex, dst: TAZIndex): (Coord, Coord) = {
    val srcGeoCenter = src.taz.coord
    val dstGeoCenter = dst.taz.coord
    val srcCoord = if (src == dst) {
      new Coord(srcGeoCenter.getX + Math.sqrt(src.taz.areaInSquareMeters) / 3.0, srcGeoCenter.getY)
    } else {
      srcGeoCenter
    }
    val dstCoord = if (src == dst) {
      new Coord(
        dstGeoCenter.getX - Math.sqrt(dst.taz.areaInSquareMeters) / 3.0,
        dstGeoCenter.getY
      )
    } else {
      dstGeoCenter
    }

    (srcCoord, dstCoord)
  }
}

class H3Clustering(val population: Population, val geoUtils: GeoUtils, val nZones: Int)
    extends GeoClustering
    with StrictLogging {

  private val wgsCoordinates: Set[WgsCoordinate] =
    getAllActivitiesLocations.map(geoUtils.utm2Wgs(_)).map(WgsCoordinate.apply).toSet

  private val summary: GeoZoneSummary =
    TopDownEqualDemandH3IndexMapper.from(new GeoZone(wgsCoordinates).includeBoundBoxPoints, nZones).generateSummary()

  logger.info(s"Created ${summary.items.length} H3 indexes from ${wgsCoordinates.size} unique coordinates")
  printClustersInfo(summary)

  val h3Indexes: Seq[GeoZoneSummaryItem] = summary.items.sortBy(x => -x.size)

  private def getAllActivitiesLocations: Iterable[Coord] = {
    population.getPersons
      .values()
      .asScala
      .flatMap { person =>
        person.getSelectedPlan.getPlanElements.asScala.collect {
          case act: Activity => act.getCoord
        }
      }
  }

  private def printClustersInfo(summary: GeoZoneSummary): Unit = {
    val resolutionToPoints = summary.items
      .map(x => x.index.resolution -> x.size)
      .groupBy { case (res, _) => res }
      .toSeq
      .map { case (res, xs) => res -> xs.map(_._2).sum }
      .sortBy { case (_, size) => -size }
    resolutionToPoints.foreach {
      case (res, size) =>
        logger.info(s"Resolution: $res, number of points: $size")
    }
  }
}

object H3Clustering {

  def getGeoIndexCenters(geoUtils: GeoUtils, src: H3Index, dst: H3Index): (Coord, Coord) = {
    val srcGeoCenter = getGeoIndexCenter(geoUtils, src)
    val dstGeoCenter = getGeoIndexCenter(geoUtils, dst)
    val srcCoord = if (src == dst) {
      new Coord(srcGeoCenter.getX + Math.sqrt(H3Wrapper.hexAreaM2(src.resolution)) / 3.0, srcGeoCenter.getY)
    } else {
      srcGeoCenter
    }
    val dstCoord = if (src == dst) {
      new Coord(
        dstGeoCenter.getX - Math.sqrt(H3Wrapper.hexAreaM2(dst.resolution)) / 3.0,
        dstGeoCenter.getY
      )
    } else {
      dstGeoCenter
    }
    (srcCoord, dstCoord)
  }

  def getGeoIndexCenter(geoUtils: GeoUtils, geoIndex: H3Index): Coord = {
    val hexCentroid = H3Wrapper.wgsCoordinate(geoIndex).coord
    geoUtils.wgs2Utm(hexCentroid)
  }
}
