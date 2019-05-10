package beam.utils.traveltime

import java.io.{File, PrintWriter, Writer}
import java.util

import beam.utils.ProfilingUtils
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Link

import scala.collection.JavaConverters._
import scala.collection.mutable

class LinkInOutFeature(
  val links: util.Map[Id[Link], _ <: Link],
  val level: Int,
  val outputPath: String
) extends LazyLogging
    with FeatureExtractor {
  import LinkInOutFeature._
  import NetworkUtil._
  val start = System.currentTimeMillis()
  val allLinks: Array[Link] = links.values().asScala.toArray

  val vehicleOnUpstreamRoads: mutable.Map[String, Map[Int, Array[Int]]]= mutable.Map[String, Map[Int, Array[Int]]]()
  val vehicleOnDownstreamRoads: mutable.Map[String, Map[Int, Array[Int]]] = mutable.Map[String, Map[Int, Array[Int]]]()

  val linkIdToLeveledOutLinks: Map[Link, Map[Int, Array[Int]]] = allLinks.par
    .map { link =>
      link -> getLinks(link, level, Direction.Out)
          .map { case (k, v) => k -> v.map(_.getId.toString.toInt)}.toMap
    }
    .toMap
    .seq

  val linkIdToLeveledInLinks: Map[Link, Map[Int, Array[Int]]] = allLinks.par
    .map { link =>
      link -> getLinks(link, level, Direction.In)
        .map { case (k, v) => k -> v.map(_.getId.toString.toInt)}.toMap
    }
    .toMap
    .seq

  val linkIdToOutLinkHops: Map[Link, Array[Int]] = Map.empty  /*linkIdToLeveledOutLinks.par.map {
    case (src, destinations) =>
      val hops = destinations.values.flatMap { dstIds =>
        dstIds.map { dstId =>
          val dst = links.get(Id.create(dstId, classOf[Link]))
          numOfHops(src, dst, Direction.Out)
        }
      }.toArray
      src -> hops
  }.seq */

  val linkIdToInLinkHops: Map[Link, Array[Int]] = Map.empty /* linkIdToLeveledInLinks.par.map {
    case (src, destinations) =>
      val hops = destinations.values.flatMap { dstIds =>
        dstIds.map { dstId =>
          val dst = links.get(Id.create(dstId, classOf[Link]))
          numOfHops(src, dst, Direction.In)
        }
      }.toArray
      src -> hops
  }.seq */

  val end = System.currentTimeMillis()
  logger.info(s"Prepared in ${end - start} ms")

  val shouldWriteMapping: Boolean = true
  if (shouldWriteMapping) {
    ProfilingUtils.timed("writeMappings", x => logger.info(x)) {
      val mappingWriter = new MappingWriter(level,
        links,
        allLinks,
        linkIdToLeveledOutLinks,
        linkIdToOutLinkHops,
        linkIdToLeveledInLinks,
        linkIdToInLinkHops,
        Delimiter
      )
      val fileName = new File(outputPath).getParentFile + "/Metadata.csv"
      mappingWriter.write(fileName)
    }
  }

  def writeHeader(wrt: Writer): Unit = {
    implicit val writer: Writer = wrt
    (1 to level).foreach { lvl =>
      writeColumnValue(s"L${lvl}_TotalVeh_OutLinks")
      writeColumnValue(s"L${lvl}_MinVeh_OutLinks")
      writeColumnValue(s"L${lvl}_MaxVeh_OutLinks")
      writeColumnValue(s"L${lvl}_MedianVeh_OutLinks")
      writeColumnValue(s"L${lvl}_AvgVeh_OutLinks")
      writeColumnValue(s"L${lvl}_StdVeh_OutLinks")
    }

    (1 to level).foreach { lvl =>
      writeColumnValue(s"L${lvl}_TotalVeh_InLinks")
      writeColumnValue(s"L${lvl}_MinVeh_InLinks")
      writeColumnValue(s"L${lvl}_MaxVeh_InLinks")
      writeColumnValue(s"L${lvl}_MedianVeh_InLinks")
      writeColumnValue(s"L${lvl}_AvgVeh_InLinks")
      writeColumnValue(s"L${lvl}_StdVeh_InLinks")
    }
  }

  def enteredLink(
    event: Event,
    link: Link,
    vehicleId: String,
    linkVehicleCount: scala.collection.Map[Int, Int]
  ): Unit = {
    val outLinksLeveledVehCounts = linkIdToLeveledOutLinks(link).map { case (lvl, outLinks) =>
      val counts = outLinks.map(linkVehicleCount.getOrElse(_, 0))
      lvl -> counts
    }
    vehicleOnUpstreamRoads.put(vehicleId, outLinksLeveledVehCounts)

    val inLinksLeveledVehCounts = linkIdToLeveledInLinks(link).map { case (lvl, inLinks) =>
      val counts = inLinks.map(linkVehicleCount.getOrElse(_, 0))
      lvl -> counts
    }
    vehicleOnDownstreamRoads.put(vehicleId, inLinksLeveledVehCounts)
  }

  def leavedLink(
    wrt: Writer,
    event: Event,
    link: Link,
    vehicleId: String,
    linkVehicleCount: scala.collection.Map[Int, Int]
  ): Unit = {
    implicit val writer: Writer = wrt
    vehicleOnUpstreamRoads(vehicleId).foreach { case (lvl, counts) =>
      writeStats(counts)
    }

    vehicleOnDownstreamRoads(vehicleId).foreach { case (lvl, counts) =>
      writeStats(counts)
    }
  }
}

object LinkInOutFeature {
  val Delimiter: String = ","
  def writeColumnValue(value: String)(implicit wrt: Writer): Unit = {
    wrt.append(value)
    wrt.append(Delimiter)
  }

  def writeStats[T](counts: Array[T])(implicit wrt: Writer, num: Numeric[T]): Unit = {
    val stats = Stats(counts)
    writeColumnValue(stats.total.toString)
    writeColumnValue(stats.min.toString)
    writeColumnValue(stats.max.toString)
    writeColumnValue(stats.median.toString)
    writeColumnValue(stats.avg.toString)
    writeColumnValue(stats.std.toString)
  }

  case class Stats[T](counts: Array[T])(implicit num: Numeric[T]) {
    val total: T = counts.sum
    val min: T = counts.min
    val max: T = counts.max
    val sorted: Array[T] = counts.sorted
    val median: T = sorted(counts.length / 2)
    val avg: Double = num.toDouble(total) / counts.length
    val std: Double = new StandardDeviation().evaluate(counts.map(num.toDouble))
  }

  class MappingWriter(val level: Int,
    val linkIdToLink: util.Map[Id[Link], _ <: Link],
    val allLinks: Array[Link],
    val linkIdToOutLinks: Map[Link, Map[Int, Array[Int]]],
    val linkIdToOutLinkHops: Map[Link, Array[Int]],
    val linkIdToInLinks: Map[Link, Map[Int, Array[Int]]],
    val linkIdToInLinkHops: Map[Link, Array[Int]],
    val delimiter: String
  ) {

    val zeroArr: Array[Int] = Array(0)

    def writeAllStats(levelToLinks: Map[Int, Array[Int]])(implicit wrt: Writer): Unit = {
      (1 to level).foreach { lvl =>
        levelToLinks.get(lvl).map { lids => lids.map(lid => linkIdToLink.get(Id.create(lid, classOf[Link]))) } match {
          case Some(links) =>
            val lens = links.map(_.getLength)
            writeStats(lens)

            val capacities = links.map(_.getCapacity)
            writeStats(capacities)

            val lanes = links.map(_.getNumberOfLanes)
            writeStats(lanes)

            val freSpeeds = links.map(_.getFreespeed)
            writeStats(freSpeeds)
          case None =>
            writeStats(zeroArr)
            writeStats(zeroArr)
            writeStats(zeroArr)
            writeStats(zeroArr)
        }
      }
    }

    def write(path: String): Unit = {
      implicit val writer: Writer = new PrintWriter(path)
      try {
        writeHeader()
        allLinks.foreach { link =>
          writeColumnValue(link.getId.toString)
          writeColumnValue(link.getLength.toString)
          writeColumnValue(link.getCapacity.toString)
          writeColumnValue(link.getNumberOfLanes.toString)
          writeColumnValue(link.getFreespeed.toString)
          writeColumnValue(link.getFromNode.getInLinks.size.toString)
          writeColumnValue(link.getFromNode.getOutLinks.size.toString)
          writeColumnValue(link.getToNode.getInLinks.size.toString)
          writeColumnValue(link.getToNode.getOutLinks.size.toString)

          writeAllStats(linkIdToOutLinks(link))
          writeAllStats(linkIdToInLinks(link))

          writeLinksPerLevel(linkIdToOutLinks(link))
          writeLinksPerLevel(linkIdToInLinks(link))

          // Do not use `writeColumnValue`, it adds delimiter, but this is the last column
          writer.append("d")
          writer.append(System.lineSeparator())
        }
      } finally {
        writer.flush()
        writer.close()
      }
    }

    private def writeLinksPerLevel(levelToLinks: Map[Int, Array[Int]])(implicit wrt: Writer): Unit = {
      (1 to level).foreach { lvl =>
        levelToLinks.get(lvl) match {
          case Some(links) =>
            val linksStr = "\"" + links.mkString(" ") + "\""
            writeColumnValue(linksStr)
          case None =>
            writeColumnValue("")
        }
      }
    }

    def writeColumnValue(value: String)(implicit wrt: Writer): Unit = {
      wrt.append(value)
      wrt.append(delimiter)
    }

    def writeStatistics(prefix: String, name: String)(implicit wrt: Writer): Unit = {
      (1 to level).foreach { lvl =>
        writeColumnValue(s"L${lvl}_${prefix}_Total${name}")
        writeColumnValue(s"L${lvl}_${prefix}_Min${name}")
        writeColumnValue(s"L${lvl}_${prefix}_Max${name}")
        writeColumnValue(s"L${lvl}_${prefix}_Median${name}")
        writeColumnValue(s"L${lvl}_${prefix}_Avg${name}")
        writeColumnValue(s"L${lvl}_${prefix}_Std${name}")
      }
    }

    def writeHeader()(implicit wrt: Writer): Unit = {
      writeColumnValue("linkId")
      writeColumnValue("length")
      writeColumnValue("capacity")
      writeColumnValue("lanes")
      writeColumnValue("freeSpeed")
      writeColumnValue("FromNode_InLinksSize")
      writeColumnValue("FromNode_OutLinksSize")
      writeColumnValue("ToNode_InLinksSize")
      writeColumnValue("ToNode_OutLinksSize")

      writeStatistics("OutLinks", "Length")
      writeStatistics("OutLinks", "Capacity")
      writeStatistics("OutLinks", "Lanes")
      writeStatistics("OutLinks", "FreeSpeed")

      writeStatistics("InLinks", "Length")
      writeStatistics("InLinks", "Capacity")
      writeStatistics("InLinks", "Lanes")
      writeStatistics("InLinks", "FreeSpeed")

      (1 to level).foreach { lvl =>
        writeColumnValue(s"L${lvl}_OutLinks")
      }
      (1 to level).foreach { lvl =>
        writeColumnValue(s"L${lvl}_InLinks")
      }
      wrt.append("dummy_column")
      wrt.append(System.lineSeparator())
      wrt.flush()
    }
  }
}
