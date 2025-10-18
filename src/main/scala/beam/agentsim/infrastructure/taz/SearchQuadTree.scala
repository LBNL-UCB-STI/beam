package beam.agentsim.infrastructure.taz

import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import org.geotools.referencing.CRS
import org.geotools.geometry.jts.JTS
import org.locationtech.jts.geom.{Coordinate, GeometryFactory}
import org.matsim.api.core.v01.network.Link
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
  *
  * @param scenarioCRS The EPSG code of the coordinate reference system (e.g., "EPSG:32610" for UTM Zone 10N)
  */
abstract class SearchQuadTree(val scenarioCRS: String) {
  import SearchQuadTree._

  private val logger = LoggerFactory.getLogger(this.getClass)

  // Track whether we've checked distortion yet
  @volatile private var distortionChecked = false

  /**
    * Determines the conversion factor from meters to the CRS's native units.
    * Most projected CRS use meters, but some (like US state plane) use feet.
    */
  protected val metersToProjectedUnits: Double = {
    try {
      val crs = CRS.decode(scenarioCRS)
      val unitString = crs.getCoordinateSystem.getAxis(0).getUnit.toString.toLowerCase

      // Parse the unit string to determine conversion factor
      val factor = unitString match {
        case s if s.contains("meter") || s.contains("metre") || s == "m" =>
          1.0
        case s if s.contains("us survey foot") || s.contains("foot_us") || s.contains("us_ft") =>
          // US survey foot to meter conversion
          1.0 / 0.304800609601219
        case s if s.contains("foot") || s.contains("feet") || s.contains("ft") =>
          // International foot to meter conversion
          1.0 / 0.3048
        case s if s.contains("kilometer") || s.contains("kilometre") || s == "km" =>
          1000.0
        case s if s.contains("mile") || s == "mi" =>
          // International mile
          1.0 / 1609.344
        case s if s.contains("yard") || s == "yd" =>
          1.0 / 0.9144
        case _ =>
          // Try to check if it's already a linear unit by checking for degree
          if (unitString.contains("degree") || unitString.contains("°")) {
            logger.error(
              s"CRS $scenarioCRS uses angular units ($unitString). This will not work correctly for distance calculations!"
            )
            1.0
          } else {
            logger.warn(s"Unknown unit '$unitString' for CRS $scenarioCRS, assuming meters")
            1.0
          }
      }

      logger.info(s"CRS $scenarioCRS uses unit: $unitString, conversion factor: $factor meters -> CRS units")
      factor

    } catch {
      case e: Exception =>
        logger.error(s"Failed to determine units for CRS $scenarioCRS, assuming meters", e)
        1.0
    }
  }

  // Validate CRS on initialization
  {
    if (!SearchQuadTree.isProjectedCRS(scenarioCRS)) {
      logger.warn(
        s"""
        |WARNING: CRS $scenarioCRS appears to be geographic (lat/lon).
        |This will result in highly inaccurate distance calculations!
        |Consider reprojecting your data to a projected CRS like:
        |  - UTM: ${SearchQuadTree.CommonCRS.getUTMZoneForArea(-122.0, 37.0)} (for San Francisco area)
        |  - State Plane (California): ${SearchQuadTree.CommonCRS.CaliforniaZone3_Meters}
        |  - Web apps (not recommended): ${SearchQuadTree.CommonCRS.WebMercator}
        """.stripMargin
      )
    } else {
      logger.info(s"Using projected CRS: $scenarioCRS")
    }
  }

