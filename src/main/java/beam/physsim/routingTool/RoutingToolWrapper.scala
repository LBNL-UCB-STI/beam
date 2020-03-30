package beam.physsim.routingTool
import java.io.File
import java.nio.file.Paths

import beam.sim.BeamServices
import com.google.common.io.Files
import com.google.inject.Inject

import scala.sys.process.Process

trait RoutingToolWrapper {
  def createCCH(): Unit
}

class RoutingToolWrapperImpl1 @Inject()(beamServices: BeamServices)
      extends InternalRTWrapper(beamServices.beamConfig.beam.routing.r5.osmFile)

class InternalRTWrapper (private val pbfPath : String) extends RoutingToolWrapper {
  private val toolDockerImage = "rooting-tool"
  private val basePath = "/routing-framework/Build/Devel"
  private val convertGraph = s"$basePath/RawData/ConvertGraph"
  private val createODPairs = s"$basePath/RawData/GenerateODPairs"
  private val assignTraffic = s"$basePath/Launchers/AssignTraffic"

  private val tempDirPath = System.getProperty("java.io.tmpdir")
  private val tempDir = new File("/tmp/rt")
  tempDir.mkdirs()

  override def createCCH(): Unit = {
    val pbfName = pbfPath.split("/").last
    val pbfNameWithoutExtension = pbfName.replace(".osm.pbf", "")
    val pbfInTempDirPath = Paths.get("/work", pbfNameWithoutExtension).toString

    val graphPath = pbfInTempDirPath + "_graph"
    val graphPathWithExtension = pbfInTempDirPath + "_graph.gr.bin"

    Files.copy(new File(pbfPath), new File(tempDir + "/" + pbfName))

    val convertGraphOutput = Process(s"""
        |docker run --rm
        | -v $tempDir:/work
        | $toolDockerImage
        | $convertGraph
        | -s osm
        | -i $pbfInTempDirPath
        | -d binary
        | -o $graphPath
        | -scc -a way_id capacity coordinate free_flow_speed lat_lng length num_lanes travel_time vertex_id
      """.stripMargin.replace("\n", ""))

    println(convertGraphOutput.!!)

    val odPairsFile = pbfInTempDirPath + "_odpairs"

    val createODPairsOutput = Process(s"""
        |docker run --rm
        | -v $tempDir:/work
        | $toolDockerImage
        | $createODPairs
        | -g $graphPathWithExtension
        | -n 100
        | -o $odPairsFile -d 10 15 20 25 30 -geom
      """.stripMargin.replace("\n", ""))

    println(createODPairsOutput.!!)

    val assignTrafficOutput = Process(s"""
        |docker run --rm
        | -v $tempDir:/work
        | $toolDockerImage
        | $assignTraffic
        | -g $graphPathWithExtension
        | -d $odPairsFile.csv
        | -p 1 -n 10 -a Dijkstra -o random
        | -i
        | -flow ${pbfInTempDirPath}_flow_10
        | -dist ${pbfInTempDirPath}_dist_10
        | -stat ${pbfInTempDirPath}_stat_10
      """.stripMargin.replace("\n", ""))

    println(assignTrafficOutput.!!)
  }

}

object Starter extends App{
  new InternalRTWrapper("/Users/e.zuykin/Downloads/iran-latest.osm.pbf").createCCH()
}