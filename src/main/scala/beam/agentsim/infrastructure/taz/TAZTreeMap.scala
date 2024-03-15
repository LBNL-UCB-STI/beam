package beam.agentsim.infrastructure.taz

import beam.agentsim.infrastructure.taz.TAZTreeMap.logger
import beam.utils.SnapCoordinateUtils.SnapLocationHelper
import beam.utils.SortingUtil
import beam.utils.geospatial.GeoReader
import beam.utils.matsim_conversion.ShapeUtils
import beam.utils.matsim_conversion.ShapeUtils.{HasQuadBounds, QuadTreeBounds}
import org.geotools.data.{DataStoreFinder, FileDataStore, FileDataStoreFinder}
import org.geotools.data.simple.SimpleFeatureCollection
import org.locationtech.jts.geom.Geometry
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.GeometryUtils
import org.matsim.core.utils.gis.ShapeFileReader
import org.matsim.core.utils.io.IOUtils
import org.opengis.feature.simple.SimpleFeature
import org.slf4j.LoggerFactory
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.events.handler.BasicEventHandler
import org.geotools.data.DataUtilities
import org.geotools.data.{DataStore, DataStoreFinder, Query}
import org.geotools.feature.FeatureCollection
import org.geotools.feature.FeatureIterator
import org.geotools.geojson.feature.FeatureJSON
import org.geotools.geojson.geom.GeometryJSON
import org.locationtech.jts.geom.Geometry

import java.io.File
import java.io._
import java.util
import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

/**
  * TAZTreeMap manages a quadTree to find the closest TAZ to any coordinate.
  *
  * @param tazQuadTree quadtree containing the TAZs
  * @param useCache Currently [as of 10-2020] the use of the TAZ quadtree cache is less performant than just keeping it off (better to reduce calls to TAZ quadtree
  *                 by avoiding unnecessary queries). The caching mechanism is however still useful for debugging and as a quickfix/confirmation if TAZ quadtree queries
  *                 suddenly increase due to code change.
  */
