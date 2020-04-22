package beam.utils.scripts.austin_network

import java.io.{File, PrintWriter}

import beam.sim.common.GeoUtils
import beam.utils.Statistics
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import beam.utils.scripts.austin_network.AustinNetworkSpeedMatching.{addCoord, getNetwork}
import beam.utils.scripts.austin_network.LinkReader.getLinkDataWithCapacities
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.NetworkReaderMatsimV2
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.geotools.MGC
import org.matsim.core.utils.gis.{PointFeatureFactory, ShapeFileWriter}
import org.opengis.feature.simple.SimpleFeature

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.collection.parallel.immutable.ParMap
import scala.io.Source

object AustinNetworkSpeedMatching {

  /*
  splitVectorsIntoPices: 50
  9:02:41.865 INFO  hsqldb.db.HSQLDB4AD417742A.ENGINE - dataFileCache open start
19:02:44.359 INFO  b.u.s.AustinNetworkSpeedMatching$AustinNetworkSpeedMatching - start produceSpeedDataPointFromSpeedVector.physsimNetworkDP
19:02:51.491 INFO  b.u.s.AustinNetworkSpeedMatching$AustinNetworkSpeedMatching - start produceSpeedDataPointFromSpeedVector.referenceNetworkDP
19:03:20.239 INFO  b.u.s.AustinNetworkSpeedMatching$AustinNetworkSpeedMatching - start quadTreeBounds
19:04:41.218 INFO  b.u.s.AustinNetworkSpeedMatching$AustinNetworkSpeedMatching - start closestPhysSimPointMap
19:04:50.090 INFO  b.u.s.AustinNetworkSpeedMatching$AustinNetworkSpeedMatching - end closestPhysSimPointMap
19:05:02.122 INFO  b.u.s.AustinNetworkSpeedMatching$AustinNetworkSpeedMatching - end referenceNetworkDP.foreach
19:05:03.945 INFO  b.u.s.AustinNetworkSpeedMatching$AustinNetworkSpeedMatching - numOfValues: 15139860, [0.00, 1404.50], median: 8.75, avg: 28.78, p75: 29.93, p95: 118.68, p99: 279.68, p99.95: 636.09, p99.99: 793.99, sum: 435749883.14


===

19:08:25.605 INFO  b.u.s.AustinNetworkSpeedMatching$AustinNetworkSpeedMatching - splitVectorsIntoPices: 100
19:08:33.671 INFO  hsqldb.db.HSQLDB4AD417742A.ENGINE - dataFileCache open start
19:08:36.476 INFO  b.u.s.AustinNetworkSpeedMatching$AustinNetworkSpeedMatching - start produceSpeedDataPointFromSpeedVector.physsimNetworkDP
19:09:53.092 INFO  b.u.s.AustinNetworkSpeedMatching$AustinNetworkSpeedMatching - start produceSpeedDataPointFromSpeedVector.referenceNetworkDP
19:11:06.336 INFO  b.u.s.AustinNetworkSpeedMatching$AustinNetworkSpeedMatching - start quadTreeBounds
19:13:23.884 INFO  b.u.s.AustinNetworkSpeedMatching$AustinNetworkSpeedMatching - start closestPhysSimPointMap
19:13:52.168 INFO  b.u.s.AustinNetworkSpeedMatching$AustinNetworkSpeedMatching - end closestPhysSimPointMap
19:14:23.114 INFO  b.u.s.AustinNetworkSpeedMatching$AustinNetworkSpeedMatching - end referenceNetworkDP.foreach
19:14:24.552 INFO  b.u.s.AustinNetworkSpeedMatching$AustinNetworkSpeedMatching - numOfValues: 29982860, [0.00, 1404.27], median: 8.51, avg: 28.60, p75: 29.79, p95: 118.61, p99: 279.57, p99.95: 636.02, p99.99: 793.90, sum: 857458060.88


   */


