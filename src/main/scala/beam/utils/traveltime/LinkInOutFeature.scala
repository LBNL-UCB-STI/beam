package beam.utils.traveltime

import java.io.{File, PrintWriter, Writer}
import java.util

import beam.utils.ProfilingUtils
import beam.utils.traveltime.LinkInOutFeature.MappingWriter
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
  val outputPath: String,
  val delimiter: String
) extends LazyLogging
    with FeatureExtractor {
  import NetworkUtil._
  val start = System.currentTimeMillis()
  val allLinks: Array[Link] = links.values().asScala.toArray

  val vehicleOnUpstreamRoads: mutable.Map[String, Map[Int, Array[Int]]]= mutable.Map[String, Map[Int, Array[Int]]]()
  val vehicleOnDownstreamRoads: mutable.Map[String, Map[Int, Array[Int]]] = mutable.Map[String, Map[Int, Array[Int]]]()

  val linkIdToLeveledOutLinks: Map[Link, Map[Int, Array[Int]]] = allLinks.par
    .map { link =>
      link -> getLinks(link, level, Direction.Out)
          .map { case (k, v) => k -> v.map(_.getId.toString.toInt)}
    }
    .toMap
    .seq

  val linkIdToLeveledInLinks: Map[Link, Map[Int, Array[Int]]] = allLinks.par
    .map { link =>
      link -> getLinks(link, level, Direction.In)
        .map { case (k, v) => k -> v.map(_.getId.toString.toInt)}
    }
    .toMap
    .seq

  val linkIdToOutLinkHops: Map[Link, Array[Int]] = linkIdToLeveledOutLinks.par.map {
    case (src, destinations) =>
      val hops = destinations.values.flatMap { dstIds =>
        dstIds.map { dstId =>
          val dst = links.get(Id.create(dstId, classOf[Link]))
          numOfHops(src, dst, Direction.Out)
        }
      }.toArray
      src -> hops
  }.seq

  val linkIdToInLinkHops: Map[Link, Array[Int]] = linkIdToLeveledInLinks.par.map {
    case (src, destinations) =>
      val hops = destinations.values.flatMap { dstIds =>
        dstIds.map { dstId =>
          val dst = links.get(Id.create(dstId, classOf[Link]))
          numOfHops(src, dst, Direction.In)
        }
      }.toArray
      src -> hops
  }.seq


  val end = System.currentTimeMillis()
  logger.info(s"Prepared in ${end - start} ms")

  val shouldWriteMapping: Boolean = true
  if (shouldWriteMapping) {
    ProfilingUtils.timed("writeMappings", x => logger.info(x)) {
      val mappingWriter = new MappingWriter(level,
        allLinks,
        linkIdToLeveledOutLinks,
        linkIdToOutLinkHops,
        linkIdToLeveledInLinks,
        linkIdToInLinkHops,
        delimiter
      )
      val fileName = new File(outputPath).getParentFile + "/LinkInOut_mapping.csv"
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
      val total = counts.sum
      val avg = total.toDouble / counts.length
      val sorted = counts.sorted
      val std = new StandardDeviation().evaluate(counts.map(_.toDouble))
      writeColumnValue(total.toString)
      writeColumnValue(counts.min.toString)
      writeColumnValue(counts.max.toString)
      writeColumnValue(sorted(counts.length / 2).toString)
      writeColumnValue(avg.toString)
      writeColumnValue(std.toString)
    }

    vehicleOnDownstreamRoads(vehicleId).foreach { case (lvl, counts) =>
      val total = counts.sum
      val avg = total.toDouble / counts.length
      val sorted = counts.sorted
      val std = new StandardDeviation().evaluate(counts.map(_.toDouble))
      writeColumnValue(total.toString)
      writeColumnValue(counts.min.toString)
      writeColumnValue(counts.max.toString)
      writeColumnValue(sorted(counts.length / 2).toString)
      writeColumnValue(avg.toString)
      writeColumnValue(std.toString)
    }
  }

  private def writeColumnValue(value: String)(implicit wrt: Writer): Unit = {
    wrt.append(value)
    wrt.append(delimiter)
  }
}

object LinkInOutFeature {

  class MappingWriter(val level: Int,
    val allLinks: Array[Link],
    val linkIdToOutLinks: Map[Link, Map[Int, Array[Int]]],
    val linkIdToOutLinkHops: Map[Link, Array[Int]],
    val linkIdToInLinks: Map[Link, Map[Int, Array[Int]]],
    val linkIdToInLinkHops: Map[Link, Array[Int]],
    val delimiter: String
  ) {

    def write(path: String): Unit = {
      implicit val writer: Writer = new PrintWriter(path)
      try {
        writeHeader()
        allLinks.foreach { link =>
          writeColumnValue(link.getId.toString)
          writeColumnValue(link.getLength.toString)
          writeColumnValue(link.getCapacity.toString)
          writeColumnValue(link.getNumberOfLanes.toString)
          writeColumnValue(link.getFromNode.getInLinks.size.toString)
          writeColumnValue(link.getFromNode.getOutLinks.size.toString)
          writeColumnValue(link.getToNode.getInLinks.size.toString)
          writeColumnValue(link.getToNode.getOutLinks.size.toString)

          linkIdToOutLinks(link).foreach { case (_, outLinks) =>
            val linksStr = "\"" + outLinks.mkString(" ") + "\""
            writeColumnValue(linksStr)
          }

          linkIdToInLinks(link).foreach { case (_, inLinks) =>
            val linksStr = "\"" + inLinks.mkString(" ") + "\""
            writeColumnValue(linksStr)
          }
          // Do not use `writeColumnValue`, it adds delimiter, but this is the last column
          writer.append("d")
          writer.append(System.lineSeparator())
        }
      } finally {
        writer.flush()
        writer.close()
      }
    }

    def writeColumnValue(value: String)(implicit wrt: Writer): Unit = {
      wrt.append(value)
      wrt.append(delimiter)
    }

    def writeHeader()(implicit wrt: Writer): Unit = {
      writeColumnValue("linkId")
      writeColumnValue("length")
      writeColumnValue("capacity")
      writeColumnValue("lanes")
      writeColumnValue("FromNode_InLinksSize")
      writeColumnValue("FromNode_OutLinksSize")
      writeColumnValue("ToNode_InLinksSize")
      writeColumnValue("ToNode_OutLinksSize")
      (1 to level).foreach { lvl =>
        writeColumnValue(s"L${lvl}_OutLinks")
      }
      (1 to level).foreach { lvl =>
        writeColumnValue(s"L${lvl}_InLinks")
      }
      writeColumnValue("dummy_column")
      wrt.append(System.lineSeparator())
      wrt.flush()
    }
  }
}