class TAZTreeMap(
  val tazQuadTree: QuadTree[TAZ],
  val useCache: Boolean = false,
  private val maybeZoneOrdering: Option[Seq[Id[TAZ]]] = None
) extends BasicEventHandler
    with IterationEndsListener {

  private val stringIdToTAZMapping: mutable.HashMap[String, TAZ] = mutable.HashMap()
  val idToTAZMapping: mutable.HashMap[Id[TAZ], TAZ] = mutable.HashMap()
  private val cache: TrieMap[(Double, Double), TAZ] = TrieMap()
  private val linkIdToTAZMapping: mutable.HashMap[Id[Link], Id[TAZ]] = mutable.HashMap.empty[Id[Link], Id[TAZ]]

  val tazToLinkIdMapping: mutable.HashMap[Id[TAZ], QuadTree[Link]] =
    mutable.HashMap.empty[Id[TAZ], QuadTree[Link]]
  private val unmatchedLinkIds: mutable.ListBuffer[Id[Link]] = mutable.ListBuffer.empty[Id[Link]]
  lazy val tazListContainsGeoms: Boolean = tazQuadTree.values().asScala.headOption.exists(_.geometry.isDefined)
  private val failedLinkLookups: mutable.ListBuffer[Id[Link]] = mutable.ListBuffer.empty[Id[Link]]

  private lazy val sortedTazIds: Seq[String] = {
    val tazIds = tazQuadTree.values().asScala.map(_.tazId.toString).toSeq
    SortingUtil.sortAsIntegers(tazIds).getOrElse(tazIds.sorted)
  }
  val orderedTazIds: Seq[String] = maybeZoneOrdering.map(order => order.map(_.toString)).getOrElse(sortedTazIds)

  def getTAZfromLink(linkId: Id[Link]): Option[TAZ] = {
    linkIdToTAZMapping.get(linkId) match {
      case Some(tazId) => getTAZ(tazId)
      case _ =>
        failedLinkLookups.append(linkId)
        None
    }
  }

  def getTAZs: Iterable[TAZ] = {
    tazQuadTree.values().asScala
  }

  for (taz: TAZ <- tazQuadTree.values().asScala) {
    stringIdToTAZMapping.put(taz.tazId.toString, taz)
    idToTAZMapping.put(taz.tazId, taz)
  }

  def getTAZ(loc: Coord): TAZ = {
    getTAZ(loc.getX, loc.getY)
  }

  def getTAZ(x: Double, y: Double): TAZ = {
    if (useCache) {
      cache.getOrElseUpdate((x, y), tazQuadTree.getClosest(x, y))
    } else {
      tazQuadTree.getClosest(x, y)
    }
  }

  def getTAZ(tazId: String): Option[TAZ] = {
    stringIdToTAZMapping.get(tazId)
  }

  def getTAZ(tazId: Id[TAZ]): Option[TAZ] = {
    stringIdToTAZMapping.get(tazId.toString)
  }

  def getTAZInRadius(x: Double, y: Double, radius: Double): util.Collection[TAZ] = {
    tazQuadTree.getDisk(x, y, radius)
  }

  def getTAZInRadius(loc: Coord, radius: Double): util.Collection[TAZ] = {
    tazQuadTree.getDisk(loc.getX, loc.getY, radius)
  }

  override def handleEvent(event: Event): Unit = {}

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    writeFailedLookupsToCsv(event)
  }

  private def writeFailedLookupsToCsv(event: IterationEndsEvent): Unit = {
    if (tazListContainsGeoms) {
      val filePath = event.getServices.getControlerIO.getIterationFilename(
        event.getServices.getIterationNumber,
        "linksWithFailedTAZlookup.csv.gz"
      )
      val numberOfFailedLookups = failedLinkLookups.size
      logger.info(
        s"Missed $numberOfFailedLookups TAZ lookups due to unmapped linkIds. Writing list to linksWithFailedTAZlookup"
      )
      implicit val writer: BufferedWriter =
        IOUtils.getBufferedWriter(filePath)
      writer.write("linkId,count")
      writer.write(System.lineSeparator())
      failedLinkLookups.toList.groupBy(identity).mapValues(_.size).foreach { case (linkId, count) =>
        try {
          writer.write(Option(linkId).mkString)
          writer.write(",")
          writer.write(count.toString)
          writer.write(System.lineSeparator())
        } catch {
          case e: Throwable => logger.error(s"${e.getMessage}. Could not write link $linkId")
        }

      }
      writer.flush()
      writer.close()
    }
    failedLinkLookups.clear()
  }

  def mapNetworkToTAZs(network: Network): Unit = {
    if (tazListContainsGeoms) {
      idToTAZMapping.keySet.foreach { id =>
        tazToLinkIdMapping(id) = new QuadTree[Link](
          tazQuadTree.getMinEasting,
          tazQuadTree.getMinNorthing,
          tazQuadTree.getMaxEasting,
          tazQuadTree.getMaxNorthing
        )
      }
      network.getLinks.asScala.foreach {
        case (id, link) =>
          val linkEndCoord = link.getToNode.getCoord
          val linkMidpoint = new Coord(
            0.5 * (link.getToNode.getCoord.getX + link.getFromNode.getCoord.getX),
            0.5 * (link.getToNode.getCoord.getY + link.getFromNode.getCoord.getY)
          )
          val foundTaz = TAZTreeMap.ringSearch(
            tazQuadTree,
            linkEndCoord,
            100,
            1000000,
            radiusMultiplication = 1.5
          ) { taz =>
            if (taz.geometry.exists(_.contains(GeometryUtils.createGeotoolsPoint(linkEndCoord)))) { Some(taz) }
            else None
          }
          foundTaz match {
            case Some(taz) if link.getAllowedModes.contains("car") & link.getAllowedModes.contains("walk") =>
              try {
                tazToLinkIdMapping(taz.tazId).put(linkMidpoint.getX, linkMidpoint.getY, link)
              } catch {
                case e: Throwable =>
                  unmatchedLinkIds += id
                  logger.warn(e.toString)
              }
              linkIdToTAZMapping += (id -> taz.tazId)
            case None =>
              unmatchedLinkIds += id
            case _ =>
          }
        case _ =>
      }
      val linksToTazMapping = tazToLinkIdMapping
        .map { case (x, y) => (x, y.size()) }
        .groupBy(x => Math.min(x._2, 10))
        .map { case (x, y) =>
          (x, y.keys.map(_.toString))
        }
        .toSeq
        .sortBy(_._1)
      logger.info(
        "Completed mapping links to TAZs. Matched "
        + linkIdToTAZMapping.size.toString +
        " links, failed to match "
        + unmatchedLinkIds.size.toString +
        " links"
      )
      logger.debug(s"Mapping of links to TAZs: ${linksToTazMapping}")
    }
  }
}

