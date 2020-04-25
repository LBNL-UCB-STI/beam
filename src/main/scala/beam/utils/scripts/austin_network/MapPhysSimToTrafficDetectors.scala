package beam.utils.scripts.austin_network


import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

object MapPhysSimToTrafficDetectors {

  def main(args: Array[String]): Unit = {
    import AustinUtils._
    val splitSizeInMeters=1
    val trafficDetectors=VisualizeVolumeStations.getCoordinatesTrafficCountsFile("E:\\work\\austin\\Traffic_Detectors.csv",0,1)
    val physsimNetwork=getPhysSimNetwork("C:\\Users\\owner\\IdeaProjects\\beam\\output\\austin\\austin-prod-1k-activities__2020-04-25_07-21-44_tmc\\output_network.xml.gz")

    val physSimDataPoints=physsimNetwork.getLinks.values().asScala.toVector.flatMap{ link =>
      DataVector(DataId(link.toString),link.getFromNode.getCoord,link.getToNode.getCoord,false).produceSpeedDataPointFromSpeedVector(splitSizeInMeters)
    }

    val quadTree=getQuadTree(physSimDataPoints)
    val trafficDataPoints=trafficDetectors.map{ case (id,coord) =>
      DataPoint(id,coord,ArrayBuffer.empty)
    }

    assignDataPointsToQuadTree(quadTree,trafficDataPoints)



  }

}