  def main(args: Array[String]): Unit = {
    // readCSV()

    val geoUtils = new GeoUtils {
      override def localCRS: String = "epsg:26910"
    }


    val austinNetworkSpeedMatching=new AustinNetworkSpeedMatching(10,geoUtils)
    //val network: Network = getNetwork("E:\\work\\austin\\output_network.xml.gz")

    val physsimNetwork=new PhyssimNetwork("E:\\work\\austin\\output_network.xml.gz",geoUtils)

    val referenceSpeedData = new ReferenceSpeedData("E:\\work\\austin\\referenceRoadSpeedsAustin.csv",geoUtils)


    val detailFilePath = "E:\\work\\austin\\austin.2015_regional_am.public.linkdetails.csv"
    val publicLinkPath = "E:\\work\\austin\\austin.2015_regional_am.public.links.csv"
    val linkCapacityData = new LinkCapacityData(detailFilePath, publicLinkPath,geoUtils)

    austinNetworkSpeedMatching.mapMatchingAlgorithm(physsimNetwork, referenceSpeedData, "E:\\work\\austin\\",linkCapacityData)
  }

  def getNetwork(filePath: String) = {
    val network = NetworkUtils.createNetwork
    val reader = new NetworkReaderMatsimV2(network)
    reader.readFile(filePath)
    network
  }

  def addCoord(coord:Coord,addCoord:Coord):Coord={
    new Coord(coord.getX+addCoord.getX,coord.getY+addCoord.getY)
  }

  def readCSV(filePath: String): Vector[String] = {
    val bufferedSource = Source.fromFile(filePath)
    var lines = bufferedSource.getLines.toVector
    bufferedSource.close
    lines
  }


class AustinNetworkSpeedMatching(splitSizeInMeters:Double,geoUtils: GeoUtils) extends LazyLogging  {



  logger.info(s"splitVectorsIntoPices: $splitSizeInMeters")



  // TODO: correct opposite direction links,



