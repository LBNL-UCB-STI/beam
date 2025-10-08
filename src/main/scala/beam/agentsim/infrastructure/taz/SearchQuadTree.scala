package beam.agentsim.infrastructure.taz

import org.geotools.referencing.CRS
import org.locationtech.jts.geom.GeometryFactory
import org.matsim.api.core.v01.network.Link
import org.matsim.core.utils.collections.QuadTree
import org.opengis.referencing.operation.MathTransform
import org.slf4j.LoggerFactory

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

abstract class SearchQuadTree(scenarioCRS: String) {
  import SearchQuadTree._

  private val logger = LoggerFactory.getLogger(this.getClass)

  // Coordinate transformation from scenarioCRS to internalCRS
  private val transform: Option[MathTransform] = if (scenarioCRS != TAZTreeMap.internalCRS) {
    try {
      val sourceCRS = CRS.decode(scenarioCRS)
      val targetCRS = CRS.decode(TAZTreeMap.internalCRS)
      Some(CRS.findMathTransform(sourceCRS, targetCRS, true))
    } catch {
      case e: Exception =>
        logger.error(
          s"Failed to create coordinate transformation from $scenarioCRS to ${TAZTreeMap.internalCRS}",
          e
        )
        None
    }
  } else {
    None
  }

  private val geometryFactory = new GeometryFactory()

  // Cache for transformed coordinates - using thread-safe TrieMap
  private val transformCache = TrieMap.empty[(Double, Double), (Double, Double)]

  // Concrete implementation of transformCoord with caching
  protected def transformCoord(x: Double, y: Double): (Double, Double) = {
    val key = (x, y)

    transformCache.getOrElseUpdate(
      key, {
        TAZTreeMap.transformSingleCoord(x, y, transform, geometryFactory)
      }
    )
  }

  // Public methods that transform coordinates before delegating to internal methods
  def getRing(x: Double, y: Double, innerRadius: Double, outerRadius: Double): SearchQuadTreeResults = {
    val (transformedX, transformedY) = transformCoord(x, y)
    getRingInternal(transformedX, transformedY, innerRadius, outerRadius)
  }

  def getElliptical(x1: Double, y1: Double, x2: Double, y2: Double, innerRadius: Double): SearchQuadTreeResults = {
    val (transformedX1, transformedY1) = transformCoord(x1, y1)
    val (transformedX2, transformedY2) = transformCoord(x2, y2)
    getEllipticalInternal(transformedX1, transformedY1, transformedX2, transformedY2, innerRadius)
  }

  // Abstract internal methods that implementations must provide
  protected def getRingInternal(x: Double, y: Double, innerRadius: Double, outerRadius: Double): SearchQuadTreeResults

  protected def getEllipticalInternal(
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
    innerRadius: Double
  ): SearchQuadTreeResults
}

object SearchQuadTree {

  private val logger = LoggerFactory.getLogger(this.getClass)

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
      outerRadius: Double
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
      radius: Double
    ): SearchQuadTreeResults = {
      val result = Set.newBuilder[TAZ]
      tazTreeMap.tazQuadTree.getElliptical(x1, y1, x2, y2, radius).forEach(taz => result += taz)
      val tazs = result.result()
      SearchQuadTreeResults(tazs, None, None)
    }
  }

  case class SearchLinkQuadTree(tazTreeMap: TAZTreeMap) extends SearchQuadTree(tazTreeMap.scenarioCRS) {

    private def buildSearchResult(
      tazToLinks: mutable.HashMap[TAZ, mutable.ArrayBuffer[Link]]
    ): SearchQuadTreeResults = {
      val startTime = System.currentTimeMillis()

      if (tazToLinks.isEmpty) {
        logger.info("buildSearchResult: empty input, returning empty results")
        SearchQuadTreeResults(Set.empty, Some(Set.empty), Some(Map.empty))
      } else {
        val numTazs = tazToLinks.size
        val numLinks = tazToLinks.values.map(_.size).sum

        val tazSetStart = System.currentTimeMillis()
        val tazSet = tazToLinks.keySet.toSet
        val tazSetTime = System.currentTimeMillis() - tazSetStart

        // Pre-allocate with size hint
        val linkSetBuilder = Set.newBuilder[Link]
        linkSetBuilder.sizeHint(numLinks)

        // Single pass: build both linkSet and quad trees
        val quadTreeStart = System.currentTimeMillis()
        val tazToLinksQuadTree = tazToLinks.par
          .map { case (tazId, links) =>
            tazId -> TAZTreeMap.fromLinks(links, tazTreeMap.scenarioCRS)
          }
          .seq
          .toMap
        val quadTreeTime = System.currentTimeMillis() - quadTreeStart

        val linkSetStart = System.currentTimeMillis()
        val finalLinkSet = linkSetBuilder.result()
        val linkSetTime = System.currentTimeMillis() - linkSetStart

        val totalTime = System.currentTimeMillis() - startTime

        logger.info(
          s"buildSearchResult: processed $numTazs TAZs with $numLinks links in ${totalTime}ms " +
          s"(tazSet: ${tazSetTime}ms, quadTree: ${quadTreeTime}ms, linkSet: ${linkSetTime}ms)"
        )

        SearchQuadTreeResults(tazSet, Some(finalLinkSet), Some(tazToLinksQuadTree))
      }
    }

    override def getRingInternal(
      x: Double,
      y: Double,
      innerRadius: Double,
      outerRadius: Double
    ): SearchQuadTreeResults = {
      val tazToLinks = mutable.HashMap.empty[TAZ, mutable.ArrayBuffer[Link]]
      tazTreeMap.linkQuadTree.get.getRing(x, y, innerRadius, outerRadius).forEach { link =>
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
      radius: Double
    ): SearchQuadTreeResults = {
      val tazToLinks = mutable.HashMap.empty[TAZ, mutable.ArrayBuffer[Link]]
      tazTreeMap.linkQuadTree.get.getElliptical(x1, y1, x2, y2, radius).forEach { link =>
        val taz = tazTreeMap.idToTAZMapping(tazTreeMap.linkIdToTAZMapping(link.getId))
        tazToLinks.getOrElseUpdate(taz, mutable.ArrayBuffer.empty[Link]) += link
      }
      buildSearchResult(tazToLinks)
    }
  }
}
