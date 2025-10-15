package beam.agentsim.infrastructure.taz

import org.geotools.referencing.{CRS, GeodeticCalculator}
import org.geotools.referencing.crs.DefaultGeographicCRS
import org.locationtech.jts.geom.GeometryFactory
import org.matsim.api.core.v01.network.Link
import org.matsim.core.utils.collections.QuadTree
import org.opengis.referencing.operation.MathTransform
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

abstract class SearchQuadTree(scenarioCRS: String) {
  import SearchQuadTree._

  private val logger = LoggerFactory.getLogger(this.getClass)

  // Coordinate transformation from scenarioCRS to internalCRS
  private val (transform, inverseTransform): (Option[MathTransform], Option[MathTransform]) =
    if (scenarioCRS != TAZTreeMap.internalCRS) {
      try {
        val sourceCRS = CRS.decode(scenarioCRS)
        val targetCRS = CRS.decode(TAZTreeMap.internalCRS)
        val forward = CRS.findMathTransform(sourceCRS, targetCRS, true)
        val inverse = CRS.findMathTransform(targetCRS, sourceCRS, true)
        (Some(forward), Some(inverse))
      } catch {
        case e: Exception =>
          logger.error(
            s"Failed to create coordinate transformation from $scenarioCRS to ${TAZTreeMap.internalCRS}",
            e
          )
          (None, None)
      }
    } else {
      (None, None)
    }

  private val geometryFactory = new GeometryFactory()

  // Cache for transformed coordinates - using thread-safe TrieMap
  private val transformCache = TrieMap.empty[(Double, Double), (Double, Double)]
  private val inverseTransformCache = TrieMap.empty[(Double, Double), (Double, Double)]

  // Cache for radius transformations - key is (lon, lat, radiusInMeters)
  private val radiusCache = TrieMap.empty[(Double, Double, Double), Double]

  // Thread-local GeodeticCalculator for efficient reuse
  private val geodeticCalculator = new ThreadLocal[GeodeticCalculator] {
    override def initialValue(): GeodeticCalculator = new GeodeticCalculator(DefaultGeographicCRS.WGS84)
  }

  // Transform from scenario CRS to internal CRS (e.g., UTM -> lat/lon)
  protected def transformCoord(x: Double, y: Double): (Double, Double) = {
    val key = (x, y)
    transformCache.getOrElseUpdate(
      key, {
        val result = TAZTreeMap.transformSingleCoord(x, y, transform, geometryFactory)
        // Only populate inverse cache if not already present to avoid race conditions
        inverseTransformCache.putIfAbsent(result, key)
        result
      }
    )
  }

  // Transform from internal CRS back to scenario CRS (e.g., lat/lon -> UTM)
  def transformCoordToScenarioCRS(x: Double, y: Double): (Double, Double) = {
    val key = (x, y)
    inverseTransformCache.getOrElseUpdate(
      key, {
        val result = TAZTreeMap.transformSingleCoord(x, y, inverseTransform, geometryFactory)
        // Only populate forward cache if not already present to avoid race conditions
        transformCache.putIfAbsent(result, key)
        result
      }
    )
  }

  /**
    * transformation
    * Use GeodeticCalculator for accurate distance-to-degrees conversion
    * This is the most accurate method using proper ellipsoid calculations
    */
  private def metersRadiusToDegreesRadiusGeodetic(
    lonInWGS: Double,
    latInWGS: Double,
    radiusInMeters: Double
  ): Double = {
    if (radiusInMeters > 0) {
      // Check cache first
      val cacheKey = (lonInWGS, latInWGS, radiusInMeters)
      radiusCache.getOrElseUpdate(
        cacheKey, {
          val calc = geodeticCalculator.get()

          // Set the starting point
          calc.setStartingGeographicPoint(lonInWGS, latInWGS)

          // Calculate point at radius distance north (for latitude difference)
          calc.setDirection(0, radiusInMeters) // 0 degrees = north
          val northPoint = calc.getDestinationGeographicPoint
          val deltaLat = math.abs(northPoint.getY - latInWGS)

          // Calculate point at radius distance east (for longitude difference)
          calc.setDirection(90, radiusInMeters) // 90 degrees = east
          val eastPoint = calc.getDestinationGeographicPoint
          val deltaLon = math.abs(eastPoint.getX - lonInWGS)

          // Return the maximum to ensure full coverage
          math.max(deltaLat, deltaLon)
        }
      )
    } else {
      radiusInMeters // No transformation needed
    }
  }

