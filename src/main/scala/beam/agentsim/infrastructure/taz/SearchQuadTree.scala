package beam.agentsim.infrastructure.taz

import beam.sim.config.BeamConfig
import beam.sim.{BeamScenario, BeamServices}
import beam.utils.geospatial.SpatialProjectionUtils
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.locationtech.jts.geom.{Coordinate, GeometryFactory}
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.mutable

/**
  * SearchQuadTree provides efficient spatial searches in the native projected coordinate system.
  *
  * Key design decisions:
  * - Works directly in the scenario's projected CRS (no coordinate transformations)
  * - Automatically handles unit conversions (meters, feet, etc.)
  * - Optimized for performance and accuracy
  */
abstract class SearchQuadTree(val tazTreeMap: TAZTreeMap, val links: Map[Id[Link], Link]) {
  import SearchQuadTree._

  private val logger = LoggerFactory.getLogger(this.getClass)

  // Track whether we've checked distortion yet
  @volatile private var distortionChecked = false

  val metersToProjectedUnits: Double = SpatialProjectionUtils.calculateMetersToProjectedUnits(tazTreeMap.scenarioCRS)

  // Validate CRS on initialization
  {
    if (!SearchQuadTree.isProjectedCRS(tazTreeMap.scenarioCRS)) {
      logger.warn(
        s"""
           |WARNING: CRS ${tazTreeMap.scenarioCRS} appears to be geographic (lat/lon).
           |This will result in highly inaccurate distance calculations!
           |Consider reprojecting your data to a projected CRS like:
           |  - UTM: ${SearchQuadTree.CommonCRS.getUTMZoneForArea(-122.0, 37.0)} (for San Francisco area)
           |  - State Plane (California): ${SearchQuadTree.CommonCRS.CaliforniaZone3_Meters}
           |  - Web apps (not recommended): ${SearchQuadTree.CommonCRS.WebMercator}
        """.stripMargin
      )
    } else {
      logger.info(s"Using projected CRS: ${tazTreeMap.scenarioCRS}")
    }
  }

  def getBounds: QuadTree.Rect = {
    new QuadTree.Rect(
      tazTreeMap.tazQuadTree.getMinEasting,
      tazTreeMap.tazQuadTree.getMinNorthing,
      tazTreeMap.tazQuadTree.getMaxEasting,
      tazTreeMap.tazQuadTree.getMaxNorthing
    )
  }

  /**
    * Check distortion at the first search location (lazy evaluation).
    * This gives us actual data coordinates to test with.
    */
  private def checkDistortionOnce(x: Double, y: Double): Unit = {
    if (!distortionChecked) {
      distortionChecked = true

      try {
        val distortion = SearchQuadTree.estimateDistortion(tazTreeMap.scenarioCRS, x, y)

        if (distortion > 2.0) {
          logger.error(
            f"""
               |CRITICAL: Distance calculations will be off by ${((distortion - 1) * 100)}%.0f%% at location ($x%.0f, $y%.0f)
               |This CRS (${tazTreeMap.scenarioCRS}) is not suitable for accurate distance calculations.
               |Please reproject your data to an appropriate projected coordinate system.
            """.stripMargin
          )
        } else if (distortion > 1.1) {
          logger.warn(
            f"""
               |WARNING: Distance calculations may be off by ${((distortion - 1) * 100)}%.0f%% at location ($x%.0f, $y%.0f)
               |Consider using a more appropriate projected coordinate system for better accuracy.
            """.stripMargin
          )
        } else if (distortion > 1.01) {
          logger.info(f"Distance distortion estimated at ${((distortion - 1) * 100)}%.1f%% - acceptable for most uses")
        } else {
          logger.debug(f"Excellent! Distance distortion < 1%% for CRS ${tazTreeMap.scenarioCRS}")
        }
      } catch {
        case e: Exception =>
          logger.debug(s"Could not estimate distortion: ${e.getMessage}")
      }
    }
  }