  /**
    * Check distortion at the first search location (lazy evaluation).
    * This gives us actual data coordinates to test with.
    */
  private def checkDistortionOnce(x: Double, y: Double): Unit = {
    if (!distortionChecked) {
      distortionChecked = true

      try {
        val distortion = SearchQuadTree.estimateDistortion(scenarioCRS, x, y)

        if (distortion > 2.0) {
          logger.error(
            f"""
            |CRITICAL: Distance calculations will be off by ${((distortion - 1) * 100)}%.0f%% at location ($x%.0f, $y%.0f)
            |This CRS ($scenarioCRS) is not suitable for accurate distance calculations.
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
          logger.debug(f"Excellent! Distance distortion < 1%% for CRS $scenarioCRS")
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
    * Convert a distance from the projected coordinate system's units to meters.
    * Useful for reporting distances back to the user.
    */
  def projectedDistanceToMeters(projectedDistance: Double): Double = {
    projectedDistance / metersToProjectedUnits
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
    * Public method to check distortion at any point in your data.
    * Useful for diagnostics and validation.
    *
    * @return Distortion factor (1.0 = no distortion, 1.5 = 50% distortion)
    */
  def getDistortionAtPoint(x: Double, y: Double): Double = {
    SearchQuadTree.estimateDistortion(scenarioCRS, x, y)
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

  def getSearchQuadTree(tazTreeMap: TAZTreeMap, enableLinkBasedSearch: Boolean, scenarioCRS: String): SearchQuadTree = {
    if (enableLinkBasedSearch && tazTreeMap.linkQuadTree.isDefined) {
      SearchLinkQuadTree(tazTreeMap, scenarioCRS)
    } else {
      SearchTAZQuadTree(tazTreeMap, scenarioCRS)
    }
  }

  def getSearchQuadTree(tazTreeMap: TAZTreeMap, beamConfig: BeamConfig): SearchQuadTree = {
    val scenarioCRS = beamConfig.beam.spatial.localCRS
    val enableLinkBasedSearch = beamConfig.beam.agentsim.agents.parking.search.params.enableLinkBasedSearch
    getSearchQuadTree(tazTreeMap, enableLinkBasedSearch, scenarioCRS)
  }

  def getSearchQuadTree(beamServices: BeamServices): SearchQuadTree = {
    val scenarioCRS = beamServices.beamConfig.beam.spatial.localCRS
    val enableLinkBasedSearch = beamServices.beamConfig.beam.agentsim.agents.parking.search.params.enableLinkBasedSearch
    getSearchQuadTree(beamServices.beamScenario.tazTreeMap, enableLinkBasedSearch, scenarioCRS)
  }

  /**
    * TAZ-based search implementation.
    * Searches directly in the TAZ QuadTree using projected coordinates.
    */
  case class SearchTAZQuadTree(tazTreeMap: TAZTreeMap, override val scenarioCRS: String)
      extends SearchQuadTree(scenarioCRS) {

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
  case class SearchLinkQuadTree(tazTreeMap: TAZTreeMap, override val scenarioCRS: String)
      extends SearchQuadTree(scenarioCRS) {

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
            tazId -> TAZTreeMap.fromLinks(links)
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

      // Direct search in projected coordinates
      tazTreeMap.linkQuadTree.get
        .getRing(x, y, innerRadius, outerRadius)
        .asScala
        .take(sampleSize)
        .foreach { link =>
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

      // Direct search in projected coordinates
      tazTreeMap.linkQuadTree.get
        .getElliptical(x1, y1, x2, y2, radius)
        .asScala
        .take(sampleSize)
        .foreach { link =>
          val taz = tazTreeMap.idToTAZMapping(tazTreeMap.linkIdToTAZMapping(link.getId))
          tazToLinks.getOrElseUpdate(taz, mutable.ArrayBuffer.empty[Link]) += link
        }

      buildSearchResult(tazToLinks)
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
    * Log information about the CRS being used.
    * Useful for debugging and verification.
    */
  def logCRSInfo(crsCode: String, logger: org.slf4j.Logger): Unit = {
    try {
      val crs = CRS.decode(crsCode)
      val unitString = crs.getCoordinateSystem.getAxis(0).getUnit.toString
      val isProjected = isProjectedCRS(crsCode)

      logger.info(s"CRS: $crsCode")
      logger.info(s"  Unit: $unitString")
      logger.info(s"  Is Projected: $isProjected")

      if (!isProjected) {
        logger.warn(
          s"CRS $crsCode appears to be geographic (lat/lon). Distance calculations will be highly inaccurate."
        )
        logger.warn("Consider reprojecting to a local projected coordinate system (e.g., UTM) for better accuracy.")
      }
    } catch {
      case e: Exception =>
        logger.error(s"Failed to analyze CRS $crsCode", e)
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
    val CaliforniaZone3_Feet = "EPSG:2227" // US survey feet
    val CaliforniaZone3_Meters = "EPSG:26943" // meters
    val NewYorkLongIsland_Feet = "EPSG:2263" // US survey feet
    val Texas_Central_Feet = "EPSG:2277" // US survey feet

    // Other common projected systems (meters)
    val BritishNationalGrid = "EPSG:27700" // meters
    val FrenchLambert93 = "EPSG:2154" // meters
    val GermanyGaussKruger = "EPSG:31467" // meters
    val AustraliaGDA94_MGA56 = "EPSG:28356" // meters

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