object TAZTreeMap {

  private val logger = LoggerFactory.getLogger(this.getClass)

  val emptyTAZId: Id[TAZ] = Id.create("NA", classOf[TAZ])
  private val mapBoundingBoxBufferMeters: Double = 2e4 // Some links also extend beyond the convex hull of the TAZs

  def fromShapeFile(shapeFilePath: String, tazIDFieldName: String): TAZTreeMap = {
    val (quadTree, mapping) = initQuadTreeFromFile(shapeFilePath, tazIDFieldName)
    new TAZTreeMap(quadTree, maybeZoneOrdering = Some(mapping))
  }

  private def initQuadTreeFromFile(filePath: String, tazIDFieldName: String): (QuadTree[TAZ], Seq[Id[TAZ]]) = {
    val features: util.Collection[SimpleFeature] = GeoReader.readFeatures(filePath)
    val quadTreeBounds: QuadTreeBounds = quadTreeExtentFromFeatures(features)
    val mapping = features.asScala.map(x => Id.create(x.getAttribute(tazIDFieldName).toString, classOf[TAZ])).toSeq

    val tazQuadTree: QuadTree[TAZ] = new QuadTree[TAZ](
      quadTreeBounds.minx - mapBoundingBoxBufferMeters,
      quadTreeBounds.miny - mapBoundingBoxBufferMeters,
      quadTreeBounds.maxx + mapBoundingBoxBufferMeters,
      quadTreeBounds.maxy + mapBoundingBoxBufferMeters
    )

    for (f <- features.asScala) {
      f.getDefaultGeometry match {
        case g: Geometry =>
          val taz = new TAZ(
            String.valueOf(f.getAttribute(tazIDFieldName)),
            new Coord(g.getCoordinate.x, g.getCoordinate.y),
            g.getArea,
            Some(g)
          )
          tazQuadTree.put(taz.coord.getX, taz.coord.getY, taz)
      }
    }
    (tazQuadTree, mapping)
  }

  private def quadTreeExtentFromFeatures(
    features: util.Collection[SimpleFeature]
  ): QuadTreeBounds = {
    val envelopes =
      features.asScala.map(_.getDefaultGeometry).collect { case g: Geometry => g.getEnvelope.getEnvelopeInternal }
    ShapeUtils.quadTreeBounds(envelopes)
  }

  private def quadTreeExtentFromCsvFile(lines: Seq[CsvTaz]): QuadTreeBounds = {
    implicit val hasQuadBounds: HasQuadBounds[CsvTaz] = new HasQuadBounds[CsvTaz] {
      override def getMinX(a: CsvTaz): Double = a.coordX

      override def getMaxX(a: CsvTaz): Double = a.coordX

      override def getMinY(a: CsvTaz): Double = a.coordY

      override def getMaxY(a: CsvTaz): Double = a.coordY
    }
    ShapeUtils.quadTreeBounds(lines)
  }

  private def quadTreeExtentFromList(lines: Seq[TAZ]): QuadTreeBounds = {
    ShapeUtils.quadTreeBounds(lines.map(_.coord))
  }

  def fromCsv(csvFile: String): TAZTreeMap = {
    val lines: Seq[CsvTaz] = CsvTaz.readCsvFile(csvFile)
    val quadTreeBounds: QuadTreeBounds = quadTreeExtentFromCsvFile(lines)
    val tazQuadTree: QuadTree[TAZ] = new QuadTree[TAZ](
      quadTreeBounds.minx - mapBoundingBoxBufferMeters,
      quadTreeBounds.miny - mapBoundingBoxBufferMeters,
      quadTreeBounds.maxx + mapBoundingBoxBufferMeters,
      quadTreeBounds.maxy + mapBoundingBoxBufferMeters
    )

    for (l <- lines) {
      val taz = new TAZ(l.id, new Coord(l.coordX, l.coordY), l.area)
      tazQuadTree.put(taz.coord.getX, taz.coord.getY, taz)
    }

    new TAZTreeMap(tazQuadTree)

  }

