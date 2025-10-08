package beam.agentsim.infrastructure.taz

import beam.agentsim.infrastructure.taz.TAZTreeMap.logger
import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Exchange.Output.ActivitySimSkimmer.Secondary.Taz.TazMapping
import beam.utils.SnapCoordinateUtils.SnapLocationHelper
import beam.utils.geospatial.GeoReader
import beam.utils.{FileUtils, SortingUtil}
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.locationtech.jts.geom.{Coordinate, Geometry, GeometryFactory}
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
import org.opengis.referencing.operation.MathTransform
import org.slf4j.LoggerFactory
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

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
  val scenarioCRS: String,
  val useCache: Boolean = false,
  private val maybeZoneOrdering: Option[Seq[Id[TAZ]]] = None
) extends BasicEventHandler
    with IterationEndsListener {

  private val stringIdToTAZMapping: mutable.HashMap[String, TAZ] = mutable.HashMap()
  val idToTAZMapping: mutable.HashMap[Id[TAZ], TAZ] = mutable.HashMap()

  // Cache for TAZ lookup results
  private val cache: TrieMap[(Double, Double), TAZ] = TrieMap()

  val linkIdToTAZMapping: mutable.HashMap[Id[Link], Id[TAZ]] = mutable.HashMap.empty[Id[Link], Id[TAZ]]

  val tazToLinkIdMapping: mutable.HashMap[Id[TAZ], QuadTree[Link]] =
    mutable.HashMap.empty[Id[TAZ], QuadTree[Link]]

  // Coordinate transformation from scenarioCRS to internalCRS
  private val transform: Option[MathTransform] = if (scenarioCRS != TAZTreeMap.internalCRS) {
    try {
      val sourceCRS = CRS.decode(scenarioCRS)
      val targetCRS = CRS.decode(TAZTreeMap.internalCRS)
      Some(CRS.findMathTransform(sourceCRS, targetCRS, true))
    } catch {
      case e: Exception =>
        logger.error(s"Failed to create coordinate transformation from $scenarioCRS to ${TAZTreeMap.internalCRS}", e)
        None
    }
  } else {
    None
  }

  private val geometryFactory = new GeometryFactory()

  // Cache for coordinate transformations (separate from TAZ lookup cache)
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

  private def transformCoord(coord: Coord): Coord = {
    val (x, y) = transformCoord(coord.getX, coord.getY)
    new Coord(x, y)
  }

  // adding this as an alternative to tazQuadTree: QuadTree[TAZ]
  // it should be activated with TODO TBD
  var linkQuadTree: Option[QuadTree[Link]] = None

  private val unmatchedLinkIds: mutable.ListBuffer[Id[Link]] = mutable.ListBuffer.empty[Id[Link]]
  lazy val tazListContainsGeoms: Boolean = tazQuadTree.values().asScala.headOption.exists(_.geometry.isDefined)
  private val failedLinkLookups: mutable.ListBuffer[Id[Link]] = mutable.ListBuffer.empty[Id[Link]]

  private lazy val sortedTazIds: Seq[String] = {
    val tazIds = tazQuadTree.values().asScala.map(_.tazId.toString).toSeq
    SortingUtil.sortAsIntegers(tazIds).getOrElse(tazIds.sorted)
  }

  val orderedTazIds: Seq[String] = maybeZoneOrdering match {
    case Some(ordering) =>
      // Sort by the numeric value of the TAZ ID
      val sorted = ordering.map(_.toString).sortBy(_.toInt)
      sorted
    case None =>
      sortedTazIds
  }
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
    val transformed = transformCoord(loc)
    getTAZ(transformed.getX, transformed.getY)
  }

  def getTAZ(x: Double, y: Double): TAZ = {
    val (transformedX, transformedY) = transformCoord(x, y)
    if (useCache) {
      cache.getOrElseUpdate((transformedX, transformedY), tazQuadTree.getClosest(transformedX, transformedY))
    } else {
      tazQuadTree.getClosest(transformedX, transformedY)
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
    val (transformedX, transformedY) = transformCoord(x, y)
    tazQuadTree.getDisk(transformedX, transformedY, radius)
  }

  def getTAZInRadius(loc: Coord, radius: Double): util.Collection[TAZ] = {
    val transformed = transformCoord(loc)
    tazQuadTree.getDisk(transformed.getX, transformed.getY, radius)
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
          case e: Throwable => logger.warn(s"Error: ${e.getMessage}. Could not write link $linkId")
        }
      }
      writer.flush()
      writer.close()
    }
    failedLinkLookups.clear()
  }

  def mapNetworkToTAZs(network: Network, buildLinkQuadTree: Boolean = false): Unit = {
    if (tazListContainsGeoms) {
      // Initialize the global link quad tree
      if (buildLinkQuadTree) {
        linkQuadTree = Some(
          new QuadTree[Link](
            tazQuadTree.getMinEasting,
            tazQuadTree.getMinNorthing,
            tazQuadTree.getMaxEasting,
            tazQuadTree.getMaxNorthing
          )
        )
      }

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
          // Transform link coordinates from scenarioCRS to internalCRS (using cached transformation)
          val linkEndCoord = transformCoord(link.getToNode.getCoord)
          val linkFromCoord = transformCoord(link.getFromNode.getCoord)
          val linkMidpoint = new Coord(
            0.5 * (linkEndCoord.getX + linkFromCoord.getX),
            0.5 * (linkEndCoord.getY + linkFromCoord.getY)
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
                linkQuadTree.foreach(_.put(linkMidpoint.getX, linkMidpoint.getY, link))
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
      logger.info(s"Created linkQuadTree with ${linkQuadTree.map(_.size()).getOrElse(0)} links")
      logger.debug(s"Mapping of links to TAZs: $linksToTazMapping")
    }
  }
}

