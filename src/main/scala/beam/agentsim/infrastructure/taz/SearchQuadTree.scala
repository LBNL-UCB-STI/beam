package beam.agentsim.infrastructure.taz

import org.geotools.referencing.CRS
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

  // Transform from scenario CRS to internal CRS (e.g., UTM -> lat/lon)
  protected def transformCoord(x: Double, y: Double): (Double, Double) = {
    val key = (x, y)
    transformCache.get(key) match {
      case Some(result) => result
      case None =>
        val result = TAZTreeMap.transformSingleCoord(x, y, transform, geometryFactory)
        transformCache.put(key, result)
        // Populate inverse cache with the reverse mapping
        inverseTransformCache.put(result, key)
        result
    }
  }

  // Transform from internal CRS back to scenario CRS (e.g., lat/lon -> UTM)
  def transformCoordToScenarioCRS(x: Double, y: Double): (Double, Double) = {
    val key = (x, y)
    inverseTransformCache.get(key) match {
      case Some(result) => result
      case None =>
        val result = TAZTreeMap.transformSingleCoord(x, y, inverseTransform, geometryFactory)
        inverseTransformCache.put(key, result)
        // Populate forward cache with the reverse mapping
        transformCache.put(result, key)
        result
    }
  }

  // Convert meters to latitude degrees
  private def metersToLatDegrees(meters: Double): Double = {
    meters / 111320.0
  }

  // Convert meters to longitude degrees at a specific latitude
  private def metersToLonDegrees(meters: Double, latitudeInDegrees: Double): Double = {
    val metersPerDegLon = 111320.0 * math.cos(math.toRadians(latitudeInDegrees))
    meters / metersPerDegLon
  }

  /**
    * Convert a radius in meters to a radius in degrees at a specific location
    */
  private def metersRadiusToDegreesRadius(
    originalXinUTM: Double,
    originalYinUTM: Double,
    radiusInMeters: Double
  ): Double = {
    if (transform.isDefined) {
      // Transform the center point to WGS84 lat/lon
      val (transformedXinWGS, transformedYinWGS) = transformCoord(originalXinUTM, originalYinUTM)

      // One is latitude (-90 to 90), one is longitude (-180 to 180)
      val latitudeInDegrees = if (math.abs(transformedXinWGS) <= 90) transformedXinWGS else transformedYinWGS

      val deltaLatInDegrees = metersToLatDegrees(radiusInMeters)
      val deltaLonInDegrees = metersToLonDegrees(radiusInMeters, latitudeInDegrees)

      // Use max to ensure search area fully covers the radius in all directions
      math.max(deltaLatInDegrees, deltaLonInDegrees)
    } else {
      radiusInMeters // No transformation, keep in meters
    }
  }

  def getRing(
    x: Double,
    y: Double,
    innerRadius: Double,
    outerRadius: Double,
    sampleSize: Int
  ): SearchQuadTreeResults = {
    val (transformedXinWGS, transformedYinWGS) = transformCoord(x, y)
    val innerRadiusInDegrees = metersRadiusToDegreesRadius(x, y, innerRadius)
    val outerRadiusInDegrees = metersRadiusToDegreesRadius(x, y, outerRadius)
    getRingInternal(transformedXinWGS, transformedYinWGS, innerRadiusInDegrees, outerRadiusInDegrees, sampleSize)
  }

  def getElliptical(
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
    innerRadius: Double,
    sampleSize: Int
  ): SearchQuadTreeResults = {
    val (transformedX1inWGS, transformedY1inWGS) = transformCoord(x1, y1)
    val (transformedX2inWGS, transformedY2inWGS) = transformCoord(x2, y2)

    // Use midpoint of original UTM coords for radius conversion
    val midXinUTM = (x1 + x2) / 2.0
    val midYinUTM = (y1 + y2) / 2.0
    val innerRadiusInDegrees = metersRadiusToDegreesRadius(midXinUTM, midYinUTM, innerRadius)

    getEllipticalInternal(
      transformedX1inWGS,
      transformedY1inWGS,
      transformedX2inWGS,
      transformedY2inWGS,
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
      x: Double,
      y: Double,
      innerRadius: Double,
      outerRadius: Double,
      sampleSize: Int = 100
    ): SearchQuadTreeResults = {
      val result = Set.newBuilder[TAZ]
      tazTreeMap.tazQuadTree.getRing(x, y, innerRadius, outerRadius).forEach(taz => result += taz)
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
      val result = Set.newBuilder[TAZ]
      tazTreeMap.tazQuadTree.getElliptical(x1, y1, x2, y2, radius).forEach(taz => result += taz)
      val tazs = result.result()
      SearchQuadTreeResults(tazs, None, None)
    }
  }

  case class SearchLinkQuadTree(tazTreeMap: TAZTreeMap) extends SearchQuadTree(tazTreeMap.scenarioCRS) {

    // The QuadTrees are already built in tazTreeMap.tazToLinkIdMapping during network initialization!
    // We just need to use them instead of rebuilding from scratch

    private def buildSearchResult(
      tazToLinks: mutable.HashMap[TAZ, mutable.ArrayBuffer[Link]]
    ): SearchQuadTreeResults = {
      if (tazToLinks.isEmpty) {
        SearchQuadTreeResults(Set.empty, Some(Set.empty), Some(Map.empty))
      } else {
        val numLinks = tazToLinks.values.map(_.size).sum

        val tazSet = tazToLinks.keySet.toSet

        // Pre-allocate with size hint
        val linkSetBuilder = Set.newBuilder[Link]
        linkSetBuilder.sizeHint(numLinks)

        // Single pass: build both linkSet and quad trees
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
      x: Double,
      y: Double,
      innerRadius: Double,
      outerRadius: Double,
      sampleSize: Int = 100
    ): SearchQuadTreeResults = {
      val tazToLinks = mutable.HashMap.empty[TAZ, mutable.ArrayBuffer[Link]]
      tazTreeMap.linkQuadTree.get.getRing(x, y, innerRadius, outerRadius).asScala.take(sampleSize).foreach { link =>
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
      tazTreeMap.linkQuadTree.get.getElliptical(x1, y1, x2, y2, radius).asScala.take(sampleSize).foreach { link =>
        val taz = tazTreeMap.idToTAZMapping(tazTreeMap.linkIdToTAZMapping(link.getId))
        tazToLinks.getOrElseUpdate(taz, mutable.ArrayBuffer.empty[Link]) += link
      }
      buildSearchResult(tazToLinks)
    }
  }
}