  def fromSeq(tazes: Seq[TAZ]): TAZTreeMap = {
    val quadTreeBounds: QuadTreeBounds = quadTreeExtentFromList(tazes)
    val tazQuadTree: QuadTree[TAZ] = new QuadTree[TAZ](
      quadTreeBounds.minx - mapBoundingBoxBufferMeters,
      quadTreeBounds.miny - mapBoundingBoxBufferMeters,
      quadTreeBounds.maxx + mapBoundingBoxBufferMeters,
      quadTreeBounds.maxy + mapBoundingBoxBufferMeters
    )

    for (taz <- tazes) {
      tazQuadTree.put(taz.coord.getX, taz.coord.getY, taz)
    }

    new TAZTreeMap(tazQuadTree)
  }

  def getTazTreeMap(filePath: String, tazIDFieldName: Option[String] = None): TAZTreeMap = {
    try {
      if (filePath.endsWith(".shp")) {
        TAZTreeMap.fromShapeFile(filePath, tazIDFieldName.get)
      } else {
        TAZTreeMap.fromCsv(filePath)
      }

    } catch {
      case fe: FileNotFoundException =>
        logger.error("No TAZ file found at given file path (using defaultTazTreeMap): %s" format filePath, fe)
        defaultTazTreeMap
      case e: Exception =>
        logger.error(
          "Exception occurred while reading from CSV file from path (using defaultTazTreeMap): %s" format e.getMessage,
          e
        )
        defaultTazTreeMap
    }
  }

  val defaultTazTreeMap: TAZTreeMap = {
    val tazQuadTree: QuadTree[TAZ] = new QuadTree(-1, -1, 1, 1)
    val taz = new TAZ("0", new Coord(0.0, 0.0), 0.0)
    tazQuadTree.put(taz.coord.getX, taz.coord.getY, taz)
    new TAZTreeMap(tazQuadTree)
  }

  def randomLocationInTAZ(
    taz: TAZ,
    rand: scala.util.Random
  ): Coord = {
    val radius = Math.sqrt(taz.areaInSquareMeters / Math.PI) / 2
    val a = 2 * Math.PI * rand.nextDouble()
    val r = radius * Math.sqrt(rand.nextDouble())
    val x = r * Math.cos(a)
    val y = r * Math.sin(a)
    new Coord(taz.coord.getX + x, taz.coord.getY + y)
  }

  def randomLocationInTAZ(
    taz: TAZ,
    rand: scala.util.Random,
    snapLocationHelper: SnapLocationHelper
  ): Coord = {
    val tazId = taz.tazId.toString
    val max = 10000
    var counter = 0
    var split: Coord = null
    while (split == null && counter < max) {
      snapLocationHelper.computeResult(randomLocationInTAZ(taz, rand)) match {
        case Right(splitCoord) =>
          split = splitCoord
        case _ =>
      }
      counter += 1
    }

    if (split == null) {
      val loc = randomLocationInTAZ(taz, rand)
      logger.warn(
        s"Could not found valid location within taz $tazId even in $max attempts. Creating one anyway $loc."
      )
      split = loc
    }

    split
  }

  /**
    * performs a concentric ring search from the present location to find elements up to the SearchMaxRadius
    * @param quadTree tree to search
    * @param searchCenter central location from which concentric discs will be built with an expanding radius
    * @param startRadius the beginning search radius
    * @param maxRadius search constrained to this maximum search radius
    * @param f function to check the elements. It must return Some if found an appropriate element and None otherwise.
    * @return the result of function f applied to the found element. None if there's no appropriate elements.
    */
  def ringSearch[A, B](
    quadTree: QuadTree[A],
    searchCenter: Coord,
    startRadius: Double,
    maxRadius: Double,
    radiusMultiplication: Double
  )(f: A => Option[B]): Option[B] = {

    @tailrec
    def _find(innerRadius: Double, outerRadius: Double): Option[B] = {
      if (innerRadius > maxRadius) None
      else {
        val elementStream = quadTree
          .getRing(searchCenter.getX, searchCenter.getY, innerRadius, outerRadius)
          .asScala
          .toStream
        val result = elementStream.flatMap(f(_)).headOption
        if (result.isDefined) result
        else _find(outerRadius, outerRadius * radiusMultiplication)
      }
    }

    _find(0.0, startRadius)
  }

}
