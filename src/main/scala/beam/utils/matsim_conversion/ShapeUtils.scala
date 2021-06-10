package beam.utils.matsim_conversion

import java.io._
import java.util

import com.vividsolutions.jts.geom.{Envelope, Geometry}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.gis.ShapeFileReader
import org.opengis.feature.simple.SimpleFeature
import org.supercsv.cellprocessor.constraint.{NotNull, UniqueHashCode}
import org.supercsv.cellprocessor.ift.CellProcessor
import org.supercsv.io._
import org.supercsv.prefs.CsvPreference
import scala.collection.JavaConverters._

import beam.agentsim.infrastructure.taz.CsvTaz

object ShapeUtils {

  case class QuadTreeBounds(minx: Double, miny: Double, maxx: Double, maxy: Double)

  object QuadTreeBounds {

    def apply(bbox: Envelope): QuadTreeBounds =
      new QuadTreeBounds(bbox.getMinX, bbox.getMinY, bbox.getMaxX, bbox.getMaxY)
  }

  case class CsvTaz(id: String, coordX: Double, coordY: Double, area: Double)

  trait HasQuadBounds[A] {
    def getMinX(a: A): Double
    def getMaxX(a: A): Double
    def getMinY(a: A): Double
    def getMaxY(a: A): Double
  }

  object HasQuadBounds {
    import scala.language.implicitConversions

    implicit val coord: HasQuadBounds[Coord] = new HasQuadBounds[Coord] {
      override def getMinX(coord: Coord): Double = coord.getX

      override def getMaxX(coord: Coord): Double = coord.getX

      override def getMinY(coord: Coord): Double = coord.getY

      override def getMaxY(coord: Coord): Double = coord.getY
    }

    implicit val envelope: HasQuadBounds[Envelope] = new HasQuadBounds[Envelope] {
      override def getMinX(envelope: Envelope): Double = envelope.getMinX

      override def getMaxX(envelope: Envelope): Double = envelope.getMaxX

      override def getMinY(envelope: Envelope): Double = envelope.getMinY

      override def getMaxY(envelope: Envelope): Double = envelope.getMaxY
    }

  }

  trait HasCoord[A] {
    def getCoord(a: A): Coord
  }

  private def featureToCsvTaz(f: SimpleFeature, tazIDFieldName: String): Option[CsvTaz] = {
    f.getDefaultGeometry match {
      case g: Geometry =>
        Some(
          CsvTaz(
            f.getAttribute(tazIDFieldName).toString,
            g.getCoordinate.x,
            g.getCoordinate.y,
            g.getArea
          )
        )
      case _ => None
    }
  }

  def shapeFileToCsv(
    shapeFilePath: String,
    tazIDFieldName: String,
    writeDestinationPath: String
  ): Unit = {
    val shapeFileReader: ShapeFileReader = new ShapeFileReader
    shapeFileReader.readFileAndInitialize(shapeFilePath)
    val features: util.Collection[SimpleFeature] = shapeFileReader.getFeatureSet

    var mapWriter: ICsvMapWriter = null
    try {
      mapWriter = new CsvMapWriter(new FileWriter(writeDestinationPath), CsvPreference.STANDARD_PREFERENCE)

      val processors = getProcessors
      val header = Array[String]("taz", "coord-x", "coord-y", "area")
      mapWriter.writeHeader(header: _*)

      val tazs = features.asScala
        .map(featureToCsvTaz(_, tazIDFieldName))
        .filter(_.isDefined)
        .map(_.get)
        .toArray
//      println(s"Total TAZ ${tazs.length}")

      val groupedTazs = groupTaz(tazs)
//      println(s"Total grouped TAZ ${groupedTazs.size}")

      val (repeatedTaz, nonRepeatedMap) = groupedTazs.partition(i => i._2.length > 1)
//      println(s"Total repeatedMap TAZ ${repeatedTaz.size}")
//      println(s"Total nonRepeatedMap TAZ ${nonRepeatedMap.size}")

      val clearedTaz = clearRepeatedTaz(repeatedTaz)
//      println(s"Total repeated cleared TAZ ${clearedTaz.length}")

      val nonRepeated = nonRepeatedMap.map(_._2.head).toArray
//      println(s"Total non repeated TAZ ${nonRepeated.length}")

      val allNonRepeatedTaz = clearedTaz ++ nonRepeated
//      println(s"Total all TAZ ${allNonRepeatedTaz.length}")

      for (t <- allNonRepeatedTaz) {
        val tazToWrite = new util.HashMap[String, Object]()
        tazToWrite.put(header(0), t.id)

        tazToWrite.put(header(1), t.coordX.toString)
        tazToWrite.put(header(2), t.coordY.toString)
        tazToWrite.put(header(3), t.area.toString)

        mapWriter.write(tazToWrite, header, processors)
      }
    } finally {
      if (mapWriter != null) {
        mapWriter.close()
      }
    }
  }

  def getProcessors: Array[CellProcessor] = {
    Array[CellProcessor](
      new NotNull(), // Id (must be unique)
      new NotNull(), // Coord X
      new NotNull(), // Coord Y
      new NotNull() // Area
    )
  }

  private def groupTaz(csvSeq: Array[CsvTaz]): Map[String, Array[CsvTaz]] = {
    csvSeq.groupBy(_.id)
  }

  private def clearRepeatedTaz(groupedRepeatedTaz: Map[String, Array[CsvTaz]]): Array[CsvTaz] = {
    groupedRepeatedTaz.flatMap(i => addSuffix(i._1, i._2)).toArray
  }

  private def addSuffix(id: String, elems: Array[CsvTaz]): Array[CsvTaz] = {
    ((1 to elems.length) zip elems map {
      case (index, elem) => elem.copy(id = s"${id}_$index")
    }).toArray
  }

  def quadTreeBounds[A: HasQuadBounds](elements: Iterable[A]): QuadTreeBounds = {
    val A = implicitly[HasQuadBounds[A]]
    var minX: Double = Double.MaxValue
    var maxX: Double = Double.MinValue
    var minY: Double = Double.MaxValue
    var maxY: Double = Double.MinValue

    for (a <- elements) {
      minX = Math.min(minX, A.getMinX(a))
      minY = Math.min(minY, A.getMinY(a))
      maxX = Math.max(maxX, A.getMaxX(a))
      maxY = Math.max(maxY, A.getMaxY(a))
    }
    QuadTreeBounds(minX, minY, maxX, maxY)
  }

  def quadTree[A: HasCoord](elements: Seq[A]): QuadTree[A] = {
    val A = implicitly[HasCoord[A]]
    val coords = elements.map(A.getCoord)
    val bounds: ShapeUtils.QuadTreeBounds = ShapeUtils.quadTreeBounds(coords)
    val quadTree: QuadTree[A] = new QuadTree(bounds.minx, bounds.miny, bounds.maxx, bounds.maxy)

    for ((x, coord) <- elements zip coords) {
      quadTree.put(coord.getX, coord.getY, x)
    }
    quadTree
  }
}
