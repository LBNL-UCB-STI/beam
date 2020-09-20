package beam.agentsim.events.handling

import beam.agentsim.events.ScalaEvent
import beam.sim.BeamServices
import com.typesafe.scalalogging.LazyLogging
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
) extends BeamEventsWriterBase(beamEventLogger, beamServices, eventTypeToLog)
    with LazyLogging {

  sealed trait ParquetType
  final object PDouble extends ParquetType
  final object PInteger extends ParquetType
  final object PBoolean extends ParquetType

  val fieldNameToType: Map[String, ParquetType] = getFieldNameToTypeMap
  val columnNames: Seq[String] = getColumnNames
  val schema: Schema = getSchema(columnNames)
  val parquetWriter: ParquetWriter[GenericData.Record] = getWriter(schema, outFileName)

  def fieldDefaultValue(fieldName: String): Any = fieldNameToType.get(fieldName) match {
    case Some(PDouble)  => 0.0
    case Some(PInteger) => 0
    case Some(PBoolean) => false
    case _              => ""
  }

  def fieldSchema(fieldName: String): Schema = fieldNameToType.get(fieldName) match {
    case Some(PDouble)  => Schema.create(Schema.Type.DOUBLE)
    case Some(PInteger) => Schema.create(Schema.Type.INT)
    case Some(PBoolean) => Schema.create(Schema.Type.BOOLEAN)
    case _              => Schema.create(Schema.Type.STRING)
  }

  private def getColumnNames: Seq[String] = {
    if (eventTypeToLog != null)
      getClassAttributes(eventTypeToLog)
    else
      beamEventLogger.getAllEventsToLog.asScala
        .foldLeft(mutable.HashSet.empty[String])((acc, clazz) => acc ++= getClassAttributes(clazz))
        .toSeq
  }

  def getSchema(columnNames: Traversable[String]): Schema = {
    def getStrField(fieldName: String): Schema.Field =
      new Schema.Field(fieldName, fieldSchema(fieldName), "", fieldDefaultValue(fieldName))

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
      .build()
  }

  override protected def writeEvent(event: Event): Unit = {
    val genericDataRecord = toGenericDataRecord(event, columnNames)
    parquetWriter.write(genericDataRecord)
  }

  override def closeFile(): Unit = {
    parquetWriter.close()
  }

  def toGenericDataRecord(event: Event, columnNames: Seq[String]): GenericData.Record = {
    val eventAttributes = event.getAttributes

    val record = new GenericData.Record(schema)
    columnNames.foreach(att => record.put(att, fieldDefaultValue(att)))

    eventAttributes.forEach((attName, attVal) => {
      val valueWithCorrectType = fieldNameToType.get(attName) match {
        case Some(PDouble)  => attVal.toDouble
        case Some(PInteger) => attVal.toInt
        case Some(PBoolean) => attVal.toBoolean
        case _              => attVal
      }

      record.put(attName, valueWithCorrectType)
    })

    record
  }

  private def getClassAttributes(cla: Class[_]): Seq[String] = {
    val attributes = scala.collection.mutable.ListBuffer.empty[String]

    if (classOf[ScalaEvent].isAssignableFrom(cla))
      cla.getDeclaredMethods.foreach { method =>
        val name = method.getName
        def nameStartsWith(p: String): Boolean = name.startsWith(p)
        if ((nameStartsWith("ATTRIBUTE_") && (eventTypeToLog == null || !nameStartsWith("ATTRIBUTE_TYPE"))) ||
            (nameStartsWith("VERBOSE_") && (eventTypeToLog == null || !nameStartsWith("VERBOSE_"))))
          try {
            attributes += method.invoke(null).asInstanceOf[String]
          } catch {
            case e: Exception => logger.error("exception occurred due to ", e)
          }
      }

    cla.getFields.foreach { field =>
      def nameStartsWith(p: String): Boolean = field.getName.startsWith(p)
      if ((nameStartsWith("ATTRIBUTE_") && (eventTypeToLog == null || !nameStartsWith("ATTRIBUTE_TYPE"))) ||
          (nameStartsWith("VERBOSE_") && (eventTypeToLog == null || !nameStartsWith("VERBOSE_"))))
        try {
          attributes += field.get(null).toString
        } catch {
          case e: Exception => logger.error("exception occurred due to ", e)
        }
    }

    attributes
  }

  def getFieldNameToTypeMap: Map[String, ParquetType] = Map(
    "price"                    -> PDouble, //0.0
    "tollPaid"                 -> PDouble, //0.0
    "length"                   -> PDouble, //0.0, 559.979, 205.071
    "secondaryFuelLevel"       -> PDouble, //0.0, 3.65598E9, 0.0
    "fuel"                     -> PDouble, //0.0, 2532300.0, 0.0
    "primaryFuel"              -> PDouble, //0.0, 1.1226458992000002E7, 4111263.4079999994
    "startX"                   -> PDouble, //-122.0, -122.418112, -122.4202577
    "startY"                   -> PDouble, //38.0, 37.7486745, 37.7450362
    "expectedMaximumUtility"   -> PDouble, //NaN
    "primaryFuelLevel"         -> PDouble, //4.68E10, 2.9988773541008E10, 2.99846622776E10
    "locationY"                -> PDouble, //37.8001929, 37.7832913, 37.738846999998344
    "secondaryFuel"            -> PDouble, //0.0
    "time"                     -> PDouble, //0.0, 14700.0, 14880.0
    "duration"                 -> PDouble, //0.0, 1101.0, 0.0
    "endY"                     -> PDouble, //38.001, 37.7440583, 37.7424152
    "endX"                     -> PDouble, //-122.001, -122.4208899, -122.421952
    "score"                    -> PDouble, //-0.0, 0.0, -0.0
    "locationX"                -> PDouble, //-122.44119629999999, -122.4327195, -122.39781000000197
    "seatingCapacity"          -> PInteger, //168, 19, 29
    "location"                 -> PInteger, //91618, 19450, 31138
    "cost"                     -> PInteger, //0
    "arrivalTime"              -> PInteger, //14700, 14880, 14853
    "departTime"               -> PInteger, //36062, 36746, 40772
    "departureTime"            -> PInteger, //14400, 14520, 14760
    "link"                     -> PInteger, //91618, 31138, 19450
    "tourIndex"                -> PInteger, //1, 2, 1
    "numPassengers"            -> PInteger, //0, 1, 0
    "capacity"                 -> PInteger, //600, 29, 39
    "personalVehicleAvailable" -> PBoolean //true, false

    /*
   with String type:
     "pricingModel" ,List(Block, FlatFee, Block))
     "chargingType",List(None, ultrafast(250.0|DC), None))
     "driver,List(TransitDriverAgent-BA:01R11, TransitDriverAgent-BA:01SFO10, TransitDriverAgent-SF:7596499))"
     "type,List(PersonEntersVehicle, PathTraversal, ModeChoice))"
     "secondaryFuelType,List(None, Gasoline, None))"
     "actType,List(Home, Work, Home))"
     "availableAlternatives,List(WALK:CAR:WALK_TRANSIT:RIDE_HAIL, CAR:DRIVE_TRANSIT:WALK_TRANSIT:WALK:RIDE_HAIL, WALK:CAR:WALK_TRANSIT:RIDE_HAIL))"
     "parkingType,List(Residential, Public, Residential))"
     "vehicle,List(rideHailVehicle-035100-2012000390561-0-7061061, rideHailVehicle-030800-2016000227368-1-2604009, rideHailVehicle-022902-2012001172726-0-6793548))"
     "mode,List(subway, bus, tram))"
     "vehicleType,List(SUBWAY-DEFAULT, BUS-DEFAULT, TRAM-DEFAULT))"
     "person,List(rideHailAgent-035100-2012000390561-0-7061061, rideHailAgent-030800-2016000227368-1-2604009, rideHailAgent-022902-2012001172726-0-6793548))"
     "primaryFuelType,List(Electricity, Diesel, Electricity))"
     "currentTourMode,List(, car, ))"
     "parkingTaz", //100634, 100372, 100652I

   with array type:
     "links" array of Int
     "linkTravelTime" array of Double
   */
  )
}
