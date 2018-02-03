package beam.agentsim.infrastructure

import java.io.File
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

import scala.collection.JavaConverters._


object TAZCreatorSctript extends App {
  val shapeFile: String = "Y:\\tmp\\beam\\tl_2011_06_taz10\\tl_2011_06_taz10.shp";
  val taz=new TAZTreeMap(shapeFile, "TAZCE10")

// TODO: attriutes or xml from config file


  val tazinfrastructureAttributesFilePath="Y:\\tmp\\beam\\infrastructure\\tazParkingAndChargingInfrastructureAttributes.xml"

  tazParkingAndChargingInfrastructureAttributes.putAttribute("FileInterpreter", "className", "BayAreaParkingAndChargingInfrastructure")

  for (tazVal:TAZ <-taz.tazQuadTree.values()){
    tazParkingAndChargingInfrastructureAttributes.putAttribute(tazVal.tazId.toString, "streetParkingCapacity", 1.toString)
    tazParkingAndChargingInfrastructureAttributes.putAttribute(tazVal.tazId.toString, "offStreetParkingCapacity", 1.toString)
  }

  val tazParkingAndChargingInfrastructureAttributes: ObjectAttributes =new ObjectAttributes()


  ObjectAttributesUtils.writeObjectAttributesToCSV(tazParkingAndChargingInfrastructureAttributes,tazinfrastructureAttributesFilePath)

  println(shapeFile)




  println(taz.getId(-120.8043534,+35.5283106))





}

class TAZTreeMap(shapeFilePath: String, tazIDFieldName: String) {
  val tazQuadTree: QuadTree[TAZ] = initQuadTree()

  def getId(x: Double, y: Double): TAZ={
    // TODO: is this enough precise, or we want to get the exact TAZ where the coordinate is located?
    tazQuadTree.getClosest(x,y)
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

  private def initQuadTree(): QuadTree[TAZ] = {
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



}

case class QuadTreeBounds(minx: Double, miny: Double, maxx: Double, maxy: Double)

class TAZ(val tazId: Id[TAZ],val coord: Coord){
  def this(tazIdString: String, coord: Coord) {
    this(Id.create(tazIdString,classOf[TAZ]),coord)
  }
}


