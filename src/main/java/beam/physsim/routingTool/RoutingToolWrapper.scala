package beam.physsim.routingTool
import java.io.{BufferedWriter, File, FileWriter}
import java.nio.file.Paths

import beam.sim.BeamServices
import com.google.common.io.Files
import com.typesafe.scalalogging.LazyLogging

import scala.sys.process.Process

trait RoutingToolWrapper {
  def generateGraph(): File
  def generateOd(): Unit
  def generateOd(iteration: Int, hour: Int, ods: java.util.List[akka.japi.Pair[java.lang.Long, java.lang.Long]]): Unit
  def assignTraffic(iteration: Int, hour: Int): (File, File, File)
}

class RoutingToolWrapperImpl(beamServices: BeamServices, tempDir: String = "/tmp/rt")
    extends InternalRTWrapper(
      new File(beamServices.beamConfig.beam.routing.r5.directory)
        .list()
        .filter(_.endsWith("osm.pbf"))
        .head,
      tempDir
    )

class InternalRTWrapper(private val pbfPath: String, private val tempDirPath: String)
    extends RoutingToolWrapper
    with LazyLogging {

  private val toolDockerImage = "rooting-tool"
  private val basePath = "/routing-framework/Build/Devel"
  private val convertGraphLauncher = s"$basePath/RawData/ConvertGraph"
  private val createODPairsLauncher = s"$basePath/RawData/GenerateODPairs"
  private val assignTrafficLauncher = s"$basePath/Launchers/AssignTraffic"

  private val pbfName = pbfPath.split("/").last
  private val pbfNameWithoutExtension = pbfName.replace(".osm.pbf", "")
  private val pbfPathInContainer = Paths.get("/work", pbfNameWithoutExtension)

  private val tempDir = new File(tempDirPath)
  tempDir.mkdirs()
  Files.copy(new File(pbfPath), new File(tempDir + "/" + pbfName))

  private val graphPathInContainer = Paths.get("/work", "graph.gr.bin")
  private val graphPathInTempDir = Paths.get(tempDirPath, "graph.gr.bin")

  private val itHourRelatedPath = (env: String, it: Int, hour: Int, file: String) =>
    Paths.get(env, it.toString, hour.toString, file)
  private val odPairsFileInContainer = (iteration: Int, hour: Int) =>
    itHourRelatedPath("/work", iteration, hour, "odpairs.csv")
  private val odPairsFileInTempDir = (iteration: Int, hour: Int) =>
    itHourRelatedPath(tempDirPath, iteration, hour, "odpairs.csv")

  override def generateGraph(): File = {
    val convertGraphOutput = Process(s"""
                                        |docker run --rm
                                        | -v $tempDir:/work
                                        | $toolDockerImage
                                        | $convertGraphLauncher
                                        | -s osm
                                        | -i $pbfPathInContainer
                                        | -d binary
                                        | -o ${graphPathInContainer.toString.replace(".gr.bin", "")}
                                        | -scc -a way_id capacity coordinate free_flow_speed lat_lng length num_lanes travel_time vertex_id
      """.stripMargin.replace("\n", ""))

    convertGraphOutput.lineStream.foreach(logger.info(_))
    graphPathInTempDir.toFile
  }

  override def generateOd(): Unit = {
    val createODPairsOutput = Process(s"""
                                         |docker run --rm
                                         | -v $tempDir:/work
                                         | $toolDockerImage
                                         | $createODPairsLauncher
                                         | -g $graphPathInContainer
                                         | -n 1000
                                         | -o ${odPairsFileInContainer(0, 0)} -d 10 15 20 25 30 -geom
      """.stripMargin.replace("\n", ""))

    createODPairsOutput.lineStream.foreach(logger.info(_))
  }

  override def generateOd(
    iteration: Int,
    hour: Int,
    ods: java.util.List[akka.japi.Pair[java.lang.Long, java.lang.Long]]
  ): Unit = {
    Paths.get(tempDirPath, iteration.toString, hour.toString).toFile.mkdirs()
    val odPairsFile = odPairsFileInTempDir(iteration, hour).toFile

    val writer = new BufferedWriter(new FileWriter(odPairsFile))
    writer.write("origin,destination")
    writer.newLine()

    ods.forEach { a =>
      writer.write(s"${a.first},${a.second}")
      writer.newLine()
    }

    writer.close()
  }

  override def assignTraffic(iteration: Int, hour: Int): (File, File, File) = {
    val flowPath = itHourRelatedPath("/work", iteration, hour, "flow")
    val distPath = itHourRelatedPath("/work", iteration, hour, "dist")
    val statPath = itHourRelatedPath("/work", iteration, hour, "stat")
    val assignTrafficOutput = Process(s"""
                                         |docker run --rm
                                         | --memory-swap -1
                                         | -v $tempDir:/work
                                         | $toolDockerImage
                                         | $assignTrafficLauncher
                                         | -g $graphPathInContainer
                                         | -d ${odPairsFileInContainer(iteration, hour)}
                                         | -p 1 -n 10 -o random
                                         | -i
                                         | -flow $flowPath
                                         | -dist $distPath
                                         | -stat $statPath
      """.stripMargin.replace("\n", ""))

    assignTrafficOutput.lineStream.foreach(logger.info(_))

    (
      itHourRelatedPath(tempDirPath, iteration, hour, "flow.csv").toFile,
      itHourRelatedPath(tempDirPath, iteration, hour, "dist.csv").toFile,
      itHourRelatedPath(tempDirPath, iteration, hour, "stat.csv").toFile
    )
  }
}

object Starter extends App {
  val wrapper = new InternalRTWrapper("/Users/e.zuykin/Downloads/iran-latest.osm.pbf", "/tmp/rt")
  wrapper.generateGraph()
  wrapper.generateOd()
  wrapper.assignTraffic(0, 0)
}