object TAZTreeMap {

  private val logger = LoggerFactory.getLogger(this.getClass)
  val internalCRS: String = "epsg:4326" // WGS 84 - lat/lon

  val emptyTAZId: Id[TAZ] = Id.create("NA", classOf[TAZ])
  private val mapBoundingBoxBufferMeters: Double = 2e4 // Some links also extend beyond the convex hull of the TAZs

  // Helper function to transform a single coordinate
  def transformSingleCoord(
    x: Double,
    y: Double,
    transform: Option[MathTransform],
    geometryFactory: GeometryFactory
  ): (Double, Double) = {
    transform match {
      case Some(t) =>
        val point = geometryFactory.createPoint(new Coordinate(x, y))
        val transformed = JTS.transform(point, t)
        val coord = transformed.getCoordinate
        (coord.x, coord.y)
      case None =>
        (x, y)
    }
  }

  // Helper function to create transformation and process coordinates in a single pass
  private def transformAndCalculateBounds[T](
    sourceCRS: String,
    items: Seq[T],
    extractCoords: T => Seq[(Double, Double)]
  ): (Option[MathTransform], GeometryFactory, Seq[T], Double, Double, Double, Double) = {
    import org.geotools.referencing.CRS
    import org.locationtech.jts.geom.GeometryFactory
    import org.opengis.referencing.operation.MathTransform

    // Create coordinate transformation if needed
    val transform: Option[MathTransform] = if (sourceCRS != internalCRS) {
      try {
        val sourceCRSObj = CRS.decode(sourceCRS)
        val targetCRS = CRS.decode(internalCRS)
        Some(CRS.findMathTransform(sourceCRSObj, targetCRS, true))
      } catch {
        case e: Exception =>
          logger.error(s"Failed to create coordinate transformation from $sourceCRS to $internalCRS", e)
          None
      }
    } else {
      None
    }

    val geometryFactory = new GeometryFactory()

    // Calculate bounds
    var minX = Double.MaxValue
    var maxX = Double.MinValue
    var minY = Double.MaxValue
    var maxY = Double.MinValue

    items.foreach { item =>
      extractCoords(item).foreach { case (x, y) =>
        val (transformedX, transformedY) = transformSingleCoord(x, y, transform, geometryFactory)
        if (transformedX < minX) minX = transformedX
        if (transformedX > maxX) maxX = transformedX
        if (transformedY < minY) minY = transformedY
        if (transformedY > maxY) maxY = transformedY
      }
    }

    (transform, geometryFactory, items, minX, maxX, minY, maxY)
  }