  def getQuadTreeBounds(speedDataPoints: Vector[SpeedDataPoint]): QuadTreeBounds = {
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

  def produceSpeedDataPointFromSpeedVector(speedVectors: Vector[SpeedVector]): Vector[SpeedDataPoint] = {
    val speedDataPoints = ArrayBuffer[SpeedDataPoint]()
    val dataPoints=speedVectors.par.flatMap{ speedVector =>
      speedVector.produceSpeedDataPointFromSpeedVector(splitSizeInMeters)
    }
    collection.mutable.ArrayBuffer(dataPoints.toList: _*).toVector
  }











  def createShapeFileForDataPoints(dataPoints: Vector[SpeedDataPoint], outputFile:String)  = {
   // val features = new util.ArrayList[SimpleFeature]()
   val features = ArrayBuffer[SimpleFeature]()

    val pointf: PointFeatureFactory = new PointFeatureFactory.Builder()
      .setCrs(MGC.getCRS("EPSG:4326"))
      .setName("nodes")
      //.addAttribute("Index", classOf[String])
      //.addAttribute("Size", classOf[java.lang.Integer])
      //.addAttribute("Resolution", classOf[java.lang.Integer])
      .create()

    dataPoints.foreach{ dataPoint =>
      val wsgCoord=geoUtils.utm2Wgs(dataPoint.coord)
      val coord=new com.vividsolutions.jts.geom.Coordinate(wsgCoord.getX, wsgCoord.getY)
      features+=pointf.createPoint(coord)
    }

    ShapeFileWriter.writeGeometries(features.asJava,outputFile)
    println(s"shapefile created:$outputFile")
  }

  def mapMatchingAlgorithm(physsimNetwork: PhyssimNetwork, referenceSpeedData: ReferenceSpeedData, outputFilePath: String, linkCapacityData: LinkCapacityData): Unit = {
    val physsimSpeedVector: Vector[SpeedVector] = physsimNetwork.getPhyssimSpeedVector()
    val referenceSpeedVector: Vector[SpeedVector] = referenceSpeedData.getReferenceSpeedVector()
    val linkCapacitySpeedVector: Vector[SpeedVector] = linkCapacityData.getReferenceSpeedVector()

    logger.info("start produceSpeedDataPointFromSpeedVector.physsimNetworkDP ")

    val physsimNetworkDP: Vector[SpeedDataPoint] = produceSpeedDataPointFromSpeedVector(physsimSpeedVector)

    logger.info("start produceSpeedDataPointFromSpeedVector.referenceNetworkDP ")
    val referenceNetworkDP: Vector[SpeedDataPoint] = produceSpeedDataPointFromSpeedVector(referenceSpeedVector)

    val linkCapacityDf: Vector[SpeedDataPoint] = produceSpeedDataPointFromSpeedVector(linkCapacitySpeedVector)

    createShapeFileForDataPoints(physsimNetworkDP,outputFilePath + "phySimDataPoints.shp")

    //createShapeFileForDataPoints(referenceNetworkDP,outputFilePath + "referenceNetwork.shp")

    logger.info("start quadTreeBounds ")
    // TODO: push quadtree into PhyssimNetwork
    val quadTreeBounds: QuadTreeBounds = getQuadTreeBounds(physsimNetworkDP)
    val physsimQuadTreeDP: QuadTree[SpeedDataPoint] = new QuadTree[SpeedDataPoint](quadTreeBounds.minx, quadTreeBounds.miny, quadTreeBounds.maxx, quadTreeBounds.maxy)
    physsimNetworkDP.foreach {
      speedDataPoint => physsimQuadTreeDP.put(speedDataPoint.coord.getX, speedDataPoint.coord.getY, speedDataPoint)
    }





    logger.info("start closestPhysSimPointMap ")
    val closestPhysSimPointMap=referenceNetworkDP.par.map{ referenceSpeedDataPoint =>
      (referenceSpeedDataPoint -> physsimQuadTreeDP.getClosest(referenceSpeedDataPoint.coord.getX, referenceSpeedDataPoint.coord.getY))
    }.toMap
    logger.info("end closestPhysSimPointMap ")

    matchNetwork(referenceNetworkDP,closestPhysSimPointMap, _.closestReferenceData)
    matchNetwork(linkCapacityDf,closestPhysSimPointMap, _.closestCapacityData)




    logger.info("end referenceNetworkDP.foreach ")
   // createShapeFileForDataPoints(selectedPhysSimPointsForDebugging,outputFilePath + "physSimNetworkDebugPoints.shp")
   // createShapeFileForDataPoints(selectedReferencePointsForDebugging,outputFilePath + "referenceNetworkDebugPoints.shp")


    writeComparisonOSMVsReferenceSpeedsByLink(outputFilePath, physsimQuadTreeDP, physsimNetwork.network)

    //    //TODO: write link_id,capacity,free_speed,length

  }

  //TODO: rename SpeedDataPoint
  def matchNetwork(dataPoints: scala.Vector[SpeedDataPoint],closestPhysSimPointMap: ParMap[SpeedDataPoint, SpeedDataPoint],attachDataFunction: SpeedDataPoint =>  Option[ArrayBuffer[Id[Link]]])={
    val distanceArray: ArrayBuffer[Double] = ArrayBuffer()
    val selectedPhysSimPointsForDebugging: ArrayBuffer[SpeedDataPoint]=new ArrayBuffer()
    val selectedReferencePointsForDebugging: ArrayBuffer[SpeedDataPoint]=new ArrayBuffer()
    dataPoints.foreach {
      referenceSpeedDataPoint =>

        //val closestPhysSimNetworkPoint = physsimQuadTreeDP.getClosest(referenceSpeedDataPoint.coord.getX, referenceSpeedDataPoint.coord.getY)
        val closestPhysSimNetworkPoint =closestPhysSimPointMap.get(referenceSpeedDataPoint).get

        //val distanceInMeters= geoUtils.distLatLon2Meters(closestPhysSimNetworkPoint.coord,referenceSpeedDataPoint.coord)
        val distanceInMeters = geoUtils.distUTMInMeters(closestPhysSimNetworkPoint.coord, referenceSpeedDataPoint.coord)
        distanceArray += distanceInMeters
        if (distanceInMeters < 100) {
          attachDataFunction(closestPhysSimNetworkPoint).get+= referenceSpeedDataPoint.linkId
        } else {
          selectedPhysSimPointsForDebugging+=closestPhysSimNetworkPoint
          selectedReferencePointsForDebugging+=referenceSpeedDataPoint
        }
    }


    logger.info(Statistics(distanceArray).toString)
  }

  private def writeComparisonOSMVsReferenceSpeedsByLink(outputFilePath: String, physsimQuadTreeDP: QuadTree[SpeedDataPoint], network: Network) = {
    var pw = new PrintWriter(new File(outputFilePath + "comparisonOSMVsReferenceSpeedsByLink.csv"))
    pw.write(s"linkId,attributeOrigType,physsimSpeed,medianReferenceSpeed\n")

    val linkIdReferenceSpeedTuples: List[(Id[Link], Double)] = physsimQuadTreeDP.values().asScala.toVector.toList.flatMap { physsimSpeedDataPoint =>
      val closestReferenceSpeeds: ArrayBuffer[Double] = physsimSpeedDataPoint.closestReferenceData.get
      closestReferenceSpeeds.map { referenceSpeed =>
        (physsimSpeedDataPoint.linkId, referenceSpeed)
      }
    }

    val linkIdReferenceSpeedGroups: Map[Id[Link], List[Double]] = linkIdReferenceSpeedTuples.groupBy(_._1).mapValues { list =>
      list.map {
        case (_, referenceSpeed) => referenceSpeed
      }
    }

    val linkReferenceSpeeds=mutable.HashMap[Id[Link], Double]()

    linkIdReferenceSpeedGroups.foreach {
      case (linkId, referenceSpeeds) if referenceSpeeds.nonEmpty =>

        var sortedReferenceSpeed = referenceSpeeds.sorted.toIndexedSeq

        val averageReferenceSpeed = sortedReferenceSpeed((sortedReferenceSpeed.size / 2))
        linkReferenceSpeeds.put(linkId,averageReferenceSpeed)
    }

    linkReferenceSpeeds.foreach{
      case (linkId,averageReferenceSpeed) =>
        val link=network.getLinks.get(linkId)
        getOppositeLink(link) match {
          case Some(oppositeLink) =>
            linkReferenceSpeeds.put(oppositeLink.getId,averageReferenceSpeed)
          case None =>
        }
    }

    linkReferenceSpeeds.foreach{
      case (linkId,averageReferenceSpeed) =>
        writeUpdatedSpeed(network, pw, linkId, averageReferenceSpeed)
    }

    pw.close
  }

  private def getOppositeLink(link:Link):Option[Link]={
    val inLinks=link.getFromNode.getInLinks.values()
    val outLinks=link.getToNode.getOutLinks.values()
    inLinks.asScala.toVector.find(linkId => outLinks.contains(linkId))
  }

  private def writeUpdatedSpeed(network: Network, pw: PrintWriter, linkId: Id[Link], averageReferenceSpeed: Double) = {
    pw.write(s"${linkId},${network.getLinks.get(linkId).getAttributes.getAttribute("type")},${network.getLinks.get(linkId).getFreespeed},$averageReferenceSpeed\n")
  }

  private def writeComparisonOSMVsReferenceSpeedsDataPoints(outputFilePath: String, physsimQuadTreeDP: QuadTree[SpeedDataPoint], network: Network) = {
    var pw = new PrintWriter(new File(outputFilePath + "comparisonOSMVsReferenceSpeedsDataPoints.csv"))
    pw.write(s"linkId,attributeOrigType,physsimSpeed,referenceSpeed\n")

    physsimQuadTreeDP.values().asScala.toVector.toList.foreach { physsimSpeedDataPoint =>
      physsimSpeedDataPoint.closestReferenceData.get.foreach { referenceSpeed =>
        pw.write(s"${physsimSpeedDataPoint.linkId},${network.getLinks.get(physsimSpeedDataPoint.linkId).getAttributes.getAttribute("type")},${physsimSpeedDataPoint.speedInMetersPerSecond},$referenceSpeed\n")
      }

    }
    pw.close
  }
}}



case class SpeedVector(val linkId: Id[Link], val startCoord: Coord, val endCoord: Coord,geoUtils: GeoUtils) {
  def produceSpeedDataPointFromSpeedVector(splitSizeInMeters: Double): ArrayBuffer[SpeedDataPoint] = {

    val numberOfPieces:Int=Math.max((geoUtils.distUTMInMeters(startCoord,endCoord)/splitSizeInMeters).toInt,1)

    val xDeltaVector = (endCoord.getX - startCoord.getX) / numberOfPieces
    val yDeltaVector = (endCoord.getY - startCoord.getY) / numberOfPieces

    val resultVector: ArrayBuffer[SpeedDataPoint] = collection.mutable.ArrayBuffer()

    for (i <- 0 to numberOfPieces) {
      resultVector += SpeedDataPoint(linkId, new Coord(startCoord.getX + i * xDeltaVector, startCoord.getY + i * yDeltaVector), Some(ArrayBuffer()))
    }
    resultVector
  }




}

case class SpeedDataPoint(val linkId: Id[Link], val coord: Coord, val closestReferenceData: Option[ArrayBuffer[Id[Link]]],val closestCapacityData: Option[ArrayBuffer[Id[Link]]])



class ReferenceSpeedData(filePath: String, geoUtils: GeoUtils) {

