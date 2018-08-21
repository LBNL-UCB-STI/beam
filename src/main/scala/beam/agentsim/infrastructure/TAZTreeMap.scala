package beam.agentsim.infrastructure

import java.io._
import java.util
import java.util.zip.GZIPInputStream

import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.utils.plansampling.WGSConverter
import com.google.inject.{ImplementedBy, Inject}
import com.vividsolutions.jts.geom.Geometry
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation
import org.matsim.core.utils.gis.ShapeFileReader
import org.opengis.feature.simple.SimpleFeature
import org.supercsv.cellprocessor.constraint.{NotNull, UniqueHashCode}
import org.supercsv.cellprocessor.ift.CellProcessor
import org.supercsv.io._
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class TAZTreeMap(val tazQuadTree: QuadTree[TAZ]) {

  val stringIdToTAZMapping: mutable.HashMap[String, TAZ] = mutable.HashMap()

  def getTAZs: Iterable[TAZ] = {
    tazQuadTree.values().asScala
  }

  for (taz: TAZ <- tazQuadTree.values().asScala) {
    stringIdToTAZMapping.put(taz.tazId.toString, taz)
  }

  def getTAZ(x: Double, y: Double): TAZ = {
    // TODO: is this enough precise, or we want to get the exact TAZ where the coordinate is located?
    tazQuadTree.getClosest(x, y)
  }

  def getTAZ(tazId: String): Option[TAZ] = {
    stringIdToTAZMapping.get(tazId)
  }

  def getTAZ(tazId: Id[TAZ]): Option[TAZ] = {
    stringIdToTAZMapping.get(tazId.toString)
  }

  def getTAZInRadius(x: Double, y: Double, radius: Double): util.Collection[TAZ] = {
    // TODO: is this enough precise, or we want to get the exact TAZ where the coordinate is located?
    tazQuadTree.getDisk(x, y, radius)
  }

  def getTAZInRadius(loc: Coord, radius: Double): util.Collection[TAZ] = {
    tazQuadTree.getDisk(loc.getX, loc.getY, radius)
  }
}

object TAZTreeMap {

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
    var minX: Double = Double.MaxValue
    var maxX: Double = Double.MinValue
    var minY: Double = Double.MaxValue
    var maxY: Double = Double.MinValue

    for (f <- features.asScala) {
      f.getDefaultGeometry match {
        case g: Geometry =>
          val ca = g.getEnvelope.getEnvelopeInternal
          //val ca = wgs2Utm(g.getEnvelope.getEnvelopeInternal)
          minX = Math.min(minX, ca.getMinX)
          minY = Math.min(minY, ca.getMinY)
          maxX = Math.max(maxX, ca.getMaxX)
          maxY = Math.max(maxY, ca.getMaxY)
        case _ =>
      }
    }
    QuadTreeBounds(minX, minY, maxX, maxY)
  }

  private def quadTreeExtentFromCsvFile(lines: Seq[CsvTaz]): QuadTreeBounds = {
    var minX: Double = Double.MaxValue
    var maxX: Double = Double.MinValue
    var minY: Double = Double.MaxValue
    var maxY: Double = Double.MinValue

    for (l <- lines) {
      minX = Math.min(minX, l.coordX)
      minY = Math.min(minY, l.coordY)
      maxX = Math.max(maxX, l.coordX)
      maxY = Math.max(maxY, l.coordY)
    }
    QuadTreeBounds(minX, minY, maxX, maxY)
  }

  def fromCsv(csvFile: String): TAZTreeMap = {

    val lines = readCsvFile(csvFile)
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

  private def readerFromFile(filePath: String): java.io.Reader = {
    if (filePath.endsWith(".gz")) {
      new InputStreamReader(
        new GZIPInputStream(new BufferedInputStream(new FileInputStream(filePath)))
      )
    } else {
      new FileReader(filePath)
    }
  }

  private def readCsvFile(filePath: String): Seq[CsvTaz] = {
    var mapReader: ICsvMapReader = null
    val res = ArrayBuffer[CsvTaz]()
    try {
      mapReader = new CsvMapReader(readerFromFile(filePath), CsvPreference.STANDARD_PREFERENCE)
      val header = mapReader.getHeader(true)
      var line: java.util.Map[String, String] = mapReader.read(header: _*)
      while (null != line) {
        val id = line.get("taz")
        val coordX = line.get("coord-x")
        val coordY = line.get("coord-y")
        val area = line.get("area")
        res.append(CsvTaz(id, coordX.toDouble, coordY.toDouble, area.toDouble))
        line = mapReader.read(header: _*)
      }

    } finally {
      if (null != mapReader)
        mapReader.close()
    }
    res
  }

  def featureToCsvTaz(f: SimpleFeature, tazIDFieldName: String): Option[CsvTaz] = {
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

    lazy val utm2Wgs: GeotoolsTransformation = new GeotoolsTransformation("EPSG:4326", "EPSG:26910")
    lazy val wgs2utm: GeotoolsTransformation = new GeotoolsTransformation("EPSG:26910", "EPSG:4326")
    var mapWriter: ICsvMapWriter = null
    try {
      mapWriter =
        new CsvMapWriter(new FileWriter(writeDestinationPath), CsvPreference.STANDARD_PREFERENCE)

      val processors = getProcessors
      val header = Array[String]("taz", "coord-x", "coord-y", "area")
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

      for (t <- allNonRepeatedTaz) {
        val tazToWrite = new util.HashMap[String, Object]()
        tazToWrite.put(header(0), t.id)
        //
        val transFormedCoord: Coord = wgs2utm.transform(new Coord(t.coordX, t.coordY))
        val tcoord = utm2Wgs.transform(new Coord(transFormedCoord.getX, transFormedCoord.getY))
        tazToWrite.put(header(1), tcoord.getX.toString)
        tazToWrite.put(header(2), tcoord.getY.toString)
        tazToWrite.put(header(3), t.area.toString)
        mapWriter.write(tazToWrite, header, processors)
      }
    } finally {
      if (mapWriter != null) {
        mapWriter.close()
      }
    }
  }

  private def getProcessors: Array[CellProcessor] = {
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

  private def closestToPoint(referencePoint: Double, elems: Array[CsvTaz]): CsvTaz = {
    elems.reduce { (a, b) =>
      val comparison1 = (a, Math.abs(referencePoint - a.coordY))
      val comparison2 = (b, Math.abs(referencePoint - b.coordY))
      val closest = Seq(comparison1, comparison2) minBy (_._2)
      closest._1
    }
  }

  case class QuadTreeBounds(minx: Double, miny: Double, maxx: Double, maxy: Double)

  case class CsvTaz(id: String, coordX: Double, coordY: Double, area: Double)

  class TAZ(val tazId: Id[TAZ], val coord: Coord, val area: Double) {
    def this(tazIdString: String, coord: Coord, area: Double) {
      this(Id.create(tazIdString, classOf[TAZ]), coord, area)
    }
  }
}