  private def fromGeoFile(shapeFilePath: String, tazIDFieldName: String, fallbackCRS: String): TAZTreeMap = {
    import org.geotools.data.shapefile.ShapefileDataStore
    import org.geotools.referencing.CRS
    val dataStore = new ShapefileDataStore(new File(shapeFilePath).toURI.toURL)
    val crs = dataStore.getSchema.getCoordinateReferenceSystem

    val epsgCode: String = Option(CRS.lookupEpsgCode(crs, true))
      .map(code => s"EPSG:$code")
      .getOrElse {
        logger.warn(s"Could not determine EPSG from $shapeFilePath, using fallback: $fallbackCRS")
        fallbackCRS
      }

    val (quadTree, mapping) = initQuadTreeFromFile(shapeFilePath, tazIDFieldName, epsgCode)
    new TAZTreeMap(quadTree, epsgCode, maybeZoneOrdering = Some(mapping))
  }

  private def initQuadTreeFromFile(
    filePath: String,
    tazIDFieldName: String,
    epsgCode: String
  ): (QuadTree[TAZ], Seq[Id[TAZ]]) = {
    import org.geotools.geometry.jts.JTS

    logger.info(s"Source coordinate system: $epsgCode")
    logger.info(s"Target coordinate system: $internalCRS")

    val features: util.Collection[SimpleFeature] = GeoReader.readFeatures(filePath)
    val featureSeq = features.asScala.toSeq

    // Extract geometries for bounds calculation
    val (transform, _, _, minX, maxX, minY, maxY) =
      transformAndCalculateBounds(
        epsgCode,
        featureSeq,
        (feature: SimpleFeature) => {
          val geom = feature.getDefaultGeometry.asInstanceOf[Geometry]
          val env = geom.getEnvelopeInternal
          Seq(
            (env.getMinX, env.getMinY),
            (env.getMaxX, env.getMaxY)
          )
        }
      )

    // Transform geometries
    val transformedFeatures = featureSeq.map { feature =>
      val g = feature.getDefaultGeometry.asInstanceOf[Geometry]
      val transformedGeom = transform match {
        case Some(t) => JTS.transform(g, t)
        case None    => g
      }
      (feature, transformedGeom)
    }.toIndexedSeq

    val tazQuadTree: QuadTree[TAZ] = new QuadTree[TAZ](
      minX - mapBoundingBoxBufferMeters,
      minY - mapBoundingBoxBufferMeters,
      maxX + mapBoundingBoxBufferMeters,
      maxY + mapBoundingBoxBufferMeters
    )

    // Create TAZ objects from transformed features
    val mapping = transformedFeatures.map { case (feature, transformedGeom) =>
      val tazId = feature.getAttribute(tazIDFieldName).toString
      val coord = transformedGeom.getCoordinate
      val taz = new TAZ(
        tazId,
        new Coord(coord.x, coord.y),
        transformedGeom.getArea,
        Some(transformedGeom),
        feature.getProperties.asScala
          .find(_.getName.toString.toLowerCase.contains("county"))
          .map(_.getValue.toString.toLowerCase)
      )
      tazQuadTree.put(coord.x, coord.y, taz)

      // Return the TAZ ID for ordering
      Id.create(tazId, classOf[TAZ])
    }

    logger.info(s"Loaded ${mapping.length} TAZ zones from shapefile in file order")
    logger.info(s"First 10 TAZ IDs in file order: ${mapping.take(10).map(_.toString).mkString(", ")}")
    logger.info(s"These will map to ActivitySim TAZ IDs 1 through ${mapping.length}")

    (tazQuadTree, mapping)
  }

