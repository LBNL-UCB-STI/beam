package beam.utils.traveltime

import java.io.Writer
import java.util

import beam.utils.ProfilingUtils
import com.typesafe.scalalogging.LazyLogging
import org.apache.avro.Schema
import org.apache.avro.Schema.Type
import org.apache.avro.generic.GenericData
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Link

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class LinkInOutFeature(
  val links: util.Map[Id[Link], _ <: Link],
  val level: Int,
  val pathToMetadataFolder: String,
  val enteredLinkMultiThreaded: Boolean = false,
  val leavedLinkMultiThreaded: Boolean = false,
  val shouldWriteMapping: Boolean = true
) extends LazyLogging
    with FeatureExtractor
    with CsvHelper {

  import NetworkUtil._

  val start = System.currentTimeMillis()
  val allLinks: Array[Link] = links.values().asScala.toArray

  val vehicleOnUpstreamRoads: mutable.Map[String, Map[Int, Array[Int]]] = mutable.Map[String, Map[Int, Array[Int]]]()
  val vehicleOnDownstreamRoads: mutable.Map[String, Map[Int, Array[Int]]] = mutable.Map[String, Map[Int, Array[Int]]]()

  val linkIdToLeveledOutLinks: Map[Link, Map[Int, Array[Int]]] = allLinks.par
    .map { link =>
      link -> getLinks(link, level, Direction.Out).map { case (k, v) => k -> v.map(_.getId.toString.toInt) }.toMap
    }
    .toMap
    .seq

  val linkIdToLeveledInLinks: Map[Link, Map[Int, Array[Int]]] = allLinks.par
    .map { link =>
      link -> getLinks(link, level, Direction.In).map { case (k, v) => k -> v.map(_.getId.toString.toInt) }.toMap
    }
    .toMap
    .seq

  val linkIdToOutLinkHops: Map[Link, Array[Int]] = Map.empty
  /*linkIdToLeveledOutLinks.par.map {
    case (src, destinations) =>
      val hops = destinations.values.flatMap { dstIds =>
        dstIds.map { dstId =>
          val dst = links.get(Id.create(dstId, classOf[Link]))
          numOfHops(src, dst, Direction.Out)
        }
      }.toArray
      src -> hops
  }.seq */

  val linkIdToInLinkHops: Map[Link, Array[Int]] = Map.empty
  /* linkIdToLeveledInLinks.par.map {
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
  logger.info(s"""LinkInOutFeature initialized in ${end - start} ms. Params:
       |Total number of links: ${allLinks.length}
       |Level: $level
       |pathToMetadataFolder: ${pathToMetadataFolder}
       |enteredLinkMultiThreaded: $enteredLinkMultiThreaded
       |leavedLinkMultiThreaded: $leavedLinkMultiThreaded
       |shouldWriteMapping: $shouldWriteMapping""".stripMargin)

  if (shouldWriteMapping) {
    ProfilingUtils.timed("writeMappings", x => logger.info(x)) {
      val mappingWriter = new LinkMetadata(
        level,
        links,
        allLinks,
        linkIdToLeveledOutLinks,
        linkIdToOutLinkHops,
        linkIdToLeveledInLinks,
        linkIdToInLinkHops
      )
      val metadataWriteType = WriterType.Parquet
      val fileNameNoExtension = pathToMetadataFolder + "/Metadata"
      mappingWriter.write(fileNameNoExtension, metadataWriteType)
    }
  }

  def fields: Seq[Schema.Field] = {
    val columns = (1 to level).flatMap { lvl =>
      Array(
        s"L${lvl}_TotalVeh_OutLinks",
        s"L${lvl}_TotalVeh_InLinks",
      )
    }
    columns.map { x =>
      val schema = Schema.create(Type.DOUBLE)
      new Schema.Field(x, schema, x, null.asInstanceOf[Any])
    }
  }

  def csvWriteHeader(wrt: Writer): Unit = {
    implicit val writer: Writer = wrt

    fields.foreach { f =>
      writeColumnValue(f.name)
    }
  }

  def enteredLink(
    event: Event,
    link: Link,
    vehicleId: String,
    linkVehicleCount: scala.collection.Map[Int, Int]
  ): Unit = {
    val levelToOutLinks = linkIdToLeveledOutLinks(link)
    val levelToInLinks = linkIdToLeveledInLinks(link)

    if (enteredLinkMultiThreaded) {
      val outLinksLeveledVehCountsF = Future {
        levelToOutLinks.map {
          case (lvl, outLinks) =>
            val counts = outLinks.map(linkVehicleCount.getOrElse(_, 0))
            lvl -> counts
        }
      }
      val inLinksLeveledVehCountsF = Future {
        levelToInLinks.map {
          case (lvl, inLinks) =>
            val counts = inLinks.map(linkVehicleCount.getOrElse(_, 0))
            lvl -> counts
        }
      }
      val result =
        Await.result(Future.sequence(Seq(outLinksLeveledVehCountsF, inLinksLeveledVehCountsF)), 100.seconds).toArray

      vehicleOnUpstreamRoads.put(vehicleId, result(0))
      vehicleOnDownstreamRoads.put(vehicleId, result(1))
    } else {
      val outLinksLeveledVehCounts = levelToOutLinks.map {
        case (lvl, outLinks) =>
          val counts = outLinks.map(linkVehicleCount.getOrElse(_, 0))
          lvl -> counts
      }
      val inLinksLeveledVehCounts = levelToInLinks.map {
        case (lvl, inLinks) =>
          val counts = inLinks.map(linkVehicleCount.getOrElse(_, 0))
          lvl -> counts
      }
      vehicleOnUpstreamRoads.put(vehicleId, outLinksLeveledVehCounts)
      vehicleOnDownstreamRoads.put(vehicleId, inLinksLeveledVehCounts)
    }
  }

  def leavedLink(
    event: Event,
    link: Link,
    vehicleId: String,
    linkVehicleCount: scala.collection.Map[Int, Int],
    record: GenericData.Record
  ): Unit = {
    if (leavedLinkMultiThreaded)
      leavedLinkMultiThreaded(record, vehicleId)
    else
      leavedLinkSingleThreaded(record, vehicleId)
  }

  def leavedLinkMultiThreaded(record: GenericData.Record, vehicleId: String): Unit = {
    val outLinks = vehicleOnUpstreamRoads(vehicleId)
    val inLinks = vehicleOnDownstreamRoads(vehicleId)
    val outF = Future {
      outLinks.map {
        case (lvl, counts) =>
          lvl -> counts.sum
      }
    }
    val inF = Future {
      inLinks.map {
        case (lvl, counts) =>
          lvl -> counts.sum
      }
    }
    val result = Await.result(Future.sequence(Seq(outF, inF)), 100.seconds).toArray
    result(0).foreach {
      case (lvl, totalVehicleAtLevel) =>
        writeStats(record, lvl, "OutLinks", totalVehicleAtLevel)
    }
    result(1).foreach {
      case (lvl, totalVehicleAtLevel) =>
        writeStats(record, lvl, "InLinks", totalVehicleAtLevel)
    }
  }

  private def leavedLinkSingleThreaded(record: GenericData.Record, vehicleId: String): Unit = {
    val outLinks = vehicleOnUpstreamRoads(vehicleId)
    outLinks.foreach {
      case (lvl, counts) =>
        writeStats(record, lvl, "OutLinks", counts.sum)
    }
    val inLinks = vehicleOnDownstreamRoads(vehicleId)
    inLinks.foreach {
      case (lvl, counts) =>
        writeStats(record, lvl, "InLinks", counts.sum)
    }
  }

  private def writeStats(record: GenericData.Record, lvl: Int, linkType: String, totalVehicles: Int): Unit = {
    record.put(s"L${lvl}_TotalVeh_$linkType", totalVehicles)
  }
}

object LinkInOutFeature {
  case class Stats[T](counts: Array[T])(implicit num: Numeric[T]) {
    val total: T = counts.sum
    val min: T = counts.min
    val max: T = counts.max
    val sorted: Array[T] = counts.sorted
    val median: T = sorted(counts.length / 2)
    val avg: Double = num.toDouble(total) / counts.length
    val std: Double = new StandardDeviation().evaluate(counts.map(num.toDouble))
  }
}
