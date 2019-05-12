package beam.utils.traveltime

import java.io.{File, PrintWriter, Writer}
import java.util
import java.util.concurrent.TimeUnit

import com.typesafe.scalalogging.LazyLogging
import org.apache.avro.Schema
import org.apache.avro.Schema.Type
import org.apache.avro.generic.GenericData
import org.apache.hadoop.fs.Path
import org.apache.parquet.avro.AvroParquetWriter
import org.apache.parquet.hadoop.ParquetWriter
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Link
import org.matsim.core.events.handler.BasicEventHandler

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration.Deadline

sealed trait WriterType

object WriterType {
  case object Parquet extends WriterType
  case object Csv extends WriterType
}

class FeatureEventHandler(
  val links: util.Map[Id[Link], _ <: Link],
  val writerTypes: Set[WriterType],
  val outputPath: String,
  val featureExtractor: FeatureExtractor
) extends BasicEventHandler
    with LazyLogging
    with AutoCloseable
    with CsvHelper {

  val linkVehicleCount: mutable.Map[Int, Int] = mutable.Map[Int, Int]()
  val vehicleToEnterTime: mutable.Map[String, Double] = mutable.Map[String, Double]()

  val vehiclesInFrontOfMe: mutable.Map[String, Int] = mutable.Map[String, Int]()

  val fields: Seq[Schema.Field] = {
    val linkIdField = {
      new Schema.Field("link_id", Schema.create(Type.INT), "link_id", null.asInstanceOf[Any])
    }
    val vehicleId = {
      new Schema.Field("vehicle_id", Schema.create(Type.STRING), "vehicle_id", null.asInstanceOf[Any])
    }
    val travelTime = {
      new Schema.Field("travel_time", Schema.create(Type.DOUBLE), "travel_time", null.asInstanceOf[Any])
    }
    val enterTime = {
      new Schema.Field("enter_time", Schema.create(Type.DOUBLE), "enter_time", null.asInstanceOf[Any])
    }
    val leaveTime = {
      new Schema.Field("leave_time", Schema.create(Type.DOUBLE), "leave_time", null.asInstanceOf[Any])
    }
    val vehiclesOnRoad = {
      new Schema.Field("vehicles_on_road", Schema.create(Type.INT), "vehicles_on_road", null.asInstanceOf[Any])
    }
    List(linkIdField, vehicleId, travelTime, enterTime, leaveTime, vehiclesOnRoad)
  }

  val finalSchema: Schema = {
    val allFields = fields ++ featureExtractor.fields
    Schema.createRecord("feature_event_heander", "", "", false, allFields.asJava)
  }

  val maybeCsvWriter: Option[PrintWriter] = {
    if (writerTypes.contains(WriterType.Csv))
      Some(new PrintWriter(new File(outputPath)))
    else
      None
  }
  maybeCsvWriter.foreach(writeCsvHeader)

  val maybeParquetWriter: Option[ParquetWriter[GenericData.Record]] = {
    if (writerTypes.contains(WriterType.Parquet)) {
      val parquetWriter =
        AvroParquetWriter.builder[GenericData.Record](new Path(outputPath + ".parquet")).withSchema(finalSchema).build()
      Some(parquetWriter)
    } else
      None
  }

  var nLeftLinkEvent: Int = 0
  val nLeftEventsToLog: Int = 300000
  var startTime = Deadline.now
  var lastSimulatedTime: Double = 0.0

  override def close(): Unit = {
    maybeCsvWriter.foreach { wrt =>
      wrt.flush()
      wrt.close()
    }
    maybeParquetWriter.foreach { wrt =>
      wrt.close()
    }
  }

  def writeCsvHeader(writer: Writer): Unit = {
    implicit val wrt = writer
    fields.foreach { f =>
      writeColumnValue(f.name)(wrt, delimiter)
    }
    featureExtractor.csvWriteHeader(wrt)
    // Do not use `writeColumnValue`, it adds delimiter, but this is the last column
    wrt.append("dummy_column")
    wrt.append(System.lineSeparator())
    wrt.flush()
  }

  def handleEvent(event: Event): Unit = {
    val attrib = event.getAttributes
    val linkId = Option(attrib.get("link")).map(_.toInt).get
    val link = links.get(Id.create(attrib.get("link"), classOf[Link]))
    event.getEventType match {
      case "entered link" | "vehicle enters traffic" | "wait2link" =>
        val enterTime = event.getTime
        val vehicleId = attrib.get("vehicle")
        vehiclesInFrontOfMe.put(vehicleId, linkVehicleCount.getOrElse(linkId, 0))
        vehicleToEnterTime.put(vehicleId, enterTime)

        featureExtractor.enteredLink(event, link, vehicleId, linkVehicleCount)
        linkVehicleCount.put(linkId, linkVehicleCount.getOrElse(linkId, 0) + 1)

      case "left link" =>
        if (lastSimulatedTime == 0)
          lastSimulatedTime = event.getTime

        val vehicleId = attrib.get("vehicle")
        val enterTime = vehicleToEnterTime(vehicleId)
        val leaveTime = event.getTime
        val travelTime = leaveTime - enterTime
        val numOfVehicleOnTheRoad = vehiclesInFrontOfMe(vehicleId)
        linkVehicleCount.put(linkId, linkVehicleCount.getOrElse(linkId, 0) - 1)
        val record = new GenericData.Record(finalSchema)
        record.put("link_id", linkId)
        record.put("vehicle_id", vehicleId)
        record.put("travel_time", travelTime)
        record.put("enter_time", enterTime)
        record.put("leave_time", leaveTime)
        record.put("vehicles_on_road", numOfVehicleOnTheRoad)

        featureExtractor.leavedLink(event, link, vehicleId, linkVehicleCount, record)

        maybeCsvWriter.foreach { implicit wrt =>
          record.getSchema.getFields.asScala.foreach { field =>
            val rec = record.get(field.pos())
            writeColumnValue(rec.toString)
          }
          // Do not use `writeColumnValue`, it adds delimiter, but this is the last column
          wrt.append("d")
          wrt.append(System.lineSeparator())
        }

        maybeParquetWriter.foreach { wrt =>
          wrt.write(record)
        }

        if (nLeftLinkEvent != 0 && nLeftLinkEvent % nLeftEventsToLog == 0) {
          val duration = timestampToString(TimeUnit.SECONDS.toMillis(event.getTime.toLong))
          val simulatedSeconds = event.getTime - lastSimulatedTime
          logger.info(
            s"Wrote $nLeftEventsToLog stats in ${(Deadline.now - startTime).toMillis} ms. Total: ${nLeftLinkEvent}. Simulated seconds: $simulatedSeconds, current simulation time: $duration [${event.getTime}]"
          )
          startTime = Deadline.now
          lastSimulatedTime = event.getTime
        }
        nLeftLinkEvent += 1
      case _ =>
    }
  }

  def timestampToString(timestamp: Long): String = {
    val timeOfDay = timestamp % 86400000L
    val hours = timeOfDay / 3600000L
    val minutes = timeOfDay / 60000L % 60
    val seconds = timeOfDay / 1000L % 60
    val ms = timeOfDay % 1000
    f"$hours%02d:$minutes%02d:$seconds%02d.$ms%03d"
  }
}
