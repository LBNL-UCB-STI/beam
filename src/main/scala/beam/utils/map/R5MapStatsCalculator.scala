package beam.utils.map

import com.conveyal.osmlib.{OSM, Way}
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig
import com.conveyal.r5.streets.EdgeStore
import com.conveyal.r5.streets.EdgeStore.EdgeFlag
import com.conveyal.r5.transit.TransportNetwork

import java.{lang, util}
import scala.collection.JavaConverters._
import scala.util.Try

object R5MapStatsCalculator {

  def main(args: Array[String]): Unit = {
    // LoggerFactory.getLogger("com.conveyal").asInstanceOf[Logger].setLevel(Level.INFO)
    val pathToOsm = args(0)
    val minimumTagFrequencyToPrintTagOut = 1000

    analyzeR5Map(pathToOsm)
    analyzeOSMMap(pathToOsm, minTagFrequency = minimumTagFrequencyToPrintTagOut)
  }

  private def analyzeOSMMap(pathToOsm: String, minTagFrequency: Int): Unit = {
    val osm = new OSM(null)
    try {
      osm.readFromFile(pathToOsm)

      println(s"Number of OSM nodes: ${osm.nodes.size()}")
      println(s"Number of OSM ways: ${osm.ways.size()}")

      printAllTags(osm, minTagFrequency)
      printOSMTagInfo(osm)
    } finally {
      Try(osm.close())
    }
  }

  private def printAllTags(osm: OSM, minTagFrequency: Int): Unit = {
    println(s"All existing tags with the minimum frequency of $minTagFrequency (the format is <tag>[<frequency>]):")
    val tagToNumberOfOccurence = scala.collection.mutable.HashMap.empty[String, Int]
    osm.ways.asScala
      .flatMap {
        case (_, way) =>
          way.tags.asScala.map { tag =>
            tag.key
          }
      }
      .foreach { tagKey =>
        val prevCnt = tagToNumberOfOccurence.getOrElse(tagKey, 0)
        tagToNumberOfOccurence(tagKey) = prevCnt + 1
      }

    val frequentTagsToCnt = tagToNumberOfOccurence
      .filter { case (_, frequency) => frequency > minTagFrequency }
      .toSeq
      .sortBy(_._2)
      .reverse

    println(frequentTagsToCnt.map { case (tag, cnt) => s"$tag [$cnt]" }.mkString("  "))
  }

  private def printOSMTagInfo(osm: OSM): Unit = {
    val highwayType2Way: Map[String, Seq[(String, lang.Long, Way)]] = osm.ways.asScala
      .map { way =>
        val tags = way._2.tags
        val highwayTag = tags.asScala.collectFirst { case tag if tag.key == "highway" => tag.value }
        val highwayType = highwayTag.getOrElse("unknown")
        (highwayType, way._1, way._2)
      }
      .toSeq
      .groupBy { case (highwayType, _, _) => highwayType }

    case class TagInfo(tagName: String, hasTag: Int, doesNotHaveATag: Int, values: Seq[String])
    def toTagInfo(ways: Iterable[(String, lang.Long, Way)], tagToCollect: String): TagInfo = {
      val tagVals = ways.map {
        case (_, _, way) =>
          if (way.hasTag(tagToCollect)) Some(way.getTag(tagToCollect))
          else None
      }
      val doesNotHaveATag = tagVals.count(tv => tv.isEmpty)
      val doesHaveATag = ways.size - doesNotHaveATag

      TagInfo(tagToCollect, doesHaveATag, doesNotHaveATag, tagVals.flatten.toSeq)
    }

    val allTags = Set("lanes", "maxspeed", "capacity")

    println("The number of tags present for each way, grouped by highway type.")
    val highwayType2TagInfo = highwayType2Way.map {
      case (highwayType, ways) =>
        val tagInfos = allTags.map { tagToAnalyze =>
          toTagInfo(ways, tagToAnalyze)
        }
        highwayType -> tagInfos
    }

    highwayType2TagInfo.foreach {
      case (highwayType, tagInfos) =>
        val numberOfWays = tagInfos.head.hasTag + tagInfos.head.doesNotHaveATag
        println(s"\t$highwayType: $numberOfWays")

        def inPercentage(value: Int, fullValue: Int): Int = value * 100 / fullValue
        val tagInfosStr = tagInfos.map(ti => s"${ti.tagName} (${inPercentage(ti.hasTag, numberOfWays)})").mkString(" ")

        println(s"\t\t$tagInfosStr")
    }
    println("The configuration text based on tags values:")

    val ms2mph = 2.237

    highwayType2TagInfo.foreach {
      case (highwayType, tagInfos) =>
        val tag2Into = tagInfos.map(ti => ti.tagName -> ti).toMap
        val maybeAverageSpeedIn_mph = tag2Into.get("maxspeed") flatMap { speedTagInfo =>
          // expected values ~ "15 mph"
          val speeds = speedTagInfo.values.map(v => v.split(" ")(0).toFloat)
          maybeAverage(speeds)
        }

        val maybeAverageNumberOfLanes = tag2Into.get("lanes") flatMap { lanesTagInfo =>
          val numberOfLines = lanesTagInfo.values.map(_.toFloat)
          maybeAverage(numberOfLines)
        }

        if (maybeAverageSpeedIn_mph.nonEmpty || maybeAverageNumberOfLanes.nonEmpty) {
          println(s"\t$highwayType {")
          if (maybeAverageSpeedIn_mph.nonEmpty) {
            // speed should be in meters per second
            println(s"\t\tspeed = ${math.round(maybeAverageSpeedIn_mph.get / ms2mph)}")
          }
          if (maybeAverageNumberOfLanes.nonEmpty) {
            println(s"\t\tlanes = ${math.round(maybeAverageNumberOfLanes.get)}")
          }
          println(s"\t}")
        }
    }
  }

  def maybeAverage(sequence: Iterable[Float]): Option[Double] = {
    val (sum, len) = sequence
      .foldLeft((0.0, 0)) { case ((accum, size), v) => (accum + v, size + 1) }

    if (len > 0) {
      Some(sum / len)
    } else {
      None
    }
  }

  private def analyzeR5Map(pathToOsm: String): Unit = {
    println(s"OSM file: $pathToOsm")
    val tn = TransportNetwork.fromFiles(
      pathToOsm,
      new util.ArrayList[String](),
      TNBuilderConfig.defaultConfig,
      true,
      false
    )
    val cursor = tn.streetLayer.edgeStore.getCursor
    val it = new Iterator[EdgeStore#Edge] {
      override def hasNext: Boolean = {
        val movedToNext = cursor.advance()
        if (movedToNext) {
          cursor.retreat()
          true
        } else false
      }

      override def next(): EdgeStore#Edge = {
        cursor.advance()
        cursor
      }
    }

    val flagToCnt = it
      .foldLeft(Map[EdgeFlag, Int]()) {
        case (acc, c) =>
          c.getFlags.asScala.foldLeft(acc) {
            case (acc, flag) =>
              acc.updated(flag, acc.getOrElse(flag, 0) + 1)
          }
      }
      .toSeq
      .filter {
        case (flag, _) =>
          flag == EdgeFlag.ALLOWS_PEDESTRIAN || flag == EdgeFlag.ALLOWS_BIKE || flag == EdgeFlag.ALLOWS_CAR
      }
      .sortBy { case (flag, _) => flag.toString }

    println("Edge flag to number of edges:")
    flagToCnt.foreach {
      case (flag, cnt) =>
        println(s"$flag => $cnt")
    }
    println(s"Number of edges in R5: ${tn.streetLayer.edgeStore.nEdges()}")
    println(s"Number of vertices in R5: ${tn.streetLayer.edgeStore.vertexStore.getVertexCount}")
  }
}
