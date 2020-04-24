package beam.physsim.routingTool
import java.io.{BufferedWriter, File, FileWriter}
import java.nio.file.Paths

import beam.sim.BeamServices
import com.google.common.io.Files
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging

import scala.sys.process.Process

trait RoutingToolWrapper {
  def generateGraph(): File
  def generateOd(): Unit
  def generateOd(ods: java.util.List[akka.japi.Pair[java.lang.Long, java.lang.Long]]): Unit
  def assignTraffic(): (File, File, File)
}

class RoutingToolWrapperImpl1 @Inject()(beamServices: BeamServices)
    extends InternalRTWrapper(beamServices.beamConfig.beam.routing.r5.osmFile)

class InternalRTWrapper(private val pbfPath: String) extends RoutingToolWrapper with LazyLogging {
  private val toolDockerImage = "rooting-tool"
  private val basePath = "/routing-framework/Build/Devel"
  private val convertGraphLauncher = s"$basePath/RawData/ConvertGraph"
  private val createODPairsLauncher = s"$basePath/RawData/GenerateODPairs"
  private val assignTrafficLauncher = s"$basePath/Launchers/AssignTraffic"

  private val pbfName = pbfPath.split("/").last
  private val pbfNameWithoutExtension = pbfName.replace(".osm.pbf", "")
  private val pbfInTempDirPath = Paths.get("/work", pbfNameWithoutExtension).toString

  private val tempDir = new File("/tmp/rt")
  tempDir.mkdirs()
  Files.copy(new File(pbfPath), new File(tempDir + "/" + pbfName))

  private val graphPath = pbfInTempDirPath + "_graph"
  private val graphPathWithExtension = pbfInTempDirPath + "_graph.gr.bin"

  private val odPairsFileInTempDir = pbfInTempDirPath + "_odpairs"

  override def generateGraph(): File = {
    val convertGraphOutput = Process(s"""
                                        |docker run --rm
                                        | -v $tempDir:/work
                                        | $toolDockerImage
                                        | $convertGraphLauncher
                                        | -s osm
                                        | -i $pbfInTempDirPath
                                        | -d binary
                                        | -o $graphPath
                                        | -scc -a way_id capacity coordinate free_flow_speed lat_lng length num_lanes travel_time vertex_id
      """.stripMargin.replace("\n", ""))

    convertGraphOutput.lineStream.foreach(logger.info(_))
    Paths.get(tempDir.getAbsolutePath, pbfNameWithoutExtension + "_graph.gr.bin").toFile
  }

  override def generateOd(): Unit = {
    val createODPairsOutput = Process(s"""
                                         |docker run --rm
                                         | -v $tempDir:/work
                                         | $toolDockerImage
                                         | $createODPairsLauncher
                                         | -g $graphPathWithExtension
                                         | -n 1000
                                         | -o $odPairsFileInTempDir -d 10 15 20 25 30 -geom
      """.stripMargin.replace("\n", ""))

    createODPairsOutput.lineStream.foreach(logger.info(_))
  }

  override def generateOd(ods: java.util.List[akka.japi.Pair[java.lang.Long, java.lang.Long]]): Unit = {
    val odPairsFile = new File(tempDir + "/" + pbfNameWithoutExtension + "_odpairs.csv")
    val writer = new BufferedWriter(new FileWriter(odPairsFile))
    writer.write("origin,destination")
    writer.newLine()

    ods.forEach { a =>
      writer.write(s"${a.first},${a.second}")
      writer.newLine()
    }

    writer.close()
  }

  override def assignTraffic(): (File, File, File) = {
    val assignTrafficOutput = Process(s"""
                                         |docker run --rm
                                         | --memory-swap -1
                                         | -v $tempDir:/work
                                         | $toolDockerImage
                                         | $assignTrafficLauncher
                                         | -g $graphPathWithExtension
                                         | -d $odPairsFileInTempDir.csv
                                         | -p 1 -n 10 -a Dijkstra -o random
                                         | -i
                                         | -flow ${pbfInTempDirPath}_flow_10
                                         | -dist ${pbfInTempDirPath}_dist_10
                                         | -stat ${pbfInTempDirPath}_stat_10
      """.stripMargin.replace("\n", ""))

    assignTrafficOutput.lineStream.foreach(logger.info(_))

    (
      new File(s"$tempDir/${pbfNameWithoutExtension}_flow_10.csv"),
      new File(s"$tempDir/${pbfNameWithoutExtension}_dist_10.csv"),
      new File(s"$tempDir/${pbfNameWithoutExtension}_stat_10.csv")
    )
  }
}

object Starter extends App {
  val wrapper = new InternalRTWrapper("/Users/e.zuykin/Downloads/iran-latest.osm.pbf")
  wrapper.generateGraph()
  wrapper.generateOd()
  wrapper.assignTraffic()
}
