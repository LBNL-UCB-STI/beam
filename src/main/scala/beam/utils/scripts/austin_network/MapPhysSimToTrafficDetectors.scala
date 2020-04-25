package beam.utils.scripts.austin_network

import beam.utils.scripts.austin_network.VisualizeVolumeStations.generateTrafficCountsFile
import scala.collection.JavaConverters._

object MapPhysSimToTrafficDetectors {

  def main(args: Array[String]): Unit = {
    val splitSizeInMeters=1
    val trafficDetectors=VisualizeVolumeStations.getCoordinatesTrafficCountsFile("E:\\work\\austin\\Traffic_Detectors.csv",0,1)
    val physsimNetwork=AustinUtils.getPhysSimNetwork("C:\\Users\\owner\\IdeaProjects\\beam\\output\\austin\\austin-prod-1k-activities__2020-04-25_07-21-44_tmc\\output_network.xml.gz")

    val physSimDataPoints=physsimNetwork.getLinks.values().asScala.toVector.map{ link =>
      DataVector(DataId(link.toString),link.getFromNode.getCoord,link.getToNode.getCoord,false).produceSpeedDataPointFromSpeedVector(splitSizeInMeters)
    }



  }

}