  /**
    * Convert a distance in meters to the projected coordinate system's units.
    * This is a simple multiplication - extremely fast compared to geodetic calculations.
    */
  protected def metersToProjectedDistance(meters: Double): Double = {
    meters * metersToProjectedUnits
  }

  /**
    * Get entities within a ring (donut shape) around a point.
    * All coordinates are in the scenario's CRS, distances in meters.
    *
    * @param x X coordinate in scenario CRS
    * @param y Y coordinate in scenario CRS
    * @param innerRadius Inner radius in meters
    * @param outerRadius Outer radius in meters
    * @param sampleSize Maximum number of results to return
    */
  def getRing(
    x: Double,
    y: Double,
    innerRadius: Double,
    outerRadius: Double,
    sampleSize: Int
  ): SearchQuadTreeResults = {
    // Check distortion on first use
    checkDistortionOnce(x, y)

    // Convert meter radii to projected units
    val innerRadiusProjected = metersToProjectedDistance(innerRadius)
    val outerRadiusProjected = metersToProjectedDistance(outerRadius)

    // Direct search in projected coordinates - no transformation needed!
    getRingInternal(x, y, innerRadiusProjected, outerRadiusProjected, sampleSize)
  }

  /**
    * Get entities within an elliptical region defined by two focal points and a radius.
    * All coordinates are in the scenario's CRS, distances in meters.
    *
    * @param x1 X coordinate of first focal point in scenario CRS
    * @param y1 Y coordinate of first focal point in scenario CRS
    * @param x2 X coordinate of second focal point in scenario CRS
    * @param y2 Y coordinate of second focal point in scenario CRS
    * @param radius Radius in meters
    * @param sampleSize Maximum number of results to return
    */
  def getElliptical(
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
    radius: Double,
    sampleSize: Int
  ): SearchQuadTreeResults = {
    // Check distortion on first use (use midpoint)
    checkDistortionOnce((x1 + x2) / 2, (y1 + y2) / 2)

    // Convert meter radius to projected units
    val radiusProjected = metersToProjectedDistance(radius)

    // Direct search in projected coordinates - no transformation needed!
    getEllipticalInternal(x1, y1, x2, y2, radiusProjected, sampleSize)
  }

  /**
    * Abstract internal methods that implementations must provide.
    * These work directly with projected coordinates and distances.
    */
  protected def getRingInternal(
    x: Double,
    y: Double,
    innerRadius: Double,
    outerRadius: Double,
    sampleSize: Int
  ): SearchQuadTreeResults

  protected def getEllipticalInternal(
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
    radius: Double,
    sampleSize: Int
  ): SearchQuadTreeResults
}

object SearchQuadTree {

  case class SearchQuadTreeResults(
    zones: Set[TAZ],
    links: Option[Set[Link]],
    tazToLinks: Option[Map[TAZ, QuadTree[Link]]]
  )

  def getSearchQuadTree(
    tazTreeMap: TAZTreeMap,
    links: Map[Id[Link], Link] = Map.empty[Id[Link], Link],
    enableLinkBasedSearch: Boolean = false
  ): SearchQuadTree = {
    if (enableLinkBasedSearch && links.nonEmpty) {
      SearchLinkQuadTree(tazTreeMap, links)
    } else {
      SearchTAZQuadTree(tazTreeMap, links)
    }
  }

  def getSearchQuadTree(
    tazTreeMap: TAZTreeMap,
    links: Map[Id[Link], Link],
    beamConfig: BeamConfig
  ): SearchQuadTree = {
    getSearchQuadTree(
      tazTreeMap,
      links,
      beamConfig.beam.agentsim.agents.parking.search.params.enableLinkBasedSearch
    )
  }

  def getSearchQuadTree(beamScenario: BeamScenario): SearchQuadTree = {
    getSearchQuadTree(
      beamScenario.tazTreeMap,
      beamScenario.network.getLinks.asScala.toMap,
      beamScenario.beamConfig.beam.agentsim.agents.parking.search.params.enableLinkBasedSearch
    )
  }

  def getSearchQuadTree(beamServices: BeamServices): SearchQuadTree = getSearchQuadTree(beamServices.beamScenario)