  val wsgShift: Coord =new Coord(-97.758288-(-97.759245),30.225124-(30.225251))

  val speeds=mutable.HashMap[Id[Link],Double]()

  def getReferenceSpeedVector(): Vector[SpeedVector] = {
    val speedVectors: ArrayBuffer[SpeedVector] = ArrayBuffer()
    val lines = AustinNetworkSpeedMatching.readCSV(filePath)


    for (line <- lines.drop(1)) {
      //
      val columns = line.split("\",")
      val geometry = columns(0)
      //println(geometry)
      val remainingColumns = columns(1).split(",")
      val objectId = Id.createLinkId(remainingColumns(0))
      val freeFlowSpeedInMetersPerSecond = remainingColumns(17).toDouble * 0.44704 // conversion miles per hour to meters per second

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


      vetorCoords.foreach { case (startCoord, endCoord) =>
        //speedVectors += SpeedVector(objectId, startCoord,endCoord, freeFlowSpeedInMetersPerSecond)
        val updatedWgsStartCoord=addCoord(startCoord,wsgShift)
        val updatedWgsEndCoord=addCoord(endCoord,wsgShift)

        speedVectors += SpeedVector(objectId, geoUtils.wgs2Utm(updatedWgsStartCoord), geoUtils.wgs2Utm(updatedWgsEndCoord),geoUtils)
        speeds.put(objectId,freeFlowSpeedInMetersPerSecond)
      }

    }
    speedVectors.toVector
  }



}


class PhyssimNetwork(filePath: String, geoUtils: GeoUtils) {

