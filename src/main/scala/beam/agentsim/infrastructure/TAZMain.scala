package beam.agentsim.infrastructure

import java.io.{File, FileReader}
import java.util
import java.util.ArrayList

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
import org.matsim.utils.objectattributes.{ObjectAttributes, ObjectAttributesXmlWriter}
import org.supercsv.cellprocessor.ParseDouble
import org.supercsv.cellprocessor.ift.CellProcessor
import org.supercsv.util.CsvContext
import org.supercsv.cellprocessor.constraint.NotNull
import org.supercsv.io.{CsvMapReader, ICsvListWriter, ICsvMapReader}
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer


object TAZCreatorScript extends App {
  val shapeFile: String = "Y:\\tmp\\beam\\tl_2011_06_taz10\\tl_2011_06_taz10.shp";
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




  ObjectAttributesUtils.writeObjectAttributesToCSV(tazParkingAttributes,tazParkingAttributesFilePath)

  println(shapeFile)

  println(taz.getId(-120.8043534,+35.5283106))

}

class TAZTreeMap(tazQuadTree: QuadTree[TAZ]) {
  def getId(x: Double, y: Double): TAZ = {
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
          var taz = new TAZ(f.getAttribute(tazIDFieldName).asInstanceOf[String], new Coord(g.getCoordinate.x, g.getCoordinate.y))
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

  private def readCsvFile(filePath: String): Seq[CsvTaz] = {
    var mapReader: ICsvMapReader = null
    val res = ArrayBuffer[CsvTaz]()
    try{
      mapReader = new CsvMapReader(new FileReader(filePath), CsvPreference.STANDARD_PREFERENCE)
      val header = mapReader.getHeader(true)
      var line: java.util.Map[String, String] = null

      while((line = mapReader.read(header:_*)) != null){
        val id = line.get("taz")
        val coordX = line.get("coord-x")
        val coordY = line.get("coord-y")
        res.append(CsvTaz(id, coordX.toDouble, coordY.toDouble))
      }

    } finally{
      if(null != mapReader)
        mapReader.close()
    }
    res
  }

}

case class QuadTreeBounds(minx: Double, miny: Double, maxx: Double, maxy: Double)
case class CsvTaz(id: String, coordX: Double, coordY: Double)

class TAZ(val tazId: Id[TAZ],val coord: Coord){
  def this(tazIdString: String, coord: Coord) {
    this(Id.create(tazIdString,classOf[TAZ]),coord)
  }
}