  def fromCsv(csvFile: String, sourceCRS: String): TAZTreeMap = {
    logger.info(s"Source coordinate system: $sourceCRS")
    logger.info(s"Target coordinate system: $internalCRS")

    val lines: Seq[CsvTaz] = CsvTaz.readCsvFile(csvFile)

    val (transform, geometryFactory, _, minX, maxX, minY, maxY) =
      transformAndCalculateBounds(
        sourceCRS,
        lines,
        (csvTaz: CsvTaz) => Seq((csvTaz.coordX, csvTaz.coordY))
      )

    val transformedData = lines.map { l =>
      val (x, y) = transformSingleCoord(l.coordX, l.coordY, transform, geometryFactory)
      (l, x, y)
    }

    val tazQuadTree: QuadTree[TAZ] = new QuadTree[TAZ](
      minX - mapBoundingBoxBufferMeters,
      minY - mapBoundingBoxBufferMeters,
      maxX + mapBoundingBoxBufferMeters,
      maxY + mapBoundingBoxBufferMeters
    )

    transformedData.foreach { case (l, x, y) =>
      val taz = new TAZ(l.id, new Coord(x, y), l.area, county = Some(l.county))
      tazQuadTree.put(x, y, taz)
    }

    logger.info(s"Loaded ${lines.length} TAZ zones from CSV")
    new TAZTreeMap(tazQuadTree, internalCRS)
  }

  def fromSeq(tazes: Seq[TAZ], sourceCRS: String): TAZTreeMap = {
    import org.geotools.geometry.jts.JTS

    val (transform, geometryFactory, _, minX, maxX, minY, maxY) =
      transformAndCalculateBounds(sourceCRS, tazes, (taz: TAZ) => Seq((taz.coord.getX, taz.coord.getY)))

    val transformedTazes = tazes.map { taz =>
      val (x, y) = transformSingleCoord(taz.coord.getX, taz.coord.getY, transform, geometryFactory)

      // Transform geometry if present
      val transformedGeometry = taz.geometry.map { geom =>
        transform match {
          case Some(t) => JTS.transform(geom, t)
          case None    => geom
        }
      }

      new TAZ(
        taz.tazId.toString,
        new Coord(x, y),
        taz.areaInSquareMeters,
        transformedGeometry,
        taz.county
      )
    }

    val tazQuadTree: QuadTree[TAZ] = new QuadTree[TAZ](
      minX - mapBoundingBoxBufferMeters,
      minY - mapBoundingBoxBufferMeters,
      maxX + mapBoundingBoxBufferMeters,
      maxY + mapBoundingBoxBufferMeters
    )

    transformedTazes.foreach { taz =>
      tazQuadTree.put(taz.coord.getX, taz.coord.getY, taz)
    }

    logger.info(s"Created TAZTreeMap from ${tazes.length} TAZ zones")
    new TAZTreeMap(tazQuadTree, internalCRS)
  }