  /**
    * TAZ-based search implementation.
    * Searches directly in the TAZ QuadTree using projected coordinates.
    */
  case class SearchTAZQuadTree(
    override val tazTreeMap: TAZTreeMap,
    override val links: Map[Id[Link], Link]
  ) extends SearchQuadTree(tazTreeMap, links) {

    override def getRingInternal(
      x: Double,
      y: Double,
      innerRadius: Double,
      outerRadius: Double,
      sampleSize: Int = 100
    ): SearchQuadTreeResults = {
      // Direct search in projected coordinates
      val result = Set.newBuilder[TAZ]
      tazTreeMap.tazQuadTree
        .getRing(x, y, innerRadius, outerRadius)
        .forEach(taz => result += taz)

      val tazs = result.result()
      SearchQuadTreeResults(tazs, None, None)
    }

    override def getEllipticalInternal(
      x1: Double,
      y1: Double,
      x2: Double,
      y2: Double,
      radius: Double,
      sampleSize: Int = 100
    ): SearchQuadTreeResults = {
      // Direct search in projected coordinates
      val result = Set.newBuilder[TAZ]
      tazTreeMap.tazQuadTree
        .getElliptical(x1, y1, x2, y2, radius)
        .forEach(taz => result += taz)

      val tazs = result.result()
      SearchQuadTreeResults(tazs, None, None)
    }
  }

