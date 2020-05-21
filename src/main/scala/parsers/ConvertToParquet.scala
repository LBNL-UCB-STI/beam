package parsers

import beam.router.skim.{AbstractSkimmerInternal, AbstractSkimmerKey}
import beam.utils.FileUtils
import com.typesafe.scalalogging.LazyLogging
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.avro.AvroParquetWriter
import org.apache.parquet.hadoop.ParquetWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._
import scala.collection.mutable

sealed trait ParquetType

object PDouble extends ParquetType

object PInteger extends ParquetType

object PBoolean extends ParquetType

object PString extends ParquetType


object ConvertToParquet extends App with LazyLogging {
  val path = new Path("/Users/crixal/work/2644/test.parquet")

  val res = mutable.Map.empty[AbstractSkimmerKey, AbstractSkimmerInternal]
  var mapReader: CsvMapReader = new CsvMapReader(FileUtils.readerFromFile("/Users/crixal/work/2644/0.skims_for_Dmitry_sample.csv.gz"), CsvPreference.STANDARD_PREFERENCE)

  val fieldNameToType = Map(
    "hour" -> PInteger,
    "mode" -> PString,
    "origTaz" -> PString,
    "destTaz" -> PString,
    "travelTimeInS" -> PDouble,
    "generalizedTimeInS" -> PDouble,
    "cost" -> PDouble,
    "generalizedCost" -> PDouble,
    "distanceInM" -> PDouble,
    "energy" -> PDouble,
    "observations" -> PInteger,
    "iterations" -> PInteger)

  var counter: Long = 0
  try {
    val header = mapReader.getHeader(true)

    val schema: Schema = getSchema(header)
    val builder = AvroParquetWriter.builder[GenericData.Record](path)

    val parquetWriter = builder
      .withRowGroupSize(ParquetWriter.DEFAULT_BLOCK_SIZE)
      .withPageSize(ParquetWriter.DEFAULT_PAGE_SIZE)
      .withSchema(schema)
      .withConf(new Configuration())
      .withCompressionCodec(CompressionCodecName.GZIP)
      .withValidation(false)
      .withDictionaryEncoding(false)
      .build()

    var line: java.util.Map[String, String] = mapReader.read(header: _*)
    while (null != line) {
      val record = new GenericData.Record(schema)
      record.put("hour", line.get("hour").toInt)
      record.put("mode", line.get("mode"))
      record.put("origTaz", line.get("origTaz"))
      record.put("destTaz", line.get("destTaz"))
      record.put("travelTimeInS", line.get("travelTimeInS").toDouble)
      record.put("generalizedTimeInS", line.get("generalizedTimeInS").toDouble)
      record.put("generalizedCost", line.get("generalizedCost").toDouble)
      record.put("distanceInM", line.get("distanceInM").toDouble)
      record.put("cost", line.get("cost").toDouble)
      record.put("energy", Option(line.get("energy")).map(_.toDouble).getOrElse(0.0))
      record.put("observations", line.get("observations").toInt)
      record.put("iterations", line.get("iterations").toInt)

      parquetWriter.write(record)
      line = mapReader.read(header: _*)

      counter += 1
      if (counter % 1000000 == 0) {
        logger.info("Processed: {} rows", counter)
      }
    }
    parquetWriter.close()
  } finally {
    if (null != mapReader)
      mapReader.close()
  }

  def getSchema(columnNames: Traversable[String]): Schema = {
    def getStrField(fieldName: String): Schema.Field =
      new Schema.Field(fieldName, fieldSchema(fieldName), "", fieldDefaultValue(fieldName))

    Schema.createRecord(
      "test",
      "",
      "",
      false,
      columnNames.map(getStrField).toSeq.asJava
    )
  }

  def fieldSchema(fieldName: String): Schema = fieldNameToType.get(fieldName) match {
    case Some(PDouble) => Schema.create(Schema.Type.DOUBLE)
    case Some(PInteger) => Schema.create(Schema.Type.INT)
    case Some(PBoolean) => Schema.create(Schema.Type.BOOLEAN)
    case _ => Schema.create(Schema.Type.STRING)
  }

  def fieldDefaultValue(fieldName: String): Any = fieldNameToType.get(fieldName) match {
    case Some(PDouble) => 0.0
    case Some(PInteger) => 0
    case Some(PBoolean) => false
    case _ => ""
  }
}
