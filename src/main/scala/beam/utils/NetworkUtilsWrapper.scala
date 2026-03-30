package beam.utils

import beam.utils.geospatial.SpatialProjectionUtils
import org.apache.log4j.Logger
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.utils.geometry.CoordUtils

import scala.jdk.CollectionConverters._

/**
  * Wrapper utility class that extends NetworkUtils functionality
  * to find the nearest link filtered by allowed mode.
  * Follows the same efficient pattern as NetworkUtils.getNearestLink
  */
object NetworkUtilsWrapper {
  private val log: Logger = Logger.getLogger(NetworkUtilsWrapper.getClass)

  /**
    * Finds the nearest link to a coordinate that allows the specified mode.
    * Uses an efficient two-step approach:
    * 1. Find the nearest node
    * 2. Search only incident links to that node filtered by mode
    *
    * @param network the network to search in
    * @param coord   the coordinate to find the nearest link to
    * @param mode    the mode that the link must allow (e.g., "car", "bike", "pt")
    * @return the nearest link that allows the specified mode, or null if no such link exists
    */
  def getNearestLinkByMode(
    network: Network,
    coord: Coord,
    modes: Array[String],
    scenarioCRS: String,
    minRadiusInMeter: Double,
    maxRadiusInMeter: Double
  ): Link = {
    val metersToProjectedUnits: Double = SpatialProjectionUtils.calculateMetersToProjectedUnits(scenarioCRS)
    var nearestLink: Link = null
    var shortestDistance: Double = Double.MaxValue

    // Start with a small search radius and expand if no link found
    var searchRadius: Double = minRadiusInMeter * metersToProjectedUnits // initial radius in meters
    val maxRadius: Double = maxRadiusInMeter * metersToProjectedUnits // maximum search radius

    while (nearestLink == null && searchRadius <= maxRadius) {
      val nearbyNodes = Option(NetworkUtils.getNearestNodes(network, coord, searchRadius))
        .map(_.asScala)
        .getOrElse(Iterable.empty)

      if (nearbyNodes.isEmpty) {
        log.debug(s"[no nodes found within radius $searchRadius for mode '${modes.mkString(",")}']")
        searchRadius *= 2 // double the radius
      } else {
        // Search through all nearby nodes for links with the specified mode
        for (node <- nearbyNodes) {
          val linksIterator = NetworkUtils.getIncidentLinks(node).values().asScala

          for (link <- linksIterator) {
            val hasAllowedMode = modes.exists(mode =>
              link.getAllowedModes.asScala.exists(allowedMode => allowedMode.equalsIgnoreCase(mode))
            )
            // Filter by mode - only consider links that allow the specified mode
            if (hasAllowedMode) {
              val dist = CoordUtils.distancePointLinesegment(
                link.getFromNode.getCoord,
                link.getToNode.getCoord,
                coord
              )

              if (dist < shortestDistance) {
                shortestDistance = dist
                nearestLink = link
              }
            }
          }
        }

        // If still not found, expand the search radius
        if (nearestLink == null) {
          searchRadius *= 2
        }
      }
    }

    if (nearestLink == null) {
      log.warn(
        s"[nearestLink with mode '${modes.mkString(",")}' not found within max radius. Will probably crash eventually ... Maybe run NetworkCleaner?]"
      )
    }

    nearestLink
  }
}
