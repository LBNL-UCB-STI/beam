package beam.utils

import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.config.BeamConfig
import com.conveyal.r5.streets.EdgeStore
import com.typesafe.config.Config

import scala.collection.mutable
import scala.language.postfixOps

/**
  * @author Bhavya Latha Bandaru.
  * Loads network and then exports required data to an output file.
  */
object NetworkEdgeOutputGenerator extends App {

  if (args.isEmpty) throw new Exception("No arguments passed . Required arguments : 1 - path to the conf file")
  val configFile = args(0)

  val config: Config = BeamConfigUtils
    .parseFileSubstitutingInputDirectory(configFile)
    .resolve()
  val beamConfig: BeamConfig = BeamConfig(config)
  private lazy val networkCoordinator = DefaultNetworkCoordinator(beamConfig)
  networkCoordinator.loadNetwork()
  private val streetLayer = networkCoordinator.transportNetwork.streetLayer
  private val edgeStore: EdgeStore = streetLayer.edgeStore

  private val bikeAccessibleEdges: mutable.ListBuffer[EdgeStore#Edge] = mutable.ListBuffer.empty[EdgeStore#Edge]
  private val pedestrianAccessibleEdges: mutable.ListBuffer[EdgeStore#Edge] = mutable.ListBuffer.empty[EdgeStore#Edge]

  reset()
  classifyEdges()
  writeAccessibleEdgesToFile()
  reset()

  private def classifyEdges(): Unit = {
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

  private def writeAccessibleEdgesToFile(): Unit = {
    val csvHeader = "id,from,to,freeSpeedInMetersPerSecond,lengthInM,travelTimeInSeconds"
    val outputDirectory = beamConfig.beam.routing.r5.directory
    val bikeAccessibleEdgesOutputFilePath = outputDirectory + "/" + "bikeAccessibleEdges.csv.gz"
    val walkAccessibleEdgesOutputFilePath = outputDirectory + "/" + "walkAccessibleEdges.csv.gz"

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
