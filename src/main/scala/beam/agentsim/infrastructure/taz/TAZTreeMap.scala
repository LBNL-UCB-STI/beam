package beam.agentsim.infrastructure.taz

import beam.agentsim.infrastructure.taz.TAZTreeMap.logger
import beam.sim.config.BeamConfig
import beam.utils.SnapCoordinateUtils.SnapLocationHelper
import beam.utils.geospatial.GeoReader
import beam.utils.matsim_conversion.ShapeUtils
import beam.utils.matsim_conversion.ShapeUtils.{HasQuadBounds, QuadTreeBounds}
import beam.utils.{FileUtils, SortingUtil}
import org.locationtech.jts.geom.Geometry
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.GeometryUtils
import org.matsim.core.utils.io.IOUtils
import org.opengis.feature.simple.SimpleFeature
import org.slf4j.LoggerFactory
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference
import beam.sim.config.BeamConfig.Beam.Exchange.Output.ActivitySimSkimmer.Secondary.Taz.TazMapping

import java.io._
import java.util
import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.util.Using

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
  val tazToTazMapping: mutable.HashMap[Id[TAZ], Id[TAZ]] = mutable.HashMap.empty[Id[TAZ], Id[TAZ]]

  def getTAZfromLink(linkId: Id[Link]): Option[TAZ] = {
    linkIdToTAZMapping.get(linkId) match {
      case Some(tazId) => getTAZ(tazId)
      case _ =>
        failedLinkLookups.append(linkId)
        None
    }
  }

  def getSize: Int = tazQuadTree.size()

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

  def getMappedGeoId(tazId: String): Option[String] = {
    stringIdToTAZMapping.get(tazId) match {
      case Some(taz) =>
        tazToTazMapping.get(taz.tazId).map(_.toString).orElse {
          logger.error(s"TAZ $tazId is not mapped to a secondary TAZ Id, check beam.exchange.output")
          None
        }
      case _ =>
        logger.error(s"The queried TAZ $tazId for mapping was not found!")
        None
    }
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
      logger.debug(s"Mapping of links to TAZs: $linksToTazMapping")
    }
  }
}

object TAZTreeMap {

  private val logger = LoggerFactory.getLogger(this.getClass)

  val emptyTAZId: Id[TAZ] = Id.create("NA", classOf[TAZ])
  private val mapBoundingBoxBufferMeters: Double = 2e4 // Some links also extend beyond the convex hull of the TAZs

