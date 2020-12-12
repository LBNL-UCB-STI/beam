package beam.agentsim.infrastructure.taz

import java.io._
import java.util
import scala.collection.JavaConverters._
import scala.collection.mutable
import beam.utils.matsim_conversion.ShapeUtils
import beam.utils.matsim_conversion.ShapeUtils.{HasQuadBounds, QuadTreeBounds}
import com.vividsolutions.jts.geom.Geometry
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.gis.ShapeFileReader
import org.opengis.feature.simple.SimpleFeature
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap

/**
  * TAZTreeMap manages a quadTree to find the closest TAZ to any coordinate.
  *
  * @param tazQuadTree quadtree containing the TAZs
  * @param useCache Currently [as of 10-2020] the use of the TAZ quadtree cache is less performant than just keeping it off (better to reduce calls to TAZ quadtree
  *                 by avoiding unnecessary queries). The caching mechanism is however still useful for debugging and as a quickfix/confirmation if TAZ quadtree queries
  *                 suddenly increase due to code change.
  */
class TAZTreeMap(val tazQuadTree: QuadTree[TAZ], val useCache: Boolean = false) {

  private val stringIdToTAZMapping: mutable.HashMap[String, TAZ] = mutable.HashMap()
  val idToTAZMapping: mutable.HashMap[Id[TAZ], TAZ] = mutable.HashMap()
  private val cache: TrieMap[(Double, Double), TAZ] = TrieMap()

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
}

object TAZTreeMap {

  private val logger = LoggerFactory.getLogger(this.getClass)

  val emptyTAZId: Id[TAZ] = Id.create("NA", classOf[TAZ])

  def fromShapeFile(shapeFilePath: String, tazIDFieldName: String): TAZTreeMap = {
    new TAZTreeMap(initQuadTreeFromShapeFile(shapeFilePath, tazIDFieldName))
  }

  private def initQuadTreeFromShapeFile(
    shapeFilePath: String,
    tazIDFieldName: String
  ): QuadTree[TAZ] = {
    val shapeFileReader: ShapeFileReader = new ShapeFileReader
    shapeFileReader.readFileAndInitialize(shapeFilePath)
    val features: util.Collection[SimpleFeature] = shapeFileReader.getFeatureSet
    val quadTreeBounds: QuadTreeBounds = quadTreeExtentFromShapeFile(features)

    val tazQuadTree: QuadTree[TAZ] = new QuadTree[TAZ](
      quadTreeBounds.minx,
      quadTreeBounds.miny,
      quadTreeBounds.maxx,
      quadTreeBounds.maxy
    )

    for (f <- features.asScala) {
      f.getDefaultGeometry match {
        case g: Geometry =>
          val taz = new TAZ(
            f.getAttribute(tazIDFieldName).asInstanceOf[String],
            new Coord(g.getCoordinate.x, g.getCoordinate.y),
            g.getArea
          )
          tazQuadTree.put(taz.coord.getX, taz.coord.getY, taz)
        case _ =>
      }
    }
    tazQuadTree
  }

  private def quadTreeExtentFromShapeFile(
    features: util.Collection[SimpleFeature]
  ): QuadTreeBounds = {
    val envelopes = features.asScala
      .map(_.getDefaultGeometry)
      .collect {
        case g: Geometry => g.getEnvelope.getEnvelopeInternal
      }
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
      quadTreeBounds.minx,
      quadTreeBounds.miny,
      quadTreeBounds.maxx,
      quadTreeBounds.maxy
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
      quadTreeBounds.minx,
      quadTreeBounds.miny,
      quadTreeBounds.maxx,
      quadTreeBounds.maxy
    )

    for (taz <- tazes) {
      tazQuadTree.put(taz.coord.getX, taz.coord.getY, taz)
    }

    new TAZTreeMap(tazQuadTree)
  }

  def getTazTreeMap(filePath: String): TAZTreeMap = {
    try {
      TAZTreeMap.fromCsv(filePath)
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
    rand: scala.util.Random = new scala.util.Random(System.currentTimeMillis())
  ): Coord = {
    val radius = Math.sqrt(taz.areaInSquareMeters / Math.PI) / 2
    val a = 2 * Math.PI * rand.nextDouble()
    val r = radius * Math.sqrt(rand.nextDouble())
    val x = r * Math.cos(a)
    val y = r * Math.sin(a)
    new Coord(taz.coord.getX + x, taz.coord.getY + y)
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
