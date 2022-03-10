package beam.utils

import beam.router.r5.R5Parameters
import beam.sim.BeamHelper
import beam.sim.common.GeoUtils
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.JavaConverters._

object LinkIdsToTrip extends BeamHelper {

  def main(args: Array[String]): Unit = {
    val configPath = "test/input/sf-light/sf-light-1k.conf"

    val manualArgs = Array[String]("--config", configPath)
    val (_, c) = prepareConfig(manualArgs, isConfigArgRequired = true)
    val (r5Parameters, networkCoordinator) = R5Parameters.fromConfigWithNetwork(c)
    implicit val linkIdCoordMap: scala.collection.Map[Id[Link], Coord] = networkCoordinator.network.getLinks.asScala.mapValues(_.getCoord)
    implicit val geoUtils: GeoUtils = r5Parameters.geo

    // input
    printLinks(Seq(88753, 51087, 50223, 51085, 94243, 51083, 51081, 47225, 88925, 47223))

    // output
    // beam.utils.LinkIdsToTrip$ - trip [[-122.41810884999806,37.748624100000235], [-122.41815495012234,37.74839320001403],
    // [-122.4182401500271,37.748157100005976], [-122.41870250381271,37.747441950839416], [-122.41928750052763,37.746537000116135],
    // [-122.41948005002415,37.74623910000531], [-122.41970265074623,37.745894800164294], [-122.41990580000439,37.74558065000097],
    // [-122.42008900059699,37.74529725013138], [-122.42057380209505,37.74454725046122]]
  }

  def printLinks(linkIds: Seq[Int])(implicit geoUtils: GeoUtils, linkIdCoordMap: scala.collection.Map[Id[Link], Coord]): Unit = {
    val coords = linkIds.map(linkId => geoUtils.utm2Wgs(linkIdCoordMap(Id.createLinkId(linkId))))
    val trip = coords.map(loc => s"[${loc.getX},${loc.getY}]")
    logger.info("trip {}", trip.mkString("[", ", ", "]"))
  }

}