  /**
    * Link-based search implementation.
    * Searches in the Link QuadTree and groups results by TAZ.
    */
  case class SearchLinkQuadTree(
    override val tazTreeMap: TAZTreeMap,
    override val links: Map[Id[Link], Link]
  ) extends SearchQuadTree(tazTreeMap, links) {

    // Get all link coordinates
    val linkCoords: Iterable[Coord] = links.values.flatMap { link =>
      Seq(link.getFromNode.getCoord, link.getToNode.getCoord)
    }

    // Create QuadTree using link bounding box
    val linkQuadTree: QuadTree[Link] = new QuadTree[Link](
      linkCoords.map(_.getX).min,
      linkCoords.map(_.getY).min,
      linkCoords.map(_.getX).max,
      linkCoords.map(_.getY).max
    )

    // Populate QuadTree with links
    links.foreach { case (_, link) =>
      val startPoint = link.getFromNode.getCoord
      val endPoint = link.getToNode.getCoord
      val linkMidpoint = new Coord(0.5 * (endPoint.getX + startPoint.getX), 0.5 * (endPoint.getY + startPoint.getY))
      linkQuadTree.put(startPoint.getX, startPoint.getY, link)
      linkQuadTree.put(endPoint.getX, endPoint.getY, link)
      linkQuadTree.put(linkMidpoint.getX, linkMidpoint.getY, link)
    }

    private def buildSearchResult(
      tazToLinks: mutable.HashMap[TAZ, mutable.ArrayBuffer[Link]]
    ): SearchQuadTreeResults = {
      if (tazToLinks.isEmpty) {
        SearchQuadTreeResults(Set.empty, Some(Set.empty), Some(Map.empty))
      } else {
        val tazSet = tazToLinks.keySet.toSet

        // Build link set from all collected links
        val linkSetBuilder = Set.newBuilder[Link]
        var totalLinks = 0
        tazToLinks.values.foreach { links =>
          linkSetBuilder ++= links
          totalLinks += links.size
        }
        linkSetBuilder.sizeHint(totalLinks)

        // Build quad trees for each TAZ
        val tazToLinksQuadTree = tazToLinks.par
          .map { case (tazId, links) =>
            tazId -> buildQuadTreeLink(links)
          }
          .seq
          .toMap

        val finalLinkSet = linkSetBuilder.result()
        SearchQuadTreeResults(tazSet, Some(finalLinkSet), Some(tazToLinksQuadTree))
      }
    }

    override def getRingInternal(
      x: Double,
      y: Double,
      innerRadius: Double,
      outerRadius: Double,
      sampleSize: Int = 100
    ): SearchQuadTreeResults = {
      val tazToLinks = mutable.HashMap.empty[TAZ, mutable.ArrayBuffer[Link]]

      // Direct search in projected coordinates - deduplicate with Set, then sample
      val uniqueLinks = linkQuadTree
        .getRing(x, y, innerRadius, outerRadius)
        .asScala
        .filter { link =>
          val allowed = link.getAllowedModes.asScala.map(_.toLowerCase)
          allowed.contains("car") && allowed.contains("walk")
        }
        .toSet

      val sampledLinks = if (uniqueLinks.size <= sampleSize) {
        uniqueLinks
      } else {
        scala.util.Random.shuffle(uniqueLinks.toSeq).take(sampleSize)
      }

      sampledLinks.foreach { link =>
        val taz = tazTreeMap.idToTAZMapping(tazTreeMap.linkIdToTAZMapping(link.getId))
        tazToLinks.getOrElseUpdate(taz, mutable.ArrayBuffer.empty[Link]) += link
      }

      buildSearchResult(tazToLinks)
    }

    override def getEllipticalInternal(
      x1: Double,
      y1: Double,
      x2: Double,
      y2: Double,
      radius: Double,
      sampleSize: Int = 100
    ): SearchQuadTreeResults = {
      val tazToLinks = mutable.HashMap.empty[TAZ, mutable.ArrayBuffer[Link]]

      // Direct search in projected coordinates - deduplicate with Set, then sample
      val uniqueLinks = linkQuadTree
        .getElliptical(x1, y1, x2, y2, radius)
        .asScala
        .toSet

      val sampledLinks = if (uniqueLinks.size <= sampleSize) {
        uniqueLinks
      } else {
        scala.util.Random.shuffle(uniqueLinks.toSeq).take(sampleSize)
      }

      sampledLinks.foreach { link =>
        val taz = tazTreeMap.idToTAZMapping(tazTreeMap.linkIdToTAZMapping(link.getId))
        tazToLinks.getOrElseUpdate(taz, mutable.ArrayBuffer.empty[Link]) += link
      }

      buildSearchResult(tazToLinks)
    }

    def buildQuadTreeLink(links: Seq[Link]): QuadTree[Link] = {
      if (links.isEmpty) {
        return new QuadTree[Link](-1, -1, 1, 1)
      }

      // Calculate bounds
      var minX = Double.MaxValue
      var maxX = Double.MinValue
      var minY = Double.MaxValue
      var maxY = Double.MinValue

      links.foreach { link =>
        val fromX = link.getFromNode.getCoord.getX
        val fromY = link.getFromNode.getCoord.getY
        val toX = link.getToNode.getCoord.getX
        val toY = link.getToNode.getCoord.getY

        // Check both endpoints for bounds
        if (fromX < minX) minX = fromX
        if (fromX > maxX) maxX = fromX
        if (fromY < minY) minY = fromY
        if (fromY > maxY) maxY = fromY

        if (toX < minX) minX = toX
        if (toX > maxX) maxX = toX
        if (toY < minY) minY = toY
        if (toY > maxY) maxY = toY
      }

      val linkMidpoints = links.map { link =>
        val fromX = link.getFromNode.getCoord.getX
        val fromY = link.getFromNode.getCoord.getY
        val toX = link.getToNode.getCoord.getX
        val toY = link.getToNode.getCoord.getY

        val midX = 0.5 * (fromX + toX)
        val midY = 0.5 * (fromY + toY)

        (link, midX, midY)
      }

      val buffer = 100.0
      val quadTree = new QuadTree[Link](
        minX - buffer,
        minY - buffer,
        maxX + buffer,
        maxY + buffer
      )

      linkMidpoints.foreach { case (link, midX, midY) =>
        quadTree.put(midX, midY, link)
      }

      quadTree
    }
  }