  private def fromGeoFile(shapeFilePath: String, tazIDFieldName: String): TAZTreeMap = {
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
            Some(g),
            f.getProperties.asScala
              .find(_.getName.toString.toLowerCase.contains("county"))
              .map(_.getValue.toString.toLowerCase) // Added county attribute to TAZ
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
      val taz = new TAZ(l.id, new Coord(l.coordX, l.coordY), l.area, county = Some(l.county))
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

  def getSecondaryTazTreeMap(
    taz2Config: BeamConfig.Beam.Exchange.Output.ActivitySimSkimmer.Secondary.Taz,
    taz1Config: BeamConfig.Beam.Agentsim.Taz,
    tazMap: TAZTreeMap
  ): Option[TAZTreeMap] = {
    val maybeTaz2Map: Option[TAZTreeMap] =
      try {
        if (taz2Config.filePath.endsWith(".shp") || taz2Config.filePath.endsWith(".geojson")) {
          val (quadTree, mapping) =
            initQuadTreeFromFile(taz2Config.filePath, taz2Config.tazIdFieldName)
          Some(new TAZTreeMap(quadTree, maybeZoneOrdering = Some(mapping)))
        } else {
          Some(TAZTreeMap.fromCsv(taz2Config.filePath))
        }
      } catch {
        case fe: FileNotFoundException =>
          logger.error("No secondary TAZ file found at given file path: %s" format taz2Config.filePath, fe)
          None
        case e: Exception =>
          logger.error("Exception while reading secondary TAZ from CSV file from path: %s" format e.getMessage, e)
          None
      }

    maybeTaz2Map.foreach { taz2Map =>
      taz2Config.tazMapping match {
        case Some(TazMapping(filePath, geoIdFieldNameKey, geoIdFieldNameValue)) if filePath.trim.nonEmpty =>
          val isMappingIncomplete = geoIdFieldNameKey.trim.isEmpty || geoIdFieldNameValue.trim.isEmpty
          val isKeyMatchingSecondaryTazIdField = geoIdFieldNameKey == taz2Config.tazIdFieldName
          val isTaz2MapLargerThanTazMap = taz2Map.getSize > tazMap.getSize

          val (indexTazMap, indexTazFieldName, mappedTazFieldName) =
            if (!isMappingIncomplete && isKeyMatchingSecondaryTazIdField) {
              (taz2Map, geoIdFieldNameKey, geoIdFieldNameValue)
            } else if (!isMappingIncomplete) {
              (tazMap, geoIdFieldNameKey, geoIdFieldNameValue)
            } else if (isTaz2MapLargerThanTazMap) {
              (taz2Map, taz2Config.tazIdFieldName, taz1Config.tazIdFieldName)
            } else {
              (tazMap, taz1Config.tazIdFieldName, taz2Config.tazIdFieldName)
            }

          readTazToTazMapCSVFile(indexTazMap, filePath, indexTazFieldName, mappedTazFieldName)
        case _ =>
          logger.warn("Instead we are generating a zonal mapping on the fly")
          mapTAZToTAZ(taz2Map, tazMap)
      }
    }

    maybeTaz2Map
  }

  def getTazTreeMap(filePath: String, tazIDFieldName: Option[String] = None): TAZTreeMap = {
    try {
      if (filePath.endsWith(".shp") || filePath.endsWith(".geojson")) {
        TAZTreeMap.fromGeoFile(filePath, tazIDFieldName.get)
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

  private val defaultTazTreeMap: TAZTreeMap = {
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

  private def readTazToTazMapCSVFile(
    indexTazMap: TAZTreeMap,
    filePath: String,
    indexTazFieldName: String,
    mappedTazFieldName: String
  ): Unit = {
    Using(new CsvMapReader(FileUtils.readerFromFile(filePath), CsvPreference.STANDARD_PREFERENCE)) { mapReader =>
      // Read the header to understand column positions.
      val header = mapReader.getHeader(true)
      // Ensure the header contains the necessary fields
      if (header.contains(indexTazFieldName) && header.contains(mappedTazFieldName)) {
        var line: java.util.Map[String, String] = mapReader.read(header: _*)
        while (line != null) {
          val geoIdKey = line.get(indexTazFieldName)
          val geoIdValue = line.get(mappedTazFieldName)
          if (geoIdKey != null && geoIdValue != null) {
            indexTazMap.tazToTazMapping.put(Id.create(geoIdKey, classOf[TAZ]), Id.create(geoIdValue, classOf[TAZ]))
          }
          line = mapReader.read(header: _*)
        }
      } else {
        logger.error(
          s"Required columns $indexTazFieldName and $mappedTazFieldName not found in geoId2TazIdMapFilePath: $filePath."
        )
      }
    }.recover { case e: Exception =>
      logger.error(s"Issue with reading $filePath: ${e.getMessage}", e)
    }
  }

  private def mapTAZToTAZ(taz2Map: TAZTreeMap, tazMap: TAZTreeMap): Unit = {
    // Determine which map to use as the index based on size
    val (indexTazMap, mappedTazMap) = if (taz2Map.getSize > tazMap.getSize) (taz2Map, tazMap) else (tazMap, taz2Map)

    // Iterate through the TAZs in the larger map
    indexTazMap.getTAZs
      .filter(_.geometry.isDefined) // Ensures that we have a geometry to work with
      .foreach { indexTaz =>
        val potentialTazToMap = mappedTazMap
          .getTAZInRadius(indexTaz.coord, 50000) // within 50km
          .asScala
          .filter(_.geometry.isDefined)
        val maxIntersectionAreaTaz = potentialTazToMap
          .map { mappedTaz =>
            val intersectionArea = indexTaz.geometry.get.intersection(mappedTaz.geometry.get).getArea
            (mappedTaz, intersectionArea)
          }
          .filter(_._2 > 0)
          .reduceOption { (pair1, pair2) =>
            if (pair1._2 > pair2._2) pair1 else pair2
          }
        // Update the mapping for the TAZ with the largest intersection area
        maxIntersectionAreaTaz.foreach { case (mappedTaz, _) =>
          indexTazMap.tazToTazMapping.put(indexTaz.tazId, mappedTaz.tazId)
        }
      }
  }
}