  def fromLinks(links: Seq[Link], sourceCRS: String): QuadTree[Link] = {
    if (links.isEmpty) {
      return new QuadTree[Link](-1, -1, 1, 1)
    }

    val (transform, geometryFactory, _, minX, maxX, minY, maxY) =
      transformAndCalculateBounds(
        sourceCRS,
        links,
        (link: Link) =>
          Seq(
            (link.getFromNode.getCoord.getX, link.getFromNode.getCoord.getY),
            (link.getToNode.getCoord.getX, link.getToNode.getCoord.getY)
          )
      )

    val linkMidpoints = links.map { link =>
      val (fromX, fromY) = transformSingleCoord(
        link.getFromNode.getCoord.getX,
        link.getFromNode.getCoord.getY,
        transform,
        geometryFactory
      )
      val (toX, toY) = transformSingleCoord(
        link.getToNode.getCoord.getX,
        link.getToNode.getCoord.getY,
        transform,
        geometryFactory
      )

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

  def getSecondaryTazTreeMap(
    taz2Config: BeamConfig.Beam.Exchange.Output.ActivitySimSkimmer.Secondary.Taz,
    taz1Config: BeamConfig.Beam.Agentsim.Taz,
    tazMap: TAZTreeMap
  ): Option[TAZTreeMap] = {
    val maybeTaz2Map: Option[TAZTreeMap] =
      try {
        if (taz2Config.filePath.endsWith(".shp") || taz2Config.filePath.endsWith(".geojson")) {
          // Extract CRS from shapefile/geojson with fallback
          import org.geotools.data.shapefile.ShapefileDataStore
          import org.geotools.referencing.CRS

          val dataStore = new ShapefileDataStore(new File(taz2Config.filePath).toURI.toURL)
          val epsgCode: String =
            try {
              val crs = dataStore.getSchema.getCoordinateReferenceSystem
              Option(crs)
                .flatMap(c => Option(CRS.lookupEpsgCode(c, true)))
                .map(code => s"EPSG:$code")
                .getOrElse {
                  logger.warn(
                    s"Could not determine EPSG from ${taz2Config.filePath}, using fallback: ${tazMap.scenarioCRS}"
                  )
                  tazMap.scenarioCRS
                }
            } finally {
              dataStore.dispose()
            }

          val (quadTree, mapping) = initQuadTreeFromFile(
            taz2Config.filePath,
            taz2Config.tazIdFieldName,
            epsgCode
          )
          Some(new TAZTreeMap(quadTree, internalCRS, maybeZoneOrdering = Some(mapping)))
        } else {
          // For CSV files, use the fallback CRS
          Some(TAZTreeMap.fromCsv(taz2Config.filePath, tazMap.scenarioCRS))
        }
      } catch {
        case fe: FileNotFoundException =>
          logger.error(s"No secondary TAZ file found at given file path: ${taz2Config.filePath}", fe)
          None
        case e: Exception =>
          logger.error(s"Exception while reading secondary TAZ from file: ${e.getMessage}", e)
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

  def getTazTreeMap(filePath: String, sourceCRS: String, tazIDFieldName: Option[String] = None): TAZTreeMap = {
    try {
      if (filePath.endsWith(".shp") || filePath.endsWith(".geojson")) {
        TAZTreeMap.fromGeoFile(filePath, tazIDFieldName.get, sourceCRS)
      } else {
        TAZTreeMap.fromCsv(filePath, sourceCRS)
      }
    } catch {
      case fe: FileNotFoundException =>
        logger.error("No TAZ file found at given file path (using defaultTazTreeMap): %s" format filePath, fe)
        defaultTazTreeMap(sourceCRS)
      case e: Exception =>
        logger.error(
          "Exception occurred while reading from CSV file from path (using defaultTazTreeMap): %s" format e.getMessage,
          e
        )
        defaultTazTreeMap(sourceCRS)
    }
  }

  private def defaultTazTreeMap(sourceCRS: String): TAZTreeMap = {
    val tazQuadTree: QuadTree[TAZ] = new QuadTree(-1, -1, 1, 1)
    val taz = new TAZ("0", new Coord(0.0, 0.0), 0.0)
    tazQuadTree.put(taz.coord.getX, taz.coord.getY, taz)
    new TAZTreeMap(tazQuadTree, sourceCRS)
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
    allLinks: Iterable[Link]
  ): Coord = {
    if (allLinks.isEmpty) {
      randomLocationInTAZ(taz, rand)
    } else {
      val totalLength = allLinks.foldRight(0.0)(_.getLength + _)
      var currentLength = 0.0
      val stopAt = rand.nextDouble() * totalLength
      allLinks
        .takeWhile { lnk =>
          currentLength += lnk.getLength
          currentLength <= stopAt
        }
        .lastOption
        .map(_.getCoord)
        .getOrElse(allLinks.head.getCoord)
    }
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