  /**
    * Utility method to validate if a CRS is suitable for distance calculations.
    * Projected coordinate systems (like UTM, State Plane) are good.
    * Geographic coordinate systems (like WGS84) are not suitable.
    */
  def isProjectedCRS(crsCode: String): Boolean = {
    try {
      val crs = CRS.decode(crsCode)
      val cs = crs.getCoordinateSystem

      // Check if the axes use linear units (meters, feet, etc.) rather than angular (degrees)
      val firstAxis = cs.getAxis(0)
      val unitString = firstAxis.getUnit.toString

      // If unit is in degrees, it's geographic (not good for distance calculations)
      !unitString.toLowerCase.contains("degree") && !unitString.contains("°")
    } catch {
      case _: Exception =>
        // If we can't determine, assume it's projected
        true
    }
  }

  /**
    * Estimate the distance distortion at a given location for a CRS.
    * This helps users understand the accuracy of their distance calculations.
    *
    * @param crsCode The EPSG code of the CRS
    * @param x X coordinate in the CRS
    * @param y Y coordinate in the CRS
    * @return Estimated distortion factor (1.0 = no distortion, 1.1 = 10% distortion)
    */
  def estimateDistortion(crsCode: String, x: Double, y: Double): Double = {
    try {
      crsCode match {
        case code if code == CommonCRS.WGS84 || code == CommonCRS.NAD83 =>
          // Geographic coordinates - extreme distortion
          val lat = math.abs(y)
          if (lat > 85) {
            Double.PositiveInfinity // Essentially unusable near poles
          } else {
            1.0 / math.cos(math.toRadians(lat)) // Distortion increases with latitude
          }

        case code if code == CommonCRS.WebMercator =>
          // Web Mercator - significant distortion away from equator
          // Need to convert from Web Mercator to lat/lon first
          val crs = CRS.decode(code)
          val wgs84 = CRS.decode(CommonCRS.WGS84)
          val transform = CRS.findMathTransform(crs, wgs84, true)
          val coord = new Coordinate(x, y)
          val geomFactory = new GeometryFactory()
          val srcPt = geomFactory.createPoint(coord)
          val transformed = JTS.transform(srcPt, transform)
          val lat = math.abs(transformed.getCoordinate.y)

          if (lat > 85) {
            Double.PositiveInfinity // Web Mercator breaks down near poles
          } else {
            1.0 / math.cos(math.toRadians(lat))
          }

        case code if code.startsWith("EPSG:326") || code.startsWith("EPSG:327") =>
          // UTM - very low distortion within zone (< 0.1%)
          1.0004 // Maximum distortion in UTM is about 0.04%

        case code if code.contains("2227") || code.contains("2263") || code.contains("2277") =>
          // US State Plane - designed for low distortion
          1.0001 // Typically < 0.01% distortion

        case _ =>
          // Other projected systems - assume low distortion
          1.01 // Conservative estimate of 1% distortion
      }
    } catch {
      case _: Exception =>
        1.05 // If we can't determine, assume 5% distortion
    }
  }

  /**
    * Common EPSG codes and their units for reference.
    * This is helpful for understanding what CRS codes use which units.
    */
  object CommonCRS {

    // Geographic (not recommended for distance calculations)
    val WGS84 = "EPSG:4326" // degrees
    val NAD83 = "EPSG:4269" // degrees

    // Web Mercator (not recommended - severe distortion)
    val WebMercator = "EPSG:3857" // meters (but distorted!)

    // UTM Zones (recommended - meters)
    def utmZoneNorth(zone: Int): String = s"EPSG:326$zone" // Northern hemisphere
    def utmZoneSouth(zone: Int): String = s"EPSG:327$zone" // Southern hemisphere

    // US State Plane (feet or meters depending on state)
    val CaliforniaZone3_Meters = "EPSG:26943" // meters

    /**
      * Get the appropriate UTM zone for a longitude/latitude.
      * @param longitude Longitude in degrees
      * @param latitude Latitude in degrees (for determining hemisphere)
      * @return EPSG code for the UTM zone
      */
    def getUTMZoneForArea(longitude: Double, latitude: Double): String = {
      val zone = math.floor((longitude + 180.0) / 6.0).toInt + 1
      if (latitude >= 0) {
        utmZoneNorth(zone)
      } else {
        utmZoneSouth(zone)
      }
    }
  }
}
