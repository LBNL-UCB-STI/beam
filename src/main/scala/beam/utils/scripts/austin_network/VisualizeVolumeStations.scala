package beam.utils.scripts.austin_network

import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.geometry.geotools.MGC
import org.matsim.core.utils.gis.{PointFeatureFactory, ShapeFileWriter}
import org.opengis.feature.simple.SimpleFeature

import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.collection.JavaConverters._
import AustinUtils._

object VisualizeVolumeStations {
  def main(args: Array[String]): Unit = {
    generateTrafficDetectorsShapeFile
    generateTrafficSensorsShapeFile
  }

  private def generateTrafficSensorsShapeFile() = {
    val coords=getCoordinatesTrafficCountsFile("E:\\work\\austin\\Travel_Sensors.csv",
      2, 3)
    createShapeFile(coords.map(_._2), "E:\\work\\austin\\Travel_Sensors.shp")
  }

  private def generateTrafficDetectorsShapeFile = {
    val coords=getCoordinatesTrafficCountsFile("E:\\work\\austin\\Traffic_Detectors.csv",
      0, 1)
    createShapeFile(coords.map(_._2), "E:\\work\\austin\\Traffic_Detectors.shp")
  }

  def getCoordinatesTrafficCountsFile(trafficCountsFile: String, indexLat: Int, indexLong: Int) = {
    val trafficDetectors = AustinUtils.getFileLines(trafficCountsFile)

    val coords = trafficDetectors.drop(1).map { line =>
      val id=line.split(",")(0)
      val tempColumns = line.split("\",")
      val lastCol = tempColumns(tempColumns.length - 1).split(",")
      val wgsCoordinate = new Coord(lastCol(indexLong).toDouble, lastCol(indexLat).toDouble)
      (DataId(id),wgsCoordinate)
    }

    coords
  }




}