  val wsgShift: Coord =addCoord(new Coord(-96.59322-(-96.59647),30.87545-(30.87585)),new Coord(-97.774010-(-97.772666),30.306692-(30.306515)))

  val network: Network = getNetwork(filePath)

  def getPhyssimSpeedVector(): Vector[SpeedVector] = {
    val speedVectors: ArrayBuffer[SpeedVector] = ArrayBuffer()

    network.getLinks.values().asScala.toVector.map { link =>
      //speedVectors += SpeedVector(link.getId, geoUtils.utm2Wgs(link.getFromNode.getCoord), geoUtils.utm2Wgs(link.getToNode.getCoord), link.getFreespeed)
      val startCoordWsg = addCoord(geoUtils.utm2Wgs(link.getFromNode.getCoord), wsgShift)
      val endCoordWsg = addCoord(geoUtils.utm2Wgs(link.getToNode.getCoord), wsgShift)
      SpeedVector(link.getId, geoUtils.wgs2Utm(startCoordWsg), geoUtils.wgs2Utm(endCoordWsg),geoUtils)
    }
  }







}

class LinkCapacityData(detailFilePath: String, publicLinkPath: String, geoUtils: GeoUtils) {

  val linkCapacityData: Map[Id[Link], LinkDetails] = LinkReader.getLinkDataWithCapacities(detailFilePath, publicLinkPath).map(linkDetails => linkDetails.linkChainId->linkDetails).toMap

  def getReferenceSpeedVector(): Vector[SpeedVector] = {
    linkCapacityData.flatMap { case (linkId, linkDetails) =>
      val coordPairs = linkDetails.geometry.sliding(2, 2)
      coordPairs.map { coordPair =>
        SpeedVector(linkId, geoUtils.wgs2Utm(coordPair(0)), geoUtils.wgs2Utm(coordPair(1)), geoUtils)
      }
    }.toVector
  }


}