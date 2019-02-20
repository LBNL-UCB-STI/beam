package beam.utils
import beam.router.r5.NetworkCoordinator
import beam.sim.BeamServices
import com.conveyal.r5.streets.EdgeStore
import javax.inject.Inject

import scala.collection.mutable
import scala.language.postfixOps

/**
  * @author Bhavya Latha Bandaru.
  * Loads network and then exports required data to an output file.
  */
class NetworkEdgeOutputGenerator @Inject()(beamServices: BeamServices,networkCoordinator: NetworkCoordinator) {

  val streetLayer = networkCoordinator.transportNetwork.streetLayer
  val edgeStore: EdgeStore = streetLayer.edgeStore

  private val bikeAccessibleEdges: mutable.ListBuffer[EdgeStore#Edge] = mutable.ListBuffer.empty[EdgeStore#Edge]
  private val pedestrianAccessibleEdges: mutable.ListBuffer[EdgeStore#Edge] = mutable.ListBuffer.empty[EdgeStore#Edge]

  def init(): Unit = {
    reset()
    classifyEdges
    writeAccessibleEdgesToFile
    reset()
  }

  private def classifyEdges = {
    for (i <- 0 until edgeStore.nEdges) {
      val edgeCursor: EdgeStore#Edge = streetLayer.edgeStore.getCursor(i)
      if (edgeCursor.getFlag(EdgeStore.EdgeFlag.ALLOWS_BIKE)) {
        // save to bike edges collection
        bikeAccessibleEdges.append(edgeCursor)
      }
      if (edgeCursor.getFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN)) {
        // save to pedestrian edges collection
        pedestrianAccessibleEdges.append(edgeCursor)
      }
    }
  }

  private def getDataFromEdges(edgesArray: mutable.ListBuffer[EdgeStore#Edge]) = {
    edgesArray map { edge =>
      s"${edge.getEdgeIndex},${edge.getFromVertex},${edge.getToVertex},${edge.getSpeed},${edge.getLengthM},${edge.getLengthM / edge.getSpeed}"
    } mkString "\n"
  }

  private def writeAccessibleEdgesToFile = {
    val csvHeader = "id,from,to,freeSpeed(m/s),length(m),travelTime(s)"
    val outputDirectory = beamServices.beamConfig.beam.routing.r5.directory
    val bikeAccessibleEdgesOutputFilePath = outputDirectory + "/" + "bikeAccessibleEdges.csv"
    val walkAccessibleEdgesOutputFilePath = outputDirectory + "/" + "walkAccessibleEdges.csv"

    val bikeAccessibleEdgesData = getDataFromEdges(this.bikeAccessibleEdges)
    FileUtils.writeToFile(bikeAccessibleEdgesOutputFilePath, Some(csvHeader), bikeAccessibleEdgesData, None)

    val pedestrianAccessibleEdgesData = getDataFromEdges(this.pedestrianAccessibleEdges)
    FileUtils.writeToFile(walkAccessibleEdgesOutputFilePath, Some(csvHeader), pedestrianAccessibleEdgesData, None)
  }

  def reset(): Unit = {
    this.bikeAccessibleEdges.clear()
    this.pedestrianAccessibleEdges.clear()
  }

}