  /**
    * Radius transformation
    * Solution 2: Use improved Haversine-based approximation (faster, still accurate)
    * This uses the WGS84 ellipsoid parameters for better accuracy than simple 111320
    */
  private def metersRadiusToDegreesRadiusHaversine(
    latInWGS: Double,
    radiusInMeters: Double
  ): Double = {
    if (radiusInMeters > 0) {
      // WGS84 ellipsoid parameters
      val a = 6378137.0 // Equatorial radius in meters
      val f = 1.0 / 298.257223563 // Flattening
      val e2 = 2 * f - f * f // First eccentricity squared

      val latRad = math.toRadians(latInWGS)
      val sinLat = math.sin(latRad)
      val cosLat = math.cos(latRad)

      // Radius of curvature in the meridian (north-south)
      val M = a * (1 - e2) / math.pow(1 - e2 * sinLat * sinLat, 1.5)

      // Radius of curvature in the prime vertical (east-west)
      val N = a / math.sqrt(1 - e2 * sinLat * sinLat)

      // Convert meters to degrees
      val deltaLat = radiusInMeters / M * (180.0 / math.Pi)
      val deltaLon = radiusInMeters / (N * cosLat) * (180.0 / math.Pi)

      // Return the maximum to ensure full coverage
      math.max(deltaLat, math.abs(deltaLon))
    } else {
      radiusInMeters
    }
  }

  // Choose which implementation to use based on your needs:
  // - Geodetic: Most accurate, slightly slower
  // - Haversine: Good accuracy, fast

  private def metersRadiusToDegreesRadius(
    lonInWGS: Double,
    latInWGS: Double,
    radiusInMeters: Double
  ): Double = {
    // metersRadiusToDegreesRadiusGeodetic(lonInWGS, latInWGS, radiusInMeters)  // Most accurate
    metersRadiusToDegreesRadiusHaversine(latInWGS, radiusInMeters) // Best balance
  }

  def getRing(
    x: Double,
    y: Double,
    innerRadius: Double,
    outerRadius: Double,
    sampleSize: Int
  ): SearchQuadTreeResults = {
    val (lonInWGS, latInWGS) = transformCoord(x, y)
    val innerRadiusInDegrees = metersRadiusToDegreesRadius(lonInWGS, latInWGS, innerRadius)
    val outerRadiusInDegrees = metersRadiusToDegreesRadius(lonInWGS, latInWGS, outerRadius)
    getRingInternal(lonInWGS, latInWGS, innerRadiusInDegrees, outerRadiusInDegrees, sampleSize)
  }

  def getElliptical(
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
    innerRadius: Double,
    sampleSize: Int
  ): SearchQuadTreeResults = {
    val (lon1inWGS, lat1inWGS) = transformCoord(x1, y1)
    val (lon2inWGS, lat2inWGS) = transformCoord(x2, y2)

    // Use midpoint of original UTM coords for radius conversion
    val midLonInWGS = (lon1inWGS + lon2inWGS) / 2.0
    val midLatInWGS = (lat1inWGS + lat2inWGS) / 2.0
    val innerRadiusInDegrees = metersRadiusToDegreesRadius(midLonInWGS, midLatInWGS, innerRadius)

    getEllipticalInternal(
      lon1inWGS,
      lat1inWGS,
      lon2inWGS,
      lat2inWGS,
      innerRadiusInDegrees,
      sampleSize
    )
  }

  // Abstract internal methods that implementations must provide
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
    innerRadius: Double,
    sampleSize: Int
  ): SearchQuadTreeResults
}

object SearchQuadTree {

  case class SearchQuadTreeResults(
    zones: Set[TAZ],
    links: Option[Set[Link]],
    tazToLinks: Option[Map[TAZ, QuadTree[Link]]]
  )

  def getSearchQuadTree(tazTreeMap: TAZTreeMap, enableLinkBasedSearch: Boolean): SearchQuadTree = {
    if (enableLinkBasedSearch && tazTreeMap.linkQuadTree.isDefined) SearchLinkQuadTree(tazTreeMap)
    else SearchTAZQuadTree(tazTreeMap)
  }

  case class SearchTAZQuadTree(tazTreeMap: TAZTreeMap) extends SearchQuadTree(tazTreeMap.scenarioCRS) {

    override def getRingInternal(
      lon: Double,
      lat: Double,
      innerRadius: Double,
      outerRadius: Double,
      sampleSize: Int = 100
    ): SearchQuadTreeResults = {
      val result = Set.newBuilder[TAZ]
      tazTreeMap.tazQuadTree.getRing(lon, lat, innerRadius, outerRadius).forEach(taz => result += taz)
      val tazs = result.result()
      SearchQuadTreeResults(tazs, None, None)
    }

    override def getEllipticalInternal(
      lon1: Double,
      lat1: Double,
      lon2: Double,
      lat2: Double,
      radius: Double,
      sampleSize: Int = 100
    ): SearchQuadTreeResults = {
      val result = Set.newBuilder[TAZ]
      tazTreeMap.tazQuadTree.getElliptical(lon1, lat1, lon2, lat2, radius).forEach(taz => result += taz)
      val tazs = result.result()
      SearchQuadTreeResults(tazs, None, None)
    }
  }

  case class SearchLinkQuadTree(tazTreeMap: TAZTreeMap) extends SearchQuadTree(tazTreeMap.scenarioCRS) {

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
            tazId -> TAZTreeMap.fromLinks(links, tazTreeMap.scenarioCRS)
          }
          .seq
          .toMap

        val finalLinkSet = linkSetBuilder.result()
        SearchQuadTreeResults(tazSet, Some(finalLinkSet), Some(tazToLinksQuadTree))
      }
    }

    override def getRingInternal(
      lon: Double,
      lat: Double,
      innerRadius: Double,
      outerRadius: Double,
      sampleSize: Int = 100
    ): SearchQuadTreeResults = {
      val tazToLinks = mutable.HashMap.empty[TAZ, mutable.ArrayBuffer[Link]]
      tazTreeMap.linkQuadTree.get.getRing(lon, lat, innerRadius, outerRadius).asScala.take(sampleSize).foreach { link =>
        val taz = tazTreeMap.idToTAZMapping(tazTreeMap.linkIdToTAZMapping(link.getId))
        tazToLinks.getOrElseUpdate(taz, mutable.ArrayBuffer.empty[Link]) += link
      }
      buildSearchResult(tazToLinks)
    }

    override def getEllipticalInternal(
      lon1: Double,
      lat1: Double,
      lon2: Double,
      lat2: Double,
      radius: Double,
      sampleSize: Int = 100
    ): SearchQuadTreeResults = {
      val tazToLinks = mutable.HashMap.empty[TAZ, mutable.ArrayBuffer[Link]]
      tazTreeMap.linkQuadTree.get.getElliptical(lon1, lat1, lon2, lat2, radius).asScala.take(sampleSize).foreach {
        link =>
          val taz = tazTreeMap.idToTAZMapping(tazTreeMap.linkIdToTAZMapping(link.getId))
          tazToLinks.getOrElseUpdate(taz, mutable.ArrayBuffer.empty[Link]) += link
      }
      buildSearchResult(tazToLinks)
    }
  }
}
