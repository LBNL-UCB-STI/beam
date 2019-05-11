package beam.utils.traveltime

import java.io.{PrintWriter, Writer}
import java.util

import beam.utils.traveltime.LinkInOutFeature.Stats
import org.apache.avro.Schema
import org.apache.avro.Schema.Type
import org.apache.avro.generic.GenericData
import org.apache.hadoop.fs.Path
import org.apache.parquet.avro.AvroParquetWriter
import org.apache.parquet.hadoop.ParquetWriter
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link

import scala.collection.JavaConverters._
import scala.util.Try

class LinkMetadata(
  val level: Int,
  val linkIdToLink: util.Map[Id[Link], _ <: Link],
  val allLinks: Array[Link],
  val linkIdToOutLinks: Map[Link, Map[Int, Array[Int]]],
  val linkIdToOutLinkHops: Map[Link, Array[Int]],
  val linkIdToInLinks: Map[Link, Map[Int, Array[Int]]],
  val linkIdToInLinkHops: Map[Link, Array[Int]]
) extends CsvHelper {

  val zeroArr: Array[Int] = Array(0)

  val fields: Seq[Schema.Field] = {
    val mainFields = Array(
      new Schema.Field("link_id", Schema.create(Type.INT), "", null.asInstanceOf[Any]),
      new Schema.Field("length", Schema.create(Type.DOUBLE), "", null.asInstanceOf[Any]),
      new Schema.Field("capacity", Schema.create(Type.DOUBLE), "", null.asInstanceOf[Any]),
      new Schema.Field("flow_capacity", Schema.create(Type.DOUBLE), "", null.asInstanceOf[Any]),
      new Schema.Field("lanes", Schema.create(Type.DOUBLE), "", null.asInstanceOf[Any]),
      new Schema.Field("free_speed", Schema.create(Type.DOUBLE), "", null.asInstanceOf[Any]),
      new Schema.Field("FromNode_InLinksSize", Schema.create(Type.INT), "", null.asInstanceOf[Any]),
      new Schema.Field("FromNode_OutLinksSize", Schema.create(Type.INT), "", null.asInstanceOf[Any]),
      new Schema.Field("FromNode_TotalLinksSize", Schema.create(Type.INT), "", null.asInstanceOf[Any]),
      new Schema.Field("ToNode_InLinksSize", Schema.create(Type.INT), "", null.asInstanceOf[Any]),
      new Schema.Field("ToNode_OutLinksSize", Schema.create(Type.INT), "", null.asInstanceOf[Any]),
      new Schema.Field("ToNode_TotalLinksSize", Schema.create(Type.INT), "", null.asInstanceOf[Any]),
    )

    val stats = createStatFields("Length", "OutLinks") ++
    createStatFields("Capacity", "OutLinks") ++
    createStatFields("FlowCapacity", "OutLinks") ++
    createStatFields("Lanes", "OutLinks") ++
    createStatFields("FreeSpeed", "OutLinks") ++
    createStatFields("Length", "InLinks") ++
    createStatFields("Capacity", "InLinks") ++
    createStatFields("FlowCapacity", "InLinks") ++
    createStatFields("Lanes", "InLinks") ++
    createStatFields("FreeSpeed", "InLinks")

    mainFields ++ stats
  }

  val schema: Schema = {
    Schema.createRecord("link_metadata", "", "", false, fields.asJava)
  }

  def writeSingleStat[T](record: GenericData.Record, lvl: Int, name: String, linkType: String, arr: Array[T])(
    implicit num: Numeric[T]
  ): Unit = {
    val stats = Stats(arr)
    record.put(s"L${lvl}_Total${name}_${linkType}", stats.total)
    record.put(s"L${lvl}_Min${name}_${linkType}", stats.min)
    record.put(s"L${lvl}_Max${name}_${linkType}", stats.max)
    record.put(s"L${lvl}_Median${name}_${linkType}", stats.median)
    record.put(s"L${lvl}_Avg${name}_${linkType}", stats.avg)
    record.put(s"L${lvl}_Std${name}_${linkType}", stats.std)
  }

  def writeStats(record: GenericData.Record, linkType: String, levelToLinks: Map[Int, Array[Int]]): Unit = {
    (1 to level).foreach { lvl =>
      levelToLinks.get(lvl).map { lids =>
        lids.map(lid => linkIdToLink.get(Id.create(lid, classOf[Link])))
      } match {
        case Some(links) =>
          writeSingleStat(record, lvl, "Length", linkType, links.map(_.getLength))
          writeSingleStat(record, lvl, "Capacity", linkType, links.map(_.getCapacity))
          writeSingleStat(record, lvl, "FlowCapacity", linkType, links.map(_.getFlowCapacityPerSec))
          writeSingleStat(record, lvl, "Lanes", linkType, links.map(_.getNumberOfLanes))
          writeSingleStat(record, lvl, "FreeSpeed", linkType, links.map(_.getFreespeed))
        case None =>
          writeSingleStat(record, lvl, "Length", linkType, zeroArr)
          writeSingleStat(record, lvl, "Capacity", linkType, zeroArr)
          writeSingleStat(record, lvl, "FlowCapacity", linkType, zeroArr)
          writeSingleStat(record, lvl, "Lanes", linkType, zeroArr)
          writeSingleStat(record, lvl, "FreeSpeed", linkType, zeroArr)
      }
    }
  }

  def write(pathWithoutExtension: String, writerType: WriterType): Unit = {
    val maybeCsvWriter: Option[Writer] =
      if (writerType == WriterType.Csv) Some(new PrintWriter(pathWithoutExtension + ".csv")) else None
    val maybeParquetWriter: Option[ParquetWriter[GenericData.Record]] = {
      if (writerType == WriterType.Parquet) {
        Some(
          AvroParquetWriter
            .builder[GenericData.Record](new Path(pathWithoutExtension + ".parquet"))
            .withSchema(schema)
            .build()
        )
      } else None
    }
    try {
      maybeCsvWriter.foreach(writeCsvHeader)

      allLinks.foreach { link =>
        val record = new GenericData.Record(schema)
        record.put("link_id", link.getId.toString.toInt)
        record.put("length", link.getLength)
        record.put("capacity", link.getCapacity)
        record.put("flow_capacity", link.getFlowCapacityPerSec)
        record.put("lanes", link.getNumberOfLanes)
        record.put("free_speed", link.getFreespeed)
        val FromNode_InLinksSize =
          Option(link.getFromNode).flatMap(x => Option(x.getInLinks.asScala.values)).map(_.size).getOrElse(0)
        val FromNode_OutLinksSize =
          Option(link.getFromNode).flatMap(x => Option(x.getOutLinks.asScala.values)).map(_.size).getOrElse(0)
        record.put("FromNode_InLinksSize", FromNode_InLinksSize)
        record.put("FromNode_OutLinksSize", FromNode_OutLinksSize)
        record.put("FromNode_TotalLinksSize", FromNode_InLinksSize + FromNode_OutLinksSize)
        val ToNode_InLinksSize =
          Option(link.getToNode).flatMap(x => Option(x.getInLinks.asScala.values)).map(_.size).getOrElse(0)
        val ToNode_OutLinksSize =
          Option(link.getToNode).flatMap(x => Option(x.getOutLinks.asScala.values)).map(_.size).getOrElse(0)
        record.put("ToNode_InLinksSize", ToNode_InLinksSize)
        record.put("ToNode_OutLinksSize", ToNode_OutLinksSize)
        record.put("ToNode_TotalLinksSize", ToNode_InLinksSize + ToNode_OutLinksSize)

        writeStats(record, "OutLinks", linkIdToOutLinks(link))
        writeStats(record, "InLinks", linkIdToInLinks(link))

        maybeCsvWriter.foreach { writer =>
          record.getSchema.getFields.asScala.foreach { field =>
            val rec = record.get(field.pos())
            writeColumnValue(rec.toString)(writer, delimiter)
          }
          // writeLinksPerLevel(linkIdToOutLinks(link))
          // writeLinksPerLevel(linkIdToInLinks(link))

          // Do not use `writeColumnValue`, it adds delimiter, but this is the last column
          writer.append("d")
          writer.append(System.lineSeparator())
        }
        maybeParquetWriter.foreach(_.write(record))
      }
    } finally {
      Try(maybeCsvWriter.foreach { writer =>
        writer.flush()
        writer.close()
      })
      maybeParquetWriter.foreach(_.close())
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

  def createStatFields(name: String, linkType: String): Seq[Schema.Field] = {
    (1 to level).flatMap { lvl =>
      Array(
        new Schema.Field(s"L${lvl}_Total${name}_${linkType}", Schema.create(Type.DOUBLE), "", null.asInstanceOf[Any]),
        new Schema.Field(s"L${lvl}_Min${name}_${linkType}", Schema.create(Type.DOUBLE), "", null.asInstanceOf[Any]),
        new Schema.Field(s"L${lvl}_Max${name}_${linkType}", Schema.create(Type.DOUBLE), "", null.asInstanceOf[Any]),
        new Schema.Field(s"L${lvl}_Median${name}_${linkType}", Schema.create(Type.DOUBLE), "", null.asInstanceOf[Any]),
        new Schema.Field(s"L${lvl}_Avg${name}_${linkType}", Schema.create(Type.DOUBLE), "", null.asInstanceOf[Any]),
        new Schema.Field(s"L${lvl}_Std${name}_${linkType}", Schema.create(Type.DOUBLE), "", null.asInstanceOf[Any])
      )
    }
  }

  def writeCsvHeader(writer: Writer): Unit = {
    implicit val wrt = writer
    fields.foreach { f =>
      writeColumnValue(f.name)(wrt, delimiter)
    }
    // Do not use `writeColumnValue`, it adds delimiter, but this is the last column
    wrt.append("dummy_column")
    wrt.append(System.lineSeparator())
    wrt.flush()
  }
}
