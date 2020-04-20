package beam.utils.scripts.austin_network

import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.geometry.geotools.MGC
import org.matsim.core.utils.gis.{PointFeatureFactory, ShapeFileWriter}
import org.opengis.feature.simple.SimpleFeature

import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.collection.JavaConverters._

object VisualizeVolumeStations {
  def main(args: Array[String]): Unit = {
    //generateTrafficDetectorsShapeFile
    generateTrafficSensorsShapeFile
  }

  private def generateTrafficSensorsShapeFile = {
    val trafficDetectors = getLines("E:\\work\\austin\\Travel_Sensors.csv")

    val coords = trafficDetectors.drop(1).map { line =>
      val tempColumns = line.split("\",")
      val lastCol = tempColumns(tempColumns.length - 1).split(",")
      val wgsCoordinate = new Coord(lastCol(3).toDouble, lastCol(2).toDouble)
      wgsCoordinate
    }

    createShapeFile(coords, "E:\\work\\austin\\Travel_Sensors.shp")
  }

  private def generateTrafficDetectorsShapeFile = {
    val trafficDetectors = getLines("E:\\work\\austin\\Traffic_Detectors.csv")

    val coords = trafficDetectors.drop(1).map { line =>
      val tempColumns = line.split("\",")
      val lastCol = tempColumns(tempColumns.length - 1).split(",")
      val wgsCoordinate = new Coord(lastCol(1).toDouble, lastCol(0).toDouble)
      wgsCoordinate
    }

    createShapeFile(coords, "E:\\work\\austin\\Traffic_Detectors.shp")
  }

  def createShapeFile(coords: Vector[Coord], shapeFileOutputPath:String)={
    val features = ArrayBuffer[SimpleFeature]()

    val pointf: PointFeatureFactory = new PointFeatureFactory.Builder()
      .setCrs(MGC.getCRS("EPSG:4326"))
      .setName("nodes")
      .create()

    coords.foreach{ wsgCoord =>
        val coord=new com.vividsolutions.jts.geom.Coordinate(wsgCoord.getX, wsgCoord.getY)
        val feature=pointf.createPoint(coord)
        features+=feature
    }

    ShapeFileWriter.writeGeometries(features.asJava,shapeFileOutputPath)
  }

  def getLines(filePath:String): Vector[String] ={
    val trafficDetectorsFilePath = Source.fromFile(filePath)
    var lines = trafficDetectorsFilePath.getLines.toVector
    trafficDetectorsFilePath.close
    lines
  }


}
