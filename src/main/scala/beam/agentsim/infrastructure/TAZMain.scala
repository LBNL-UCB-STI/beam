package beam.agentsim.infrastructure

import java.io._
import java.util
import java.util.ArrayList
import java.util.zip.GZIPInputStream

import beam.agentsim.agents.PersonAgent
import beam.utils.scripts.HasXY.wgs2Utm
import beam.utils.scripts.PlansSampler._
import beam.utils.scripts.QuadTreeExtent
import com.vividsolutions.jts.geom.Geometry
import org.geotools.data.simple.SimpleFeatureIterator
import org.geotools.data.{FileDataStore, FileDataStoreFinder}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.gis.ShapeFileReader
import org.matsim.core.utils.misc.Counter
import org.opengis.feature.simple.SimpleFeature
import util.HashMap

import beam.utils.ObjectAttributesUtils
import beam.utils.scripts.HouseholdAttrib.HousingType
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation
import org.matsim.core.utils.io.IOUtils
import org.matsim.utils.objectattributes.{ObjectAttributes, ObjectAttributesXmlWriter}
import org.supercsv.cellprocessor.ParseDouble
import org.supercsv.cellprocessor.FmtBool
import org.supercsv.cellprocessor.FmtDate
import org.supercsv.cellprocessor.constraint.{LMinMax, NotNull, Unique, UniqueHashCode}
import org.supercsv.cellprocessor.ift.CellProcessor
import org.supercsv.exception.SuperCsvConstraintViolationException
import org.supercsv.util.CsvContext
import org.supercsv.io._
import org.supercsv.prefs.CsvPreference
import org.xml.sax.InputSource

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer


object TAZCreatorScript extends App {

  /*
  val shapeFile: String = "Y:\\tmp\\beam\\tl_2011_06_taz10\\tl_2011_06_taz10.shp"
  val taz=new TAZTreeMap(shapeFile, "TAZCE10")

// TODO: attriutes or xml from config file - allow specifying multiple files
    // create test attributes data for starting


  val tazParkingAttributesFilePath="Y:\\tmp\\beam\\infrastructure\\tazParkingAttributes.xml"

  val tazParkingAttributes: ObjectAttributes =new ObjectAttributes()

  tazParkingAttributes.putAttribute(Parking.PARKING_MANAGER, "className", "beam.agentsim.infrastructure.BayAreaParkingManager")

  for (tazVal:TAZ <-taz.tazQuadTree.values()){
    tazParkingAttributes.putAttribute(tazVal.tazId.toString, Parking.PARKING_TYPE, ParkingType.PARKING_WITH_CHARGER)
    tazParkingAttributes.putAttribute(tazVal.tazId.toString, Parking.PARKING_CAPACITY, 1.toString)
    tazParkingAttributes.putAttribute(tazVal.tazId.toString, Parking.HOURLY_RATE, 1.0.toString)
    tazParkingAttributes.putAttribute(tazVal.tazId.toString, Parking.CHARGING_LEVEL, ChargerLevel.L2)
  }

  //TODO: convert shape file to taz csv.
  // create script for that to use sometimes.
  //#TAZ params
  //  beam.agentsim.taz.file=""
  //#Parking params
  //  beam.agentsim.infrastructure.parking.attributesFilePaths=""




  ObjectAttributesUtils.writeObjectAttributesToCSV(tazParkingAttributes,tazParkingAttributesFilePath)

  println(shapeFile)

  println(taz.getId(-120.8043534,+35.5283106))
*/

  //TAZTreeMap.shapeFileToCsv("Y:\\tmp\\beam\\tl_2011_06_taz10\\tl_2011_06_taz10.shp","TAZCE10","Y:\\tmp\\beam\\taz-centers.csv")


//  println("HELLO WORLD")
//  val path = "d:/shape_out.csv.gz"
//  val mapTaz = TAZTreeMap.fromCsv(path)
//  print(mapTaz)


