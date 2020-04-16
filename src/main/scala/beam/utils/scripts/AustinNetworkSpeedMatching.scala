package beam.utils.scripts

import java.io.{File, PrintWriter}

import beam.sim.common.GeoUtils
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.network.{Link, Network}
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

    val network: Network = getNetwork("E:\\work\\austin\\output_network.xml.gz")
    val physsimSpeedVector: ArrayBuffer[SpeedVector] = getPhyssimSpeedVector(network)
    val referenceSpeedVector: ArrayBuffer[SpeedVector] = getReferenceSpeedVector("E:\\work\\austin\\referenceRoadSpeedsAustin.csv")

    mapMatchingAlgorithm(physsimSpeedVector, referenceSpeedVector, network, "E:\\work\\austin\\")
  }


  def readCSV(filePath: String): Vector[String] = {
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

  def getPhyssimSpeedVector(network: Network): ArrayBuffer[SpeedVector] = {
    val speedVectors: ArrayBuffer[SpeedVector] = ArrayBuffer()


    val geoUtils = new GeoUtils {
      override def localCRS: String = "epsg:26910"
    }

    //TODO: do coordinate conversion!
    network.getLinks.values().asScala.toVector.foreach { link =>
      speedVectors += SpeedVector(link.getId, geoUtils.utm2Wgs(link.getFromNode.getCoord), geoUtils.utm2Wgs(link.getToNode.getCoord), link.getFreespeed)
    }

    speedVectors
  }


  private def getNetwork(filePath: String) = {
    val network = NetworkUtils.createNetwork
    val reader = new NetworkReaderMatsimV2(network)
    reader.readFile(filePath)
    network
  }

  def getReferenceSpeedVector(filePath: String): ArrayBuffer[SpeedVector] = {
    val speedVectors: ArrayBuffer[SpeedVector] = ArrayBuffer()
    val lines = readCSV(filePath)


    for (line <- lines.drop(1)) {
      //
      val columns = line.split("\",")
      val geometry = columns(0)
      //println(geometry)
      val remainingColumns = columns(1).split(",")
      val objectId = Id.createLinkId(remainingColumns(0))
      val freeFlowSpeedInMetersPerSecond = remainingColumns(17).toDouble * 0.44704

      //MULTILINESTRING ((-97.682173979079 30.311113592404, -97.682016564442 30.311085638311, -97.681829312011 30.311093784592, -97.681515441419 30.311171453739))

      def getCoordinatesList(linkString: String): List[(Coord, Coord)] = {
        val numbers = linkString.split("[ ,]").filterNot(_.isEmpty)
        val pairs = numbers.sliding(2, 2).toList
        val coordinates = pairs.map { pair => new Coord(pair(0).toDouble, pair(1).toDouble) }
        val links = coordinates.sliding(2, 1).toList.map(pair => pair(0) -> pair(1))
        links
      }

      val vectors = geometry.replace("\"MULTILINESTRING (", "").replace("))", ")").split("\\), \\(")
      val linkStrings = vectors.map { x => x.replace("(", "").replace(")", "") }
      val vetorCoords = linkStrings.map(x => getCoordinatesList(x)).flatten //.foreach( x => println(x))


      vetorCoords.foreach { x =>
        speedVectors += SpeedVector(objectId, x._1, x._2, freeFlowSpeedInMetersPerSecond)
      }




      //  geometry.split(",").foreach{
      //   coord => val coords=coord.split(" ")

      // }

      // SpeedVector(link.getId,link.getFromNode.getCoord,link.getToNode.getCoord,link.getFreespeed)-97.682173979079 30.311113592404 -> -97.682016564442 30.311085638311

      //-97.682016564442 30.311085638311 -> -97.681829312011 30.311093784592

      //-97.681829312011 30.311093784592 -> -97.681515441419 30.311171453739


      //SPEED_LIMIT

      //val startCoordX=geoUtils.utm2Wgs(new Coord(x.))
      //val long=
      // println(linkId)


    }
    speedVectors
  }


  def mapMatchingAlgorithm(physsimSpeedVector: ArrayBuffer[SpeedVector], referenceSpeedVector: ArrayBuffer[SpeedVector], network: Network, outputFilePath: String): Unit = {

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


    writeComparisonOSMVsReferenceSpeedsDataPoints(outputFilePath, physsimQuadTreeDP, network)


    writeComparisonOSMVsReferenceSpeedsByLink(outputFilePath, physsimQuadTreeDP, network)


  }

  private def writeComparisonOSMVsReferenceSpeedsByLink(outputFilePath: String, physsimQuadTreeDP: QuadTree[SpeedDataPoint], network: Network) = {
    var pw = new PrintWriter(new File(outputFilePath + "comparisonOSMVsReferenceSpeedsByLink.csv"))
    pw.write(s"linkId,attributeOrigType,physsimSpeed,medianReferenceSpeed\n")

    val linkIdReferenceSpeedTuples: List[(Id[Link], Double)] = physsimQuadTreeDP.values().asScala.toVector.toList.flatMap { physsimSpeedDataPoint =>
      val closestReferenceSpeeds: ArrayBuffer[Double] = physsimSpeedDataPoint.closestReferenceSpeeds.get
      closestReferenceSpeeds.map { referenceSpeed =>
        (physsimSpeedDataPoint.linkId, referenceSpeed)
      }
    }

    val linkIdReferenceSpeedGroups: Map[Id[Link], List[Double]] = linkIdReferenceSpeedTuples.groupBy(_._1).mapValues { list =>
      list.map {
        case (_, referenceSpeed) => referenceSpeed
      }
    }

    linkIdReferenceSpeedGroups.foreach {
      case (linkId, referenceSpeeds) if referenceSpeeds.nonEmpty =>

        var sortedReferenceSpeed = referenceSpeeds.sorted.toIndexedSeq

        val averageReferenceSpeed = sortedReferenceSpeed((sortedReferenceSpeed.size / 2))

        pw.write(s"${linkId},${network.getLinks.get(linkId).getAttributes.getAttribute("type")},${network.getLinks.get(linkId).getFreespeed},$averageReferenceSpeed\n")
    }

    pw.close
  }

  private def writeComparisonOSMVsReferenceSpeedsDataPoints(outputFilePath: String, physsimQuadTreeDP: QuadTree[SpeedDataPoint], network: Network) = {
    var pw = new PrintWriter(new File(outputFilePath + "comparisonOSMVsReferenceSpeedsDataPoints.csv"))
    pw.write(s"linkId,attributeOrigType,physsimSpeed,referenceSpeed\n")

    physsimQuadTreeDP.values().asScala.toVector.toList.foreach { physsimSpeedDataPoint =>
      physsimSpeedDataPoint.closestReferenceSpeeds.get.foreach { referenceSpeed =>
        pw.write(s"${physsimSpeedDataPoint.linkId},${network.getLinks.get(physsimSpeedDataPoint.linkId).getAttributes.getAttribute("type")},${physsimSpeedDataPoint.speedInMetersPerSecond},$referenceSpeed\n")
      }

    }
    pw.close
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