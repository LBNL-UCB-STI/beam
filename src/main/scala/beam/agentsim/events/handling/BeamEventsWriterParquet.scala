package beam.agentsim.events.handling

import java.io.IOException

import beam.agentsim.events.ScalaEvent
import beam.sim.BeamServices
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.avro.AvroParquetWriter
import org.apache.parquet.hadoop.ParquetWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.matsim.api.core.v01.events.Event

import scala.collection.JavaConverters._
import scala.collection.mutable

class BeamEventsWriterParquet(
  var outFileName: String,
  beamEventLogger: BeamEventsLogger,
  beamServices: BeamServices,
  eventTypeToLog: Class[_]
) extends BeamEventsWriterBase(beamEventLogger, beamServices, eventTypeToLog) {

  val columnNames: mutable.HashSet[String] = getColumnNames(eventTypeToLog)
  val schema: Schema = getSchema(columnNames)
  val strSchema: String = schema.toString(true)
  val parquetWriter: ParquetWriter[GenericData.Record] = getWriter(schema, outFileName)

  def getColumnNames(eventTypeToLog: Class[_]): mutable.HashSet[String] = {
    if (eventTypeToLog != null)
      mutable.HashSet(getClassAttributes(eventTypeToLog): _*)
    else
      beamEventLogger.getAllEventsToLog.asScala
        .foldLeft(mutable.HashSet.empty[String])((acc, clazz) => acc ++= getClassAttributes(clazz))

  }

  def getSchema(columnNames: Traversable[String]): Schema = {
    def getStrField(fieldName: String): Schema.Field =
      new Schema.Field(fieldName, Schema.create(Schema.Type.STRING), "", "")

    Schema.createRecord(
      "beam_events_File",
      "",
      "",
      false,
      columnNames.map(getStrField).toSeq.asJava
    )
  }

  def getWriter(schema: Schema, filePath: String): ParquetWriter[GenericData.Record] = {
    val path = new Path(filePath)
    val builder = AvroParquetWriter.builder[GenericData.Record](path)

    builder
      .withRowGroupSize(ParquetWriter.DEFAULT_BLOCK_SIZE)
      .withPageSize(ParquetWriter.DEFAULT_PAGE_SIZE)
      .withSchema(schema)
      .withConf(new Configuration())
      .withCompressionCodec(CompressionCodecName.GZIP)
      .withValidation(false)
      .withDictionaryEncoding(false)
      .build();
  }

  override protected def writeEvent(event: Event): Unit = {
    val genericDataRecord = toGenericDataRecord(event, columnNames)
    try {
      parquetWriter.write(genericDataRecord)
    } catch {
      case e: IOException => e.printStackTrace()
    }
  }

  override def closeFile(): Unit = {
    parquetWriter.close()
  }

  def toGenericDataRecord(event: Event, columnNames: mutable.HashSet[String]): GenericData.Record = {
    val eventAttributes = event.getAttributes

/*
    eventAttributes.asScala.keys.foreach { attName =>
      if (!columnNames.contains(attName))
        throw new RuntimeException("attribute " + attName + " does not contains in columns")
    }
    if (eventAttributes.size == 0) {
      throw new RuntimeException("there are 0 attributes")
    }
    if (eventAttributes.asScala.values.forall(value => value == "")) {
      throw new RuntimeException("all attributes are empty")
    }
*/

    val record = new GenericData.Record(schema)
    columnNames.foreach(att => record.put(att, ""))
    eventAttributes.forEach((key, value) => record.put(key, value))
    record
  }

  private def getClassAttributes(cla: Class[_]): Seq[String] = {
    // whole block of code was copypasted from java.beam.agentsim.events.handling.BeamEventsWriterCSV.registerClass method

    val attributes = scala.collection.mutable.ListBuffer.empty[String]

    if (classOf[ScalaEvent].isAssignableFrom(cla))
      for (method <- cla.getDeclaredMethods) {
        val name = method.getName
        def nameStartsWith(p: String): Boolean = name.startsWith(p)
        if ((nameStartsWith("ATTRIBUTE_") && (eventTypeToLog == null || !nameStartsWith("ATTRIBUTE_TYPE"))) ||
            (nameStartsWith("VERBOSE_") && (eventTypeToLog == null || !nameStartsWith("VERBOSE_"))))
          try {
            attributes += method.invoke(null).asInstanceOf[String]
          } catch {
            case e: Exception => e.printStackTrace()
          }
      }

    for (field <- cla.getFields) {
      def nameStartsWith(p: String): Boolean = field.getName.startsWith(p)
      if ((nameStartsWith("ATTRIBUTE_") && (eventTypeToLog == null || !nameStartsWith("ATTRIBUTE_TYPE"))) ||
          (nameStartsWith("VERBOSE_") && (eventTypeToLog == null || !nameStartsWith("VERBOSE_"))))
        try {
          attributes += field.get(null).toString
        } catch {
          case e: Exception => e.printStackTrace()
        }
    }

    attributes
  }

}
