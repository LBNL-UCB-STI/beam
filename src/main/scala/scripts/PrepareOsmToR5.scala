package scripts

import java.nio.file.{Files, Paths, StandardCopyOption}

import beam.sim.common.GeoUtils
import com.conveyal.osmlib.OSM
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Coord

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.sys.process.Process

object PrepareOsmToR5 extends App {
  if (args.length == 0) {
    println("""
Usage: [options] -i inputFilePath -o outputFilePath
  -f
    filter input file by osmium-tool

  -s <value>
    simplify ways. Node will be removed possibly if distance to next one is lesser than <value> meters

  --stats
    print OSM stats for each iteration
""")

    System.exit(1)
  }

  val argList = args.toList

  def nextOption(map: Map[String, String], list: List[String]): Map[String, String] = {
    list match {
      case Nil                   => map
      case "-f" :: tail          => nextOption(map ++ Map("filter" -> ""), tail)
      case "-s" :: value :: tail => nextOption(map ++ Map("simplify" -> value), tail)
      case "-i" :: value :: tail => nextOption(map ++ Map("input" -> value), tail)
      case "-o" :: value :: tail => nextOption(map ++ Map("output" -> value), tail)
      case "--stats" :: tail     => nextOption(map ++ Map("stats" -> "true"), tail)
      case option :: tail => {
        println(s"Unknown option $option")
        System.exit(1)
        Map()
      }
    }
  }

  val options = nextOption(Map(), argList)
  val showStats = options.getOrElse("stats", "false").toBoolean
  var currentOperationResultFile = ""

  val preparer = new PrepareOsmToR5()

  if (showStats) {
    preparer.printOsmStats(preparer.readOsm(options("input")))
  }

  if (options.contains("filter")) {
    currentOperationResultFile = preparer.filter(options("input"), showStats)
    println("Filtration by osmium-tool was successfully finished")
  }

  if (options.contains("simplify")) {
    val inputFile = if (currentOperationResultFile.isEmpty) options("input") else currentOperationResultFile
    currentOperationResultFile = preparer.simplify(inputFile, options("simplify").toDouble, showStats)
    println("Simplification was successfully finished")
  }

  if (currentOperationResultFile.isEmpty) {
    println("No operation has been selected")
  } else {
    Files.move(Paths.get(currentOperationResultFile), Paths.get(options("output")), StandardCopyOption.REPLACE_EXISTING)
  }
}

class PrepareOsmToR5 extends LazyLogging {
  private val toolDockerImage = "stefda/osmium-tool"
  private val tagKeys =
    "traffic_signal:direction, traffic_signals:direction, destination, vehicle, motor_vehicle, bridge:support, motorcar, bicycle, traffic_calming, maxspeed, lanes:both_ways, side, lanes, bicycle_parking, point_mile, direction, traffic_signals:light, lanes:forward, turn:lanes:forward, turn:lanes:backward, traffic_signals:sound, service:bicycle:tools, traffic_sign, destination:lanes, public_transport, turning_circle, railway, bridge, motorcycle, service:bicycle:pump, cycleway, stop, traffic_sign:direction, kerb, barrier:rfid, highway, tunnel, railway:position:exact, traffic_signals, foot, lanes:backward, bus"
      .split(",")
      .map(_.trim)

  private val geo: GeoUtils = new GeoUtils {
    override def localCRS: String = "epsg:2808"
  }

  def filter(inputFilePath: String, printStats: Boolean): String = {
    val inputPath = Paths.get(inputFilePath)
    val outputPath = Paths.get(Files.createTempFile("osm-filtered-", "osm.pbf").toString)
    val command =
      s"""
         |docker run --rm
         | -v ${inputPath.getParent}:/input
         | -v ${outputPath.getParent}:/output
         | $toolDockerImage
         | osmium tags-filter
         | /input/${inputPath.getFileName}
         | w/highway
         | w/public_transport=platform
         | w/railway=platform
         | w/park_ride=yes
         | r/type=restriction
         | -o /output/${outputPath.getFileName}
         | -f pbf,add_metadata=false
         | --overwrite
      """.stripMargin.replace("\n", "")
    logger.info("Docker command for filter osm: {}", command)
    val filterOsmOutput = Process(command)

    filterOsmOutput.lineStream.foreach(v => logger.info(v))
    if (printStats) printOsmStats(readOsm(outputPath.toString))
    outputPath.toString
  }

  def simplify(inputFilePath: String, distance: Double, printStats: Boolean): String = {
    val osm = readOsm(inputFilePath)

    def simplifyInner() = {
      def simplifyWays() = {
        val nodeToWayMap = new mutable.HashMap[Long, mutable.ListBuffer[Long]]()
        osm.ways.asScala.foreach {
          case (id, way) =>
            for (i <- way.nodes.indices) {
              val node = way.nodes(i)
              nodeToWayMap.get(node) match {
                case Some(value) => value += id
                case None =>
                  val list = new mutable.ListBuffer[Long]()
                  list += id
                  nodeToWayMap.put(node, list)
              }
            }
        }

        val simplifiedWays = osm.ways.asScala.map {
          case (id, way) =>
            val newNodes = new ListBuffer[Long]()

            newNodes += way.nodes(0)
            for (i <- 1 until way.nodes.length - 1) {
              val nodeTags = osm.nodes.get(way.nodes(i)).tags
              if (nodeToWayMap(way.nodes(i)).size > 1 ||
                  (nodeTags != null && nodeTags.asScala.exists(tags => tagKeys.contains(tags.key)))) {
                newNodes += way.nodes(i)
              } else {
                val distanceToNextNode = getDistance(osm, newNodes.last, way.nodes(i))
                if (distanceToNextNode > distance) {
                  newNodes += way.nodes(i)
                } else if (i + 1 > way.nodes.length) {
                  newNodes += way.nodes(i)
                } else {
                  // check angle to determine turn
                  // not work because nodes i and i+1 too close

//                val a = getDistance(osm, way.nodes(i), way.nodes(i + 1))
//                val b = distanceToNextNode
//                val c = getDistance(osm, newNodes.last, way.nodes(i + 1))
//                val angle = 180.0 - Math.acos((b * b + c * c - a * a) / (2 * b * c)) - Math.acos((a * a + c * c - b * b) / (2 * a * c))
//
//                if (angle < 120.0) {
//                  newNodes += way.nodes(i)
//                }
                }
              }
            }
            if (way.nodes.length > 1) {
              newNodes += way.nodes(way.nodes.length - 1)
            }

            id -> newNodes
        }

        simplifiedWays
      }

      val simplifiedWays = simplifyWays()

      val copy = new OSM(null)
      copy.writeBegin()

      val secondsSinceEpoch: Long = System.currentTimeMillis / 1000
      copy.setReplicationTimestamp(secondsSinceEpoch)

      osm.ways.asScala.foreach {
        case (id, way) =>
          if (simplifiedWays.contains(id)) {
            way.nodes = simplifiedWays(id).toArray
            copy.writeWay(id, way)
          }
      }

      simplifiedWays.values.flatten.foreach { nodeId =>
        copy.writeNode(nodeId, osm.nodes.get(nodeId))
      }

      osm.relations.asScala.foreach {
        case (id, rel) =>
          copy.writeRelation(id, rel)
      }

      copy.writeEnd()
      copy
    }

    val simplifiedOsm = simplifyInner()
    val outputFilePath = Files.createTempFile("osm-simplified-", "osm.pbf").toString
    simplifiedOsm.writeToFile(outputFilePath)
    if (printStats) printOsmStats(simplifiedOsm)
    outputFilePath
  }

  private def readOsm(filePath: String): OSM = {
    val osm = new OSM(null)
    osm.readFromFile(filePath)
    osm
  }

  private def getNodeCoord(osm: OSM, nodeId: Long): Coord = {
    val node = osm.nodes.get(nodeId)
    new Coord(node.getLon, node.getLat)
  }

  private def getDistance(osm: OSM, nodeId1: Long, nodeId2: Long): Double = {
    val coord1 = getNodeCoord(osm, nodeId1)
    val coord2 = getNodeCoord(osm, nodeId2)
    geo.distLatLon2Meters(coord1, coord2)
  }

  private def printOsmStats(osm: OSM): Unit = {
    logger.info(s"count of nodes is: ${osm.nodes.size()}")
    logger.info(s"count of ways is: ${osm.ways.size()}")
    logger.info(s"count of relations is: ${osm.relations.size()}")
  }
}
