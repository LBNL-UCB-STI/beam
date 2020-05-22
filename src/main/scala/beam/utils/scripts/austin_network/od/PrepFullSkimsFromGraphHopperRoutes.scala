package beam.utils.scripts.austin_network.od

import beam.sim.common.GeoUtils
import beam.utils.scripts.austin_network.MapPhysSimToTrafficDetectors.log
import beam.utils.scripts.austin_network.{AustinUtils, Logging}
import org.matsim.api.core.v01.Coord

import scala.collection.immutable.HashSet
import scala.collection.mutable
import scala.io.Source

object PrepFullSkimsFromGraphHopperRoutes {

  val ROUTE_DETOUR_FACTOR = 1.4

  def main(args: Array[String]): Unit = {
    val pathToTaz = "E:\\work\\random\\dallas\\shape\\dallas_shp\\tazCentersDallas_study_area.csv"
    val graphHopperRoutesPath = "E:\\work\\random\\dallas\\shape\\dallas_shp\\taz skims\\graphhopperRouteInfo.csv"
    val outputSkimsPath = "E:\\work\\random\\dallas\\shape\\dallas_shp\\taz skims\\fullSkims_no_cutoff.csv"

    val geoUtils: GeoUtils = new GeoUtils {
      override def localCRS: String = "epsg:26914"
    }
    // graph hopper produced in an example 0.03% routes with more than 2000 miles in a study area with around 50miles radius, these distances will be imputed as well
    val cutoffRangeInMeters = 100000000

    val log = new Logging()
    log.info("start")
    val tazs = Source.fromFile(pathToTaz).getLines().drop(1).map { line =>
      val columns = line.split(",")
      val tazId = columns(0)
      val xCoord = columns(1).toDouble
      val yCoord = columns(2).toDouble
      (tazId, new Coord(xCoord, yCoord))
    }.toMap

    val fullODPairs = (for {
      src <- tazs
      dst <- tazs
    } yield Od(src._1, dst._1)).toVector

    val graphHopperOdsWithDistance = Source.fromFile(graphHopperRoutesPath).getLines().drop(1).map { line =>
      val columns = line.split(",")
      val sourceTaz = columns(1)
      val destTaz = columns(2)
      val distanceInM = columns(3).toDouble
      OdWithDistance(Od(sourceTaz, destTaz), distanceInM)
    }.toVector.filter(odWithDistance => odWithDistance.distance < cutoffRangeInMeters)
    log.info("graphHopperOdsWithDistance done")
    val graphHopperOds =graphHopperOdsWithDistance.map( odWithDistance =>odWithDistance.od).toSet

    val missingOdPairs = fullODPairs.par.filterNot(od => graphHopperOds.contains(od) )

    val missingDistances = missingOdPairs.map { od =>
      val srcUtmCoord = tazs.get(od.src).get
      val dstUtmCoord = tazs.get(od.dst).get
      val distance = geoUtils.distUTMInMeters(srcUtmCoord, dstUtmCoord) * ROUTE_DETOUR_FACTOR
      OdWithDistance(od, distance)
    }
    log.info("missingDistances done")
    val allTazWithDistances = graphHopperOdsWithDistance ++ missingDistances

    val outputSkims = for (odWithDistance <- allTazWithDistances; hour <- 0 to 23) yield {
      s"$hour,CAR,${odWithDistance.od.src},${odWithDistance.od.dst},0,0,0,0,${odWithDistance.distance},0,0,0"
    }
    log.info("outputSkims done")
    AustinUtils.writeFile(outputSkims, outputSkimsPath, Some("hour,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,energy,observations,iterations"))
  }
}

case class Od(val src: String, val dst: String)

case class OdWithDistance(val od: Od, val distance: Double)