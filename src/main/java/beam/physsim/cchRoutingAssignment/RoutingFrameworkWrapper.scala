package beam.physsim.cchRoutingAssignment
import java.io.{BufferedWriter, File, FileWriter}
import java.nio.file.Paths

import beam.sim.BeamServices
import beam.utils.CloseableUtil._
import beam.utils.FileUtils
import com.typesafe.scalalogging.LazyLogging
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.mutable
import scala.sys.process.Process

/**
  * Wrapper around routing framework
  */
trait RoutingFrameworkWrapper {

  /**
    * Generates a graph in binary format in tempDir
    *
    * @return generated graph
    */
  def generateGraph(): RoutingFrameworkGraph

  /**
    * Generates random ods based on binary graph
    */
  def generateOd(): Unit

  /**
    * Generate odpairs.csv in tempDir based on incoming stream of ods
    *
    * @param iteration iteration number
    * @param hour hour
    * @param ods stream of od pairs
    */
  def writeOds(iteration: Int, hour: Int, ods: Stream[OD]): Unit

  /**
    * Assign traffic and get results of last iteration
    *
    * @param iteration iteration number
    * @param hour hour
    * @return map wayId -> travelTime
    */
  def assignTrafficAndFetchWay2TravelTime(iteration: Int, hour: Int): Map[Long, Double]
}

class RoutingFrameworkWrapperImpl(beamServices: BeamServices)
    extends DockerRoutingFrameworkWrapper(
      Paths
        .get(
          beamServices.beamConfig.beam.routing.r5.directory,
          new File(
            beamServices.beamConfig.beam.routing.r5.directory
          ).list().filter(_.endsWith("osm.pbf")).head
        )
        .toString,
      beamServices.matsimServices.getControlerIO.getOutputFilename("routing-framework"),
      beamServices.beamConfig.beam.debug.debugEnabled
    )

class DockerRoutingFrameworkWrapper(
  private val pbfPath: String,
  private val tempDirPath: String,
  private val verboseLoggingEnabled: Boolean = true
) extends RoutingFrameworkWrapper
    with LazyLogging {

  private val graphReader: RoutingFrameworkGraphReader = new RoutingFrameworkGraphReaderImpl()

  private val toolDockerImage = "beammodel/routing-framework:1.0"

  private val basePath = "/routing-framework/Build/Devel"
  private val convertGraphLauncher = s"$basePath/RawData/ConvertGraph"
  private val createODPairsLauncher = s"$basePath/RawData/GenerateODPairs"
  private val assignTrafficLauncher = s"$basePath/Launchers/AssignTraffic"

  private val pbfPathInContainer = "/pbf_storage/input_pbf.osm.pbf"
  private val pbfPathInContainerWOExtension = "/pbf_storage/input_pbf"

  private val tempDir = new File(tempDirPath)
  tempDir.mkdirs()

  private val graphPathInContainer = Paths.get("/work", "graph.gr.bin")
  private val graphPathInTempDir = Paths.get(tempDirPath, "graph.gr.bin")

  private val itHourRelatedPath = (env: String, it: Int, hour: Int, file: String) =>
    Paths.get(env, s"Iter.$it", s"Hour.$hour", file)
  private val odPairsFileInContainer = (iteration: Int, hour: Int) =>
    itHourRelatedPath("/work", iteration, hour, "odpairs.csv")
  private val odPairsFileInTempDir = (iteration: Int, hour: Int) =>
    itHourRelatedPath(tempDirPath, iteration, hour, "odpairs.csv")

  override def generateGraph(): RoutingFrameworkGraph = {
    val command = s"""
               |docker run --rm
               | -v $tempDir:/work
               | -v $pbfPath:$pbfPathInContainer
               | $toolDockerImage
               | $convertGraphLauncher
               | -s osm
               | -i $pbfPathInContainerWOExtension
               | -d binary
               | -o ${graphPathInContainer.toString.replace(".gr.bin", "")}
               | -scc -a way_id capacity coordinate free_flow_speed lat_lng length num_lanes travel_time vertex_id
      """.stripMargin.replace("\n", "")
    val convertGraphOutput = Process(command)
    logger.info("Docker command for graph generation: {}", command)

    convertGraphOutput.lineStream.foreach(logger.info(_))

    graphReader.read(graphPathInTempDir.toFile)
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

  def writeOds(iteration: Int, hour: Int, ods: Stream[OD]): Unit = {
    itHourRelatedPath(tempDirPath, iteration, hour, "").toFile.mkdirs()
    val odPairsFile = odPairsFileInTempDir(iteration, hour).toFile

    val writer = new BufferedWriter(new FileWriter(odPairsFile))
    writer.write("origin,destination")
    writer.newLine()

    ods.foreach {
      case OD(first, second) =>
        writer.write(s"$first,$second")
        writer.newLine()
    }

    writer.close()
  }

  override def assignTrafficAndFetchWay2TravelTime(iteration: Int, hour: Int): Map[Long, Double] = {
    val flowPath = itHourRelatedPath("/work", iteration, hour, "flow")
    val distPath = itHourRelatedPath("/work", iteration, hour, "dist")
    val statPath = itHourRelatedPath("/work", iteration, hour, "stat")

    val query = s"""
                     |docker run --rm
                     | --memory-swap -1
                     | -v $tempDir:/work
                     | $toolDockerImage
                     | $assignTrafficLauncher
                     | -g $graphPathInContainer
                     | -d ${odPairsFileInContainer(iteration, hour)}
                     | -p 1 
                     | -n 0 
                     | -o random
                     | -i
                     | ${if (verboseLoggingEnabled) "-v" else ""}
                     | -flow $flowPath
                     | -dist $distPath
                     | -stat $statPath
      """.stripMargin.replace("\n", "")

    logger.info("Docker command for assigning traffic: {}", query)

    val assignTrafficOutput = Process(query)

    assignTrafficOutput.lineStream.foreach(logger.info(_))

    var curIter = -1
    val wayId2TravelTime = new mutable.HashMap[Long, Double]()

    val reader = FileUtils.readerFromFile(itHourRelatedPath(tempDirPath, iteration, hour, "flow.csv").toString)
    //skip first line, which contains debug info
    reader.readLine()
    //file format : iteration,vol,sat,travel_time,way_id,bpr_result
    new CsvMapReader(reader, CsvPreference.STANDARD_PREFERENCE)
      .use { csvReader =>
        val headers = csvReader.getHeader(false)
        Iterator
          .continually(csvReader.read(headers: _*))
          .takeWhile(_ != null)
          .map { map =>
            val iteration = map.get("iteration").toInt
            if (iteration != curIter) {
              curIter = iteration
              wayId2TravelTime.clear()
            }
            map.get("way_id").toLong ->
            // travel time in routing framework is measured in tens of seconds
            // so we are dividing it by 10 to get time in seconds
            map.get("bpr_result").toDouble / 10
          }
          .foreach {
            case (wayId, travelTime) =>
              wayId2TravelTime.get(wayId) match {
                case Some(v) => wayId2TravelTime.put(wayId, v + travelTime)
                case None    => wayId2TravelTime.put(wayId, travelTime)
              }
          }
      }

    wayId2TravelTime.toMap
  }
}

object Starter extends App {
  val wrapper = new DockerRoutingFrameworkWrapper("/Users/e.zuykin/Downloads/iran-latest.osm.pbf", "/tmp/rt")
  wrapper.generateGraph()
  wrapper.generateOd()
  wrapper.assignTrafficAndFetchWay2TravelTime(0, 0)
}
