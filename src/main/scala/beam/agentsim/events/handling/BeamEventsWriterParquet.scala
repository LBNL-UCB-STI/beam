package beam.agentsim.events.handling

import beam.sim.BeamServices
import java.io.IOException
import java.lang.reflect.Field
import java.util.ArrayList
import java.util.List

import beam.agentsim.events.ScalaEvent
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.avro.AvroParquetWriter
import org.apache.parquet.hadoop.ParquetWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.matsim.api.core.v01.events.Event

import scala.collection.mutable;

class BeamEventsWriterParquet(
  var outFileName: String,
  beamEventLogger: BeamEventsLogger,
  beamServices: BeamServices,
  eventTypeToLog: Class[_]
) extends BeamEventsWriterBase(outFileName, beamEventLogger, beamServices, eventTypeToLog) {

  val schema: Schema = getSchema(eventTypeToLog)
  val parquetWriter: ParquetWriter[GenericData.Record] = getWriter(schema)

  def getSchema(eventTypeToLog: Class[_]): Schema = {
    import scala.collection.JavaConverters._

    val attributes =
      if (eventTypeToLog != null) registerClass(eventTypeToLog)
      else
        beamEventLogger.getAllEventsToLog.asScala
          .foldLeft(mutable.Map.empty[String, String])((acc, clazz) => acc ++ registerClass(clazz))

    val fields = mutable.ListBuffer.empty[Schema.Field]
    attributes.foreach {
      case (attName, _) => fields += new Schema.Field(attName, Schema.create(Schema.Type.STRING), "", "")
    }

    val sch = Schema.createRecord("name","doc","namespace", false, fields.asJava)
    sch
  }

  def getWriter(schema: Schema): ParquetWriter[GenericData.Record] = {
    val path = new Path(outFileName)
    val builder = AvroParquetWriter.builder[GenericData.Record](path)

    builder
      .withRowGroupSize(ParquetWriter.DEFAULT_BLOCK_SIZE)
      .withPageSize(ParquetWriter.DEFAULT_PAGE_SIZE)
      .withSchema(schema)
      .withConf(new Configuration())
      .withCompressionCodec(CompressionCodecName.SNAPPY)
      .withValidation(false)
      .withDictionaryEncoding(false)
      .build();
  }

  override protected def writeEvent(event: Event): Unit = {
    val genericDataRecord = toGenericDataRecord(event)
    try {
      parquetWriter.write(genericDataRecord)
    } catch {
      case e: IOException => e.printStackTrace()
    }
  }

  override def closeFile(): Unit = {
    parquetWriter.close()
  }

  def toGenericDataRecord(event: Event): GenericData.Record = {
    val record = new GenericData.Record(schema)
    event.getAttributes.forEach((key, value) => record.put(key, value))

    record
  }

  private def registerClass(cla: Class[_]): scala.collection.mutable.Map[String, String] = {
    // ScalaEvent classes are from scala, so we have to have special treatment for them
    // scala's val and var are not actual fields, but methods (getters and setters)

    val attributes = scala.collection.mutable.Map.empty[String, String]

    if (classOf[ScalaEvent].isAssignableFrom(cla))
      for (method <- cla.getDeclaredMethods) {
        val name = method.getName
        if ((name.startsWith("ATTRIBUTE_") && (eventTypeToLog == null || !name.startsWith("ATTRIBUTE_TYPE"))) ||
            (name.startsWith("VERBOSE_") && (eventTypeToLog == null || !name.startsWith("VERBOSE_"))))
          try { // Call static method
            val value = method.invoke(null).asInstanceOf[String]
            attributes.put(value, "String")
          } catch {
            case e: Exception =>
              e.printStackTrace()
          }
      }
    val fields = cla.getFields
    for (field <- fields) {
      if ((field.getName.startsWith("ATTRIBUTE_") && (eventTypeToLog == null || !field.getName.startsWith(
            "ATTRIBUTE_TYPE"
          ))) ||
          (field.getName.startsWith("VERBOSE_") && (eventTypeToLog == null || !field.getName.startsWith("VERBOSE_"))))
        try {
          val value = field.get(null).toString
          attributes.put(value, "String")
        } catch {
          case e @ (_: IllegalArgumentException | _: IllegalAccessException) =>
            e.printStackTrace()
        }
    }

    attributes
  }

}
