package beam.utils.traveltime

import java.io.{File, PrintWriter, Writer}
import java.util

import beam.utils.ProfilingUtils
import beam.utils.traveltime.LinkInOutFeature.MappingWriter
import com.typesafe.scalalogging.LazyLogging
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
  val start: Long = System.currentTimeMillis()
  val allLinks: Array[Link] = links.values().asScala.toArray

  val vehicleOnUpstreamRoads: mutable.Map[String, Array[Int]] = mutable.Map[String, Array[Int]]()
  val vehicleOnDownstreamRoads: mutable.Map[String, Array[Int]] = mutable.Map[String, Array[Int]]()

  val linkIdToOutLinks: Map[Link, Array[Int]] = allLinks.par
    .map { link =>
      link -> getLinks(link, level, Direction.Out).map(_.getId.toString.toInt)
    }
    .toMap
    .seq

  val linkIdToInLinks: Map[Link, Array[Int]] = allLinks.par
    .map { link =>
      link -> getLinks(link, level, Direction.In).map(_.getId.toString.toInt)
    }
    .toMap
    .seq

  val linkIdToOutLinkHops: Map[Link, Array[Int]] = linkIdToOutLinks.par.map { case (src, destinations) =>
    val hops = destinations.map { dstId =>
      val dst = links.get(Id.create(dstId, classOf[Link]))
      numOfHops(src, dst, Direction.Out)
    }
    src -> hops
  }.seq

  val linkIdToInLinkHops: Map[Link, Array[Int]] = linkIdToInLinks.par.map { case (src, destinations) =>
    val hops = destinations.map { dstId =>
      val dst = links.get(Id.create(dstId, classOf[Link]))
      numOfHops(src, dst, Direction.In)
    }
    src -> hops
  }.seq
  val maxOutColumns: Int = linkIdToOutLinks.values.maxBy(_.length).length
  val maxInColumns: Int = linkIdToInLinks.values.maxBy(_.length).length

  val end: Long = System.currentTimeMillis()
  logger.info(s"Prepared in ${end - start} ms")

  val shouldWriteMapping: Boolean = true
  if (shouldWriteMapping) {
    ProfilingUtils.timed("writeMappings", x => logger.info(x)) {
      val mappingWriter = new MappingWriter(
        allLinks,
        maxOutColumns,
        linkIdToOutLinks,
        linkIdToOutLinkHops,
        maxInColumns,
        linkIdToInLinks,
        linkIdToInLinkHops,
        delimiter
      )
      val fileName = new File(outputPath).getParentFile + "/LinkInOut_mapping.csv"
      mappingWriter.write(fileName)
    }
  }
  logger.info(s"Build in and out links. maxOutColumns: $maxOutColumns, maxInColumns: $maxInColumns")

  def writeHeader(wrt: Writer): Unit = {
    implicit val writer: Writer = wrt
    writeColumnValue("out_sum")
    writeColumnValue("in_sum")

    (1 to maxOutColumns).foreach { i =>
      writeColumnValue(s"outLink${i}_vehOnRoad")
    }

    (1 to maxInColumns).foreach { i =>
      writeColumnValue(s"inLink${i}_vehOnRoad")
    }
  }

  def enteredLink(
    event: Event,
    link: Link,
    vehicleId: String,
    linkVehicleCount: scala.collection.Map[Int, Int]
  ): Unit = {
    linkIdToOutLinks.get(link).foreach { outLinks =>
      val counts = outLinks.map { lid =>
        linkVehicleCount.getOrElse(lid, 0)
      }
      vehicleOnUpstreamRoads.put(vehicleId, counts)
    }

    linkIdToInLinks.get(link).foreach { inLinks =>
      val counts = inLinks.map { lid =>
        linkVehicleCount.getOrElse(lid, 0)
      }
      vehicleOnDownstreamRoads.put(vehicleId, counts)
    }
  }

  def leavedLink(
    wrt: Writer,
    event: Event,
    link: Link,
    vehicleId: String,
    linkVehicleCount: scala.collection.Map[Int, Int]
  ): Unit = {
    implicit val writer: Writer = wrt
    val outCounts = vehicleOnUpstreamRoads(vehicleId)
    val inCounts = vehicleOnDownstreamRoads(vehicleId)
    writeColumnValue(outCounts.sum.toString)
    writeColumnValue(inCounts.sum.toString)

    (1 to maxOutColumns).foreach { i =>
      val idx = i - 1
      outCounts.lift(idx) match {
        case Some(cnt) =>
          writeColumnValue(s"${cnt.toString}")
        case None =>
          writeColumnValue("0")
      }
    }

    (1 to maxInColumns).foreach { i =>
      val idx = i - 1
      inCounts.lift(idx) match {
        case Some(cnt) =>
          writeColumnValue(s"${cnt.toString}")
        case None =>
          writeColumnValue("0")
      }
    }
  }

  private def writeColumnValue(value: String)(implicit wrt: Writer): Unit = {
    wrt.append(value)
    wrt.append(delimiter)
  }
}

object LinkInOutFeature {

  class MappingWriter(
    val allLinks: Array[Link],
    val maxOutColumns: Int,
    val linkIdToOutLinks: Map[Link, Array[Int]],
    val linkIdToOutLinkHops: Map[Link, Array[Int]],
    val maxInColumns: Int,
    val linkIdToInLinks: Map[Link, Array[Int]],
    val linkIdToInLinkHops: Map[Link, Array[Int]],
    val delimiter: String
  ) {

    def write(path: String): Unit = {
      implicit val writer: Writer = new PrintWriter(path)
      try {
        writeHeader()
        allLinks.foreach { link =>
          writeColumnValue(link.getId.toString)
          writeColumnValue(link.getCapacity.toString)
          writeColumnValue(link.getNumberOfLanes.toString)

          writeLinksWithHop(maxOutColumns, linkIdToOutLinks(link), linkIdToOutLinkHops(link))
          writeLinksWithHop(maxInColumns, linkIdToInLinks(link), linkIdToInLinkHops(link))

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
      writeColumnValue("capacity")
      writeColumnValue("lanes")

      (1 to maxOutColumns).foreach { i =>
        writeColumnValue(s"outLink${i}_linkId")
        writeColumnValue(s"outLink${i}_numOfHops")
      }

      (1 to maxInColumns).foreach { i =>
        writeColumnValue(s"inLink${i}_linkId")
        writeColumnValue(s"inLink${i}_numOfHops")
      }
      writeColumnValue("dummy_column")
      wrt.append(System.lineSeparator())
      wrt.flush()
    }

    def writeLinksWithHop(maxColumns: Int, linkIds: Array[Int], hopsPerLink: Array[Int])(implicit wrt: Writer): Unit = {
      var i: Int = 0
      assert(linkIds.length == hopsPerLink.length)
      while (i < maxColumns) {
        if (i < linkIds.length) {
          val linkId = linkIds(i)
          val nHops = hopsPerLink(i)
          writeColumnValue(linkId.toString)
          writeColumnValue(nHops.toString)
        } else {
          writeColumnValue("0")
          writeColumnValue("0")
        }
        i += 1
      }
    }
  }
}