  //Test Write File
  if (null != args && 3 == args.length){
    println("Running conversion")
    val pathFileShape = args(0)
    val tazIdName = args(1)
    val destination = args(2)

    println("Process Started")
    TAZTreeMap.shapeFileToCsv(pathFileShape,tazIdName,destination)
    println("Process Terminate...")
  } else {
    println("Please specify: shapeFilePath tazIDFieldName destinationFilePath")
  }

}

class TAZTreeMap(val tazQuadTree: QuadTree[TAZ]) {
  def getTAZ(x: Double, y: Double): TAZ = {
    // TODO: is this enough precise, or we want to get the exact TAZ where the coordinate is located?
    tazQuadTree.getClosest(x,y)
  }
}

object TAZTreeMap {
  def fromShapeFile(shapeFilePath: String, tazIDFieldName: String): TAZTreeMap = {
    new TAZTreeMap(initQuadTreeFromShapeFile(shapeFilePath, tazIDFieldName))
  }

  private def initQuadTreeFromShapeFile(shapeFilePath: String, tazIDFieldName: String): QuadTree[TAZ] = {
    val shapeFileReader: ShapeFileReader = new ShapeFileReader
    shapeFileReader.readFileAndInitialize(shapeFilePath)
    val features: util.Collection[SimpleFeature] =     shapeFileReader.getFeatureSet
    val quadTreeBounds: QuadTreeBounds = quadTreeExtentFromShapeFile(features)

    val tazQuadTree: QuadTree[TAZ] = new QuadTree[TAZ](quadTreeBounds.minx, quadTreeBounds.miny, quadTreeBounds.maxx, quadTreeBounds.maxy)

    for (f <- features.asScala) {
      f.getDefaultGeometry match {
        case g: Geometry =>
          val taz = new TAZ(f.getAttribute(tazIDFieldName).asInstanceOf[String], new Coord(g.getCoordinate.x, g.getCoordinate.y))
          tazQuadTree.put(taz.coord.getX, taz.coord.getY, taz)
        case _ =>
      }
    }
    tazQuadTree
  }

  private def quadTreeExtentFromShapeFile(features: util.Collection[SimpleFeature]): QuadTreeBounds = {
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
    val tazQuadTree: QuadTree[TAZ] = new QuadTree[TAZ](quadTreeBounds.minx, quadTreeBounds.miny, quadTreeBounds.maxx, quadTreeBounds.maxy)

    for (l <- lines) {
      val taz = new TAZ(l.id, new Coord(l.coordX, l.coordY))
      tazQuadTree.put(taz.coord.getX, taz.coord.getY, taz)
    }

    new TAZTreeMap(tazQuadTree)

  }

  private def readerFromFile(filePath: String): java.io.Reader  = {
    if(filePath.endsWith(".gz")){
      new InputStreamReader(new GZIPInputStream(new BufferedInputStream(new FileInputStream(filePath))))
    } else {
      new FileReader(filePath)
    }
  }

  private def readCsvFile(filePath: String): Seq[CsvTaz] = {
    var mapReader: ICsvMapReader = null
    val res = ArrayBuffer[CsvTaz]()
    try{
      mapReader = new CsvMapReader(readerFromFile(filePath), CsvPreference.STANDARD_PREFERENCE)
      val header = mapReader.getHeader(true)
      var flag = true
      var line: java.util.Map[String, String] = mapReader.read(header:_*)
      while(null != line){
        val id = line.get("taz")
        val coordX = line.get("coord-x")
        val coordY = line.get("coord-y")
        res.append(CsvTaz(id, coordX.toDouble, coordY.toDouble))
        line = mapReader.read(header:_*)
      }

    } finally{
      if(null != mapReader)
        mapReader.close()
    }
    res
  }

  def featureToCsvTaz(f: SimpleFeature, tazIDFieldName: String): Option[CsvTaz] = {
    f.getDefaultGeometry match {
      case g: Geometry =>
        Some(CsvTaz(f.getAttribute(tazIDFieldName).toString, g.getCoordinate.x, g.getCoordinate.y))
      case _ => None
    }
  }

