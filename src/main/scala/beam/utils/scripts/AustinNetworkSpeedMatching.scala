package beam.utils.scripts

import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.core.utils.collections.QuadTree

import scala.io.Source
import collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

object AustinNetworkSpeedMatching {

  def main(args: Array[String]): Unit = {
    readCSV()

  }


  def readCSV() = {
    println("Month, Income, Expenses, Profit")

    val bufferedSource = Source.fromFile("E:\\work\\austin\\referenceRoadSpeedsAustin.csv")
    //var i=0

    var lines = bufferedSource.getLines.toVector

    //for (i <- 1 to  bufferedSource.getLines.size) {
    for (i <- 0 to 2) {
      val line = lines(i)

      line.asInstanceOf[String].split(",").foreach(x =>
        println(x)
      )

      //line.split(",").map
    }

    bufferedSource.close

    //     for (var i: 1 to bufferedSource.getLines.size) {
    //        line.split(",").map()
    //        //val cols = line.split(",").map(_.trim)
    //        // do whatever you want with the columns here
    //        //println(s"${cols(0)}|${cols(1)}|${cols(2)}|${cols(3)}")
    //       if (i>1) break;
    //      }
    //
    // }

  }

  // linkId,SpeedVector ->


  // TODO: correct opposite direction links,




def getQuadTreeBounds(speedDataPoints:ArrayBuffer[SpeedDataPoint]):QuadTreeBounds={
  var minX: Double = Double.MaxValue
  var maxX: Double = Double.MinValue
  var minY: Double = Double.MaxValue
  var maxY: Double = Double.MinValue

  speedDataPoints.foreach{ speedDataPoint =>
    minX = Math.min(minX, speedDataPoint.coord.lat)
    minY = Math.min(minY, speedDataPoint.coord.long)
    maxX = Math.max(maxX, speedDataPoint.coord.lat)
    maxY = Math.max(maxY, speedDataPoint.coord.long)
  }
  QuadTreeBounds(minX, minY, maxX, maxY)
}

  def produceSpeedDataPointFromSpeedVector(speedVectors: mutable.ArrayBuffer[SpeedVector]):mutable.ArrayBuffer[SpeedDataPoint]={
    val speedDataPoints=ArrayBuffer[SpeedDataPoint]()
    speedVectors.foreach{
      speedVector =>
        speedDataPoints++=speedVector.produceSpeedDataPointFromSpeedVector(10)
    }
    speedDataPoints
  }


  def mapMatchingAlgorithm(): Unit = {

    val physsimSpeedVector: ArrayBuffer[SpeedVector] = ArrayBuffer()
    val referenceSpeedVector: ArrayBuffer[SpeedVector] = ArrayBuffer()


    val physsimNetworkDP:ArrayBuffer[SpeedDataPoint]=produceSpeedDataPointFromSpeedVector(physsimSpeedVector)
    val referenceNetworkDP:ArrayBuffer[SpeedDataPoint]=produceSpeedDataPointFromSpeedVector(referenceSpeedVector)

    val quadTreeBounds:QuadTreeBounds=getQuadTreeBounds(physsimNetworkDP)
    val physsimQuadTreeDP:QuadTree[SpeedDataPoint]=new QuadTree[SpeedDataPoint](quadTreeBounds.minx,quadTreeBounds.miny,quadTreeBounds.maxx,quadTreeBounds.maxy)
    physsimNetworkDP.foreach{
      x => physsimQuadTreeDP.put(x.coord.lat,x.coord.long,x)
    }


    referenceNetworkDP.foreach{
      referenceSpeedDataPoint =>
      val closestPhysSimNetworkPoint=physsimQuadTreeDP.getClosest(referenceSpeedDataPoint.coord.lat,referenceSpeedDataPoint.coord.long)
        closestPhysSimNetworkPoint.closestReferenceSpeeds.get+=referenceSpeedDataPoint.speedInMetersPerSecond
    }

    physsimQuadTreeDP.values().asScala.toVector.toList.foreach{ physsimSpeedDataPoint =>

      val closestReferenceSpeeds=physsimSpeedDataPoint.closestReferenceSpeeds.get
      val averageReferenceSpeed=closestReferenceSpeeds.sum / closestReferenceSpeeds.length

       if (physsimSpeedDataPoint.speedInMetersPerSecond != averageReferenceSpeed){
          println(s"${physsimSpeedDataPoint.linkId},${physsimSpeedDataPoint.speedInMetersPerSecond},$averageReferenceSpeed")
      }

  }

}

}

case class Coord(val lat: Double, val long: Double)

case class SpeedVector(val linkId: Id[Link], val startCoord: Coord, val endCoord: Coord, val speedInMetersPerSecond: Double) {
  def produceSpeedDataPointFromSpeedVector(numberOfPieces: Int): ArrayBuffer[SpeedDataPoint] = {
    val latDeltaVector = (endCoord.lat - startCoord.lat) / numberOfPieces
    val longDeltaVector = (endCoord.long - startCoord.long) / numberOfPieces

    val resultVector: ArrayBuffer[SpeedDataPoint] = collection.mutable.ArrayBuffer()

    for (i <- 0 to numberOfPieces) {
      resultVector += SpeedDataPoint(linkId,Coord(startCoord.lat + numberOfPieces * latDeltaVector, startCoord.long + numberOfPieces * latDeltaVector), speedInMetersPerSecond,Some(ArrayBuffer()))
    }
    resultVector
  }
}

case class SpeedDataPoint(val linkId: Id[Link], val coord: Coord, val speedInMetersPerSecond: Double, val closestReferenceSpeeds:Option[ArrayBuffer[Double]])