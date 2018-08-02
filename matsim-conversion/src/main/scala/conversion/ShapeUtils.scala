package conversion

import java.io._
import java.util

import com.vividsolutions.jts.geom.Geometry
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation
import org.matsim.core.utils.gis.ShapeFileReader
import org.opengis.feature.simple.SimpleFeature
import org.supercsv.cellprocessor.constraint.{NotNull, UniqueHashCode}
import org.supercsv.cellprocessor.ift.CellProcessor
import org.supercsv.io._
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._


object ShapeUtils {

  case class QuadTreeBounds(minx: Double, miny: Double, maxx: Double, maxy: Double)

  case class CsvTaz(id: String, coordX: Double, coordY: Double)

  case class TAZ(tazId: Id[TAZ], coord: Coord) {
    def this(tazIdString: String, coord: Coord) {
      this(Id.create(tazIdString, classOf[TAZ]), coord)
    }
  }

  private def featureToCsvTaz(f: SimpleFeature, tazIDFieldName: String): Option[CsvTaz] = {
    f.getDefaultGeometry match {
      case g: Geometry =>
        Some(CsvTaz(f.getAttribute(tazIDFieldName).toString, g.getCoordinate.x, g.getCoordinate.y))
      case _ => None
    }
  }

  def shapeFileToCsv(
    shapeFilePath: String,
    tazIDFieldName: String,
    writeDestinationPath: String,
    localCRS: String
  ): Unit = {
    val shapeFileReader: ShapeFileReader = new ShapeFileReader
    shapeFileReader.readFileAndInitialize(shapeFilePath)
    val features: util.Collection[SimpleFeature] = shapeFileReader.getFeatureSet

    val utm2Wgs: GeotoolsTransformation = new GeotoolsTransformation(localCRS, "EPSG:4326")

    var mapWriter: ICsvMapWriter = null
    try {
      mapWriter =
        new CsvMapWriter(new FileWriter(writeDestinationPath), CsvPreference.STANDARD_PREFERENCE)

      val processors = getProcessors
      val header = Array[String]("taz", "coord-x", "coord-y")
      mapWriter.writeHeader(header: _*)

      val tazs = features.asScala
        .map(featureToCsvTaz(_, tazIDFieldName))
        .filter(_.isDefined)
        .map(_.get)
        .toArray
      println(s"Total TAZ ${tazs.length}")

      val groupedTazs = groupTaz(tazs)
      println(s"Total grouped TAZ ${groupedTazs.size}")

      val (repeatedTaz, nonRepeatedMap) = groupedTazs.partition(i => i._2.length > 1)
      println(s"Total repeatedMap TAZ ${repeatedTaz.size}")
      println(s"Total nonRepeatedMap TAZ ${nonRepeatedMap.size}")

      val clearedTaz = clearRepeatedTaz(repeatedTaz)
      println(s"Total repeated cleared TAZ ${clearedTaz.length}")

      val nonRepeated = nonRepeatedMap.map(_._2.head).toArray
      println(s"Total non repeated TAZ ${nonRepeated.length}")

      val allNonRepeatedTaz = clearedTaz ++ nonRepeated
      println(s"Total all TAZ ${allNonRepeatedTaz.length}")

      val wgs2Utm: GeotoolsTransformation = new GeotoolsTransformation(localCRS, "EPSG:26910")

      for (t <- allNonRepeatedTaz) {
        val tazToWrite = new util.HashMap[String, Object]()
        tazToWrite.put(header(0), t.id)

        val transFormedCoord: Coord = wgs2Utm.transform(new Coord(t.coordX, t.coordY))

        val tcoord = utm2Wgs.transform(new Coord(transFormedCoord.getX, transFormedCoord.getY))
        tazToWrite.put(header(1), tcoord.getX.toString)
        tazToWrite.put(header(2), tcoord.getY.toString)

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
      new UniqueHashCode(), // Id (must be unique)
      new NotNull(), // Coord X
      new NotNull()
    ) // Coord Y
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

}