  def shapeFileToCsv(shapeFilePath: String, tazIDFieldName: String , writeDestinationPath: String): Unit = {
    val shapeFileReader: ShapeFileReader = new ShapeFileReader
    shapeFileReader.readFileAndInitialize(shapeFilePath)
    val features: util.Collection[SimpleFeature] = shapeFileReader.getFeatureSet

    lazy val utm2Wgs: GeotoolsTransformation = new GeotoolsTransformation("utm", "EPSG:26910")

    var mapWriter: ICsvMapWriter   = null
    try {
      mapWriter = new CsvMapWriter(new FileWriter(writeDestinationPath),
        CsvPreference.STANDARD_PREFERENCE)

      val processors = getProcessors
      val header = Array[String]("taz", "coord-x", "coord-y")
      mapWriter.writeHeader(header:_*)

      val tazs = features.asScala.map(featureToCsvTaz(_, tazIDFieldName))
        .filter(_.isDefined)
        .map(_.get).toArray
      println(s"Total TAZ $tazs.length")

      val groupedTazs = groupTaz(tazs)
      println(s"Total grouped TAZ ${groupedTazs.size}")

      val (repeatedTaz, nonRepeatedMap) = groupedTazs.partition(i => i._2.length > 1)
      println(s"Total repeatedMap TAZ ${repeatedTaz.size}")
      println(s"Total nonRepeatedMap TAZ ${nonRepeatedMap.size}")

      val clearedTaz = clearRepeatedTaz(repeatedTaz)
      println(s"Total repeated cleared TAZ $clearedTaz.length")

      val nonRepeated = nonRepeatedMap.map(_._2.head).toArray
      println(s"Total non repeated TAZ $nonRepeated.length")

      val allNonRepeatedTaz = clearedTaz ++ nonRepeated
      println(s"Total all TAZ $allNonRepeatedTaz.length")

      for(t <- allNonRepeatedTaz){
        val tazToWrite = new util.HashMap[String, Object]()
        tazToWrite.put(header(0), t.id)
       //
       val transFormedCoord: Coord = wgs2Utm.transform(new Coord(t.coordX, t.coordY))
        val tcoord = utm2Wgs.transform(new Coord(transFormedCoord.getX, transFormedCoord.getY))
        tazToWrite.put(header(1), tcoord.getX.toString)
        tazToWrite.put(header(2), tcoord.getY.toString)
        mapWriter.write(tazToWrite, header, processors)
      }
    } finally {
      if( mapWriter != null ) {
        mapWriter.close()
      }
    }
  }

  private def getProcessors: Array[CellProcessor]  = {
    Array[CellProcessor](
      new UniqueHashCode(), // Id (must be unique)
      new NotNull(), // Coord X
      new NotNull()) // Coord Y
  }

  private def groupTaz(csvSeq: Array[CsvTaz]): Map[String, Array[CsvTaz]] = {
    csvSeq.groupBy(_.id)
  }

  private def clearRepeatedTaz(groupedRepeatedTaz: Map[String, Array[CsvTaz]]): Array[CsvTaz] = {
    groupedRepeatedTaz.flatMap(i => addSuffix(i._1, i._2))
      .toArray
  }

  private def addSuffix(_id: String, elems: Array[CsvTaz]): Array[CsvTaz] = {
    (1 to elems.length).zip(elems).map {
      case (index, elem) => elem.copy(id = s"${_id}_$index")
    }.toArray
  }

  private def closestToPoint(referencePoint: Double, elems: Array[CsvTaz]): CsvTaz = {
    elems.reduce{ (a, b) =>
      val comparison1 = (a, Math.abs(referencePoint - a.coordY))
      val comparison2 = (b, Math.abs(referencePoint - b.coordY))
      val closest = Seq(comparison1, comparison2) minBy(_._2)
      closest._1
    }
  }
}

case class QuadTreeBounds(minx: Double, miny: Double, maxx: Double, maxy: Double)
case class CsvTaz(id: String, coordX: Double, coordY: Double)

class TAZ(val tazId: Id[TAZ],val coord: Coord){
  def this(tazIdString: String, coord: Coord) {
    this(Id.create(tazIdString,classOf[TAZ]),coord)
  }
}


