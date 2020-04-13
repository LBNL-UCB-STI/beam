package beam.utils.scripts

import beam.sim.common.GeoUtils
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.network.Link
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.NetworkReaderMatsimV2
import org.matsim.core.utils.collections.QuadTree

import scala.io.Source
import collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

object AustinNetworkSpeedMatching {

  def main(args: Array[String]): Unit = {
   // readCSV()


    val physsimSpeedVector: ArrayBuffer[SpeedVector] = getPhyssimSpeedVector("E:\\work\\austin\\output_network.xml.gz")
    val referenceSpeedVector: ArrayBuffer[SpeedVector] = getReferenceSpeedVector("E:\\work\\austin\\referenceRoadSpeedsAustin.csv")

    mapMatchingAlgorithm(physsimSpeedVector,referenceSpeedVector)
  }


  def readCSV(filePath:String):Vector[String]  = {
    val bufferedSource = Source.fromFile(filePath)
    var lines = bufferedSource.getLines.toVector
    bufferedSource.close
    lines
  }
    //for (i <- 1 to  bufferedSource.getLines.size) {
//    for (i <- 0 to 2) {
//      val line = lines(i)
//
//      line.asInstanceOf[String].split(",").foreach(x =>
//        println(x)
//      )
//
//      //line.split(",").map
//    }



    //     for (var i: 1 to bufferedSource.getLines.size) {
    //        line.split(",").map()
    //        //val cols = line.split(",").map(_.trim)
    //        // do whatever you want with the columns here
    //        //println(s"${cols(0)}|${cols(1)}|${cols(2)}|${cols(3)}")
    //       if (i>1) break;
    //      }
    //
    // }



  // linkId,SpeedVector ->


  // TODO: correct opposite direction links,


  def getQuadTreeBounds(speedDataPoints: ArrayBuffer[SpeedDataPoint]): QuadTreeBounds = {
    var minX: Double = Double.MaxValue
    var maxX: Double = Double.MinValue
    var minY: Double = Double.MaxValue
    var maxY: Double = Double.MinValue

    speedDataPoints.foreach { speedDataPoint =>
      minX = Math.min(minX, speedDataPoint.coord.getX)
      minY = Math.min(minY, speedDataPoint.coord.getY)
      maxX = Math.max(maxX, speedDataPoint.coord.getX)
      maxY = Math.max(maxY, speedDataPoint.coord.getY)
    }
    QuadTreeBounds(minX, minY, maxX, maxY)
  }

  def produceSpeedDataPointFromSpeedVector(speedVectors: mutable.ArrayBuffer[SpeedVector]): mutable.ArrayBuffer[SpeedDataPoint] = {
    val speedDataPoints = ArrayBuffer[SpeedDataPoint]()
    speedVectors.foreach {
      speedVector =>
        speedDataPoints ++= speedVector.produceSpeedDataPointFromSpeedVector(10)
    }
    speedDataPoints
  }

  def getPhyssimSpeedVector(filePath:String): ArrayBuffer[SpeedVector]={
    val speedVectors:ArrayBuffer[SpeedVector]=ArrayBuffer()


    val network = NetworkUtils.createNetwork
    val reader = new NetworkReaderMatsimV2(network)
    reader.readFile(filePath)


    val geoUtils = new beam.sim.common.GeoUtils {
      override def localCRS: String = "epsg:26910"
    }

    network.getLinks.values().asScala.toVector.foreach{ link =>
      speedVectors += SpeedVector(link.getId,link.getFromNode.getCoord,link.getToNode.getCoord,link.getFreespeed)
      //println(geoUtils.utm2Wgs(link.getFromNode))
    }

    ???
  }

  def getReferenceSpeedVector(filePath:String): ArrayBuffer[SpeedVector]={
    val lines=readCSV(filePath)

    lines.drop(0)
    for (line <- lines){
      val columns =line.split(",")
      val linkId=columns(0)
      val freeFlowSpeedInMetersPerSecond=columns(2)



      //val startCoordX=geoUtils.utm2Wgs(new Coord(x.))
      //val long=
        println(linkId)


    }
    ???
  }


  def mapMatchingAlgorithm(physsimSpeedVector: ArrayBuffer[SpeedVector],referenceSpeedVector: ArrayBuffer[SpeedVector]): Unit = {

    val physsimNetworkDP: ArrayBuffer[SpeedDataPoint] = produceSpeedDataPointFromSpeedVector(physsimSpeedVector)
    val referenceNetworkDP: ArrayBuffer[SpeedDataPoint] = produceSpeedDataPointFromSpeedVector(referenceSpeedVector)

    val quadTreeBounds: QuadTreeBounds = getQuadTreeBounds(physsimNetworkDP)
    val physsimQuadTreeDP: QuadTree[SpeedDataPoint] = new QuadTree[SpeedDataPoint](quadTreeBounds.minx, quadTreeBounds.miny, quadTreeBounds.maxx, quadTreeBounds.maxy)
    physsimNetworkDP.foreach {
      speedDataPoint => physsimQuadTreeDP.put(speedDataPoint.coord.getX, speedDataPoint.coord.getY, speedDataPoint)
    }


    referenceNetworkDP.foreach {
      referenceSpeedDataPoint =>
        val closestPhysSimNetworkPoint = physsimQuadTreeDP.getClosest(referenceSpeedDataPoint.coord.getX, referenceSpeedDataPoint.coord.getY)
        closestPhysSimNetworkPoint.closestReferenceSpeeds.get += referenceSpeedDataPoint.speedInMetersPerSecond
    }

    physsimQuadTreeDP.values().asScala.toVector.toList.foreach { physsimSpeedDataPoint =>

      val closestReferenceSpeeds = physsimSpeedDataPoint.closestReferenceSpeeds.get
      val averageReferenceSpeed = closestReferenceSpeeds.sum / closestReferenceSpeeds.length

      if (physsimSpeedDataPoint.speedInMetersPerSecond != averageReferenceSpeed) {
        println(s"${physsimSpeedDataPoint.linkId},${physsimSpeedDataPoint.speedInMetersPerSecond},$averageReferenceSpeed")
      }

    }

  }

}

//case class Coord(val lat: Double, val long: Double)

case class SpeedVector(val linkId: Id[Link], val startCoord: Coord, val endCoord: Coord, val speedInMetersPerSecond: Double) {
  def produceSpeedDataPointFromSpeedVector(numberOfPieces: Int): ArrayBuffer[SpeedDataPoint] = {
    val xDeltaVector = (endCoord.getX - startCoord.getX) / numberOfPieces
    val yDeltaVector = (endCoord.getY - startCoord.getY) / numberOfPieces

    val resultVector: ArrayBuffer[SpeedDataPoint] = collection.mutable.ArrayBuffer()

    for (i <- 0 to numberOfPieces) {
      resultVector += SpeedDataPoint(linkId, new Coord(startCoord.getX + numberOfPieces * xDeltaVector, startCoord.getY + numberOfPieces * yDeltaVector), speedInMetersPerSecond, Some(ArrayBuffer()))
    }
    resultVector
  }
}

case class SpeedDataPoint(val linkId: Id[Link], val coord: Coord, val speedInMetersPerSecond: Double, val closestReferenceSpeeds: Option[ArrayBuffer[Double]])