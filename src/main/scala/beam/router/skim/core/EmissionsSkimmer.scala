package beam.router.skim.core

import beam.agentsim.agents.vehicles.VehicleEmissions.Emissions._
import beam.agentsim.agents.vehicles.VehicleEmissions.{Emissions, EmissionsProfile}
import beam.router.skim.{readonly, Skims}
import beam.sim.config.BeamConfig
import beam.utils.{OutputDataDescriptor, OutputDataDescriptorObject}
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericRecord}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.avro.AvroParquetWriter
import org.apache.parquet.hadoop.ParquetFileWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.hadoop.util.HadoopOutputFile
import org.apache.spark.sql.Row
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.MatsimServices
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.utils.io.IOUtils
import java.io.BufferedWriter
import java.util.concurrent.ConcurrentHashMap
import scala.util.{Failure, Success, Try}

class EmissionsSkimmer @Inject() (matsimServices: MatsimServices, beamConfig: BeamConfig)
    extends AbstractSkimmer(beamConfig, matsimServices.getControlerIO) {
  import EmissionsSkimmer._
  private val config: BeamConfig.Beam.Router.Skim = beamConfig.beam.router.skim

  override lazy val readOnlySkim: AbstractSkimmerReadOnly = new readonly.EmissionsSkims()

  override protected val skimName: String = config.emissions_skimmer.name
  override protected val skimType: Skims.SkimType.Value = Skims.SkimType.EMISSIONS_SKIMMER
  override protected val skimFileBaseName: String = config.emissions_skimmer.fileBaseName
  override protected val skimOutputFormat: String = config.emissions_skimmer.fileOutputFormat

  override protected val skimFileHeader: String = {
    s"hour,linkId,vehicleTypeId,process,emissions,travelTimeInSecond,parkingDurationInSecond,observations,iterations"
  }

  private val currentPackedSkimInternal = new ConcurrentHashMap[java.lang.Long, EmissionsSkimmerInternal]()

  override def handleEvent(event: Event): Unit = {
    event match {
      case e: beam.router.skim.event.EmissionsSkimmerEvent if e.getEventType == eventType =>
        val packedKey = packKey(e.linkId, e.vehicleType, (e.time / 3600).toInt % 24, e.emissionsProcess)
        val currentValue =
          EmissionsSkimmerInternal(
            e.emissions,
            e.travelTime,
            e.parkingDuration,
            1,
            matsimServices.getIterationNumber + 1
          )
        currentPackedSkimInternal.compute(
          java.lang.Long.valueOf(packedKey),
          (_, previousValue) =>
            aggregateWithinIteration(Option(previousValue), currentValue).asInstanceOf[EmissionsSkimmerInternal]
        )
      case _ =>
    }
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    if (config.writeSkimsInterval > 0 && readOnlySkim.currentIterationInternal % config.writeSkimsInterval == 0) {
      val filePath = matsimServices.getControlerIO
        .getIterationFilename(readOnlySkim.currentIterationInternal, s"$skimFileBaseName.$skimOutputFormat")
      writePackedSkim(filePath)
    }

    currentSkimInternal.clear()
    currentPackedSkimInternal.clear()
  }

  private def writePackedSkim(filePath: String): Unit = {
    filePath.toLowerCase match {
      case path if path.endsWith(".parquet") =>
        writePackedSkimsAsParquet(filePath)

      case path if path.endsWith(".csv.gz") || path.endsWith(".csv.gzip") || path.endsWith(".csv") =>
        writePackedSkimsAsCsv(filePath)

      case _ =>
        val error = s"Unsupported file format for writing skims: $filePath"
        println(error)
        throw new IllegalArgumentException(error)
    }
  }

  private def writePackedSkimsAsCsv(filePath: String): Unit = {
    var writer: BufferedWriter = null
    try {
      writer = IOUtils.getBufferedWriter(filePath)
      writer.write(skimFileHeader + "\n")
      val batch = new java.lang.StringBuilder(math.min(1 << 20, currentPackedSkimInternal.size.max(1) * 64))
      currentPackedSkimInternal.forEach { (packedKey, value) =>
        val key = packedKey.longValue()
        batch.append(unpackHour(key))
        batch.append(",")
        batch.append(unpackLinkId(key))
        batch.append(",")
        batch.append(unpackVehicleTypeId(key))
        batch.append(",")
        batch.append(unpackProcessName(key))
        batch.append(",")
        batch.append(value.toCsv)
        batch.append("\n")
        if (batch.length() >= (1 << 20)) {
          writer.write(batch.toString)
          batch.setLength(0)
        }
      }
      if (batch.length() > 0) {
        writer.write(batch.toString)
      }
    } finally {
      if (writer != null) writer.close()
    }
  }

  private def writePackedSkimsAsParquet(filePath: String): Unit = {
    logger.info(s"Writing ${currentPackedSkimInternal.size} emissions skim records to Parquet file: $filePath")

    if (currentPackedSkimInternal.isEmpty) {
      logger.warn("Attempting to write empty emissions skim map to Parquet file")
      return
    }

    Try {
      val schema = createEmissionsAvroSchema()
      val conf = new Configuration()

      val hadoopPath = new Path(filePath)
      val fs = hadoopPath.getFileSystem(conf)
      val hadoopFile = HadoopOutputFile.fromPath(hadoopPath, conf)

      val parentDir = hadoopPath.getParent
      if (!fs.exists(parentDir)) {
        fs.mkdirs(parentDir)
      }

      val writer = AvroParquetWriter
        .builder[GenericRecord](hadoopFile)
        .withSchema(schema)
        .withConf(conf)
        .withCompressionCodec(CompressionCodecName.SNAPPY)
        .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
        .build()

      try {
        var recordCount = 0
        val record = new GenericData.Record(schema)
        currentPackedSkimInternal.forEach { (packedKey, value) =>
          populatePackedEmissionsAvroRecord(record, packedKey.longValue(), value)
          writer.write(record)
          recordCount += 1

          if (recordCount % 10000 == 0) {
            logger.debug(s"Written $recordCount emissions records to Parquet file")
          }
        }
        logger.info(s"Successfully wrote $recordCount emissions records to $filePath")
      } finally {
        writer.close()
      }

    } match {
      case Success(_) =>
        logger.info(s"Emissions Parquet file written successfully: $filePath")
        println(s"Emissions Parquet file written successfully: $filePath")

      case Failure(ex) =>
        logger.error(s"Failed to write emissions Parquet file: $filePath", ex)
        println(s"Failed to write emissions Parquet file: $filePath", ex)
        throw new RuntimeException(s"Failed to write emissions Parquet file: $filePath", ex)
    }
  }

  private def createEmissionsAvroSchema(): Schema = {
    val schemaString =
      """
  {
    "type": "record",
    "name": "EmissionsSkimRecord",
    "namespace": "beam.router.skim.emissions",
    "fields": [
      {"name": "hour", "type": "int"},
      {"name": "linkId", "type": "int"},
      {"name": "vehicleTypeId", "type": "string"},
      {"name": "process", "type": "string"},
      {"name": "emissions", "type": "string"},
      {"name": "travelTimeInSecond", "type": "double"},
      {"name": "parkingDurationInSecond", "type": "double"},
      {"name": "observations", "type": "int"},
      {"name": "iterations", "type": "int"}
    ]
  }
  """
    new Schema.Parser().parse(schemaString)
  }

  private def populatePackedEmissionsAvroRecord(
    record: GenericRecord,
    packedKey: Long,
    value: EmissionsSkimmerInternal
  ): Unit = {
    record.put("hour", unpackHour(packedKey))
    record.put("linkId", unpackLinkId(packedKey))
    record.put("vehicleTypeId", unpackVehicleTypeId(packedKey))
    record.put("process", unpackProcessName(packedKey))
    record.put("emissions", value.pollutantsString)
    record.put("travelTimeInSecond", value.travelTime)
    record.put("parkingDurationInSecond", value.parkingDuration)
    record.put("observations", value.observations)
    record.put("iterations", value.iterations)
  }

  private def parsePollutantsString(pollutantsString: String): Emissions = {
    val emissionsMap = if (pollutantsString != null && pollutantsString.nonEmpty) {
      pollutantsString
        .split(";")
        .filter(_.nonEmpty)
        .map { entry =>
          val parts = entry.split(":")
          if (parts.length == 2) {
            try {
              Emissions.fromString(parts(0)).map(_ -> parts(1).toDouble)
            } catch {
              case _: Exception => None
            }
          } else None
        }
        .collect { case Some(kv) => kv }
        .toMap
    } else Map.empty[EmissionType, Double]

    Emissions(emissionsMap)
  }

  override protected def fromParquetRow(row: Row): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    // Helper function to safely get row values with defaults
    def getSafeInt(field: String, default: Int = 0): Int = {
      Try(row.getAs[Int](field)).getOrElse {
        logger.warn(s"Missing or invalid field '$field', using default: $default")
        default
      }
    }

    def getSafeDouble(field: String, default: Double = 0.0): Double = {
      Try(row.getAs[Double](field)).getOrElse {
        logger.warn(s"Missing or invalid field '$field', using default: $default")
        default
      }
    }

    def getSafeString(field: String, default: String = ""): String = {
      Try(row.getAs[String](field)).getOrElse {
        logger.warn(s"Missing or invalid field '$field', using default: '$default'")
        default
      }
    }

    try {
      // Extract key fields with safe access
      val hour = getSafeInt("hour")
      val linkId = getSafeInt("linkId")
      val vehicleTypeId = getSafeString("vehicleTypeId")
      val emissionsProcessStr = getSafeString("process")

      val emissionsProfile = EmissionsProfile.fromString(emissionsProcessStr).getOrElse {
        throw new IllegalArgumentException(s"Unknown emissions process: '$emissionsProcessStr'")
      }

      // Create the key
      val key = EmissionsSkimmerKey(linkId, canonicalVehicleTypeId(vehicleTypeId), hour, emissionsProfile)

      // Extract value fields with safe access
      val travelTime = getSafeDouble("travelTimeInSecond")
      val parkingDuration = getSafeDouble("parkingDurationInSecond")
      val observations = getSafeInt("observations")
      val iterations = getSafeInt("iterations")

      val pollutantsString = getSafeString("emissions")

      // Convert pollutants string back to Emissions object
      val emissions = parsePollutantsString(pollutantsString)

      // Create the value
      val value = EmissionsSkimmerInternal(emissions, travelTime, parkingDuration, observations, iterations)

      (key, value)

    } catch {
      case ex: Exception =>
        logger.error(s"Failed to parse Parquet row: ${rowToString(row)}", ex)
        throw new RuntimeException(s"Failed to parse Parquet row for EmissionsSkimmer", ex)
    }
  }

  /**
    * Convert a Row to string for logging purposes
    */
  private def rowToString(row: Row): String = {
    Try {
      val fields = row.schema.fieldNames.map { fieldName =>
        s"$fieldName: ${try { row.getAs[Any](fieldName) }
        catch { case _: Exception => "ERROR" }}"
      }
      fields.mkString("Row(", ", ", ")")
    }.getOrElse("Row(could not convert to string)")
  }

  override def fromCsv(
    line: scala.collection.Map[String, String]
  ): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    val emissionsMap = line
      .getOrElse("emissions", "")
      .split(";")
      .filter(_.nonEmpty)
      .map { entry =>
        val parts = entry.split(":")
        if (parts.length == 2) {
          try {
            Emissions.fromString(parts(0)).map(_ -> parts(1).toDouble)
          } catch {
            case _: Exception => None
          }
        } else None
      }
      .collect { case Some(kv) => kv }
      .toMap

    (
      EmissionsSkimmerKey(
        line("linkId").toInt,
        canonicalVehicleTypeId(line("vehicleTypeId")),
        line("hour").toInt,
        EmissionsProfile.withName(line("process"))
      ),
      EmissionsSkimmerInternal(
        Emissions(emissionsMap),
        line("travelTimeInSecond").toDouble,
        line("parkingDurationInSecond").toDouble,
        line("observations").toInt,
        line("iterations").toInt
      )
    )
  }

  override protected def aggregateOverIterations(
    prevIteration: Option[AbstractSkimmerInternal],
    currIteration: Option[AbstractSkimmerInternal]
  ): AbstractSkimmerInternal = {
    val prevSkim = prevIteration
      .map(_.asInstanceOf[EmissionsSkimmerInternal])
      .getOrElse(EmissionsSkimmerInternal(init(), 0, 0, 0))
    val currSkim = currIteration
      .map(_.asInstanceOf[EmissionsSkimmerInternal])
      .getOrElse(
        EmissionsSkimmerInternal(init(), 0, 0, 0, iterations = matsimServices.getIterationNumber + 1)
      )
    EmissionsSkimmerInternal(
      emissions =
        Emissions.weightedAverage(prevSkim.emissions, prevSkim.iterations, currSkim.emissions, currSkim.iterations),
      travelTime =
        (prevSkim.travelTime * prevSkim.iterations + currSkim.travelTime * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
      parkingDuration =
        (prevSkim.parkingDuration * prevSkim.iterations + currSkim.parkingDuration * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
      observations =
        (prevSkim.observations * prevSkim.iterations + currSkim.observations * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
      iterations = prevSkim.iterations + currSkim.iterations
    )
  }

  override protected def aggregateWithinIteration(
    prevObservation: Option[AbstractSkimmerInternal],
    currObservation: AbstractSkimmerInternal
  ): AbstractSkimmerInternal = {
    val prevSkim = prevObservation
      .map(_.asInstanceOf[EmissionsSkimmerInternal])
      .getOrElse(EmissionsSkimmerInternal(init(), 0, 0, 0, iterations = matsimServices.getIterationNumber + 1))
    val currSkim = currObservation.asInstanceOf[EmissionsSkimmerInternal]
    if (prevSkim.emissions == null || currSkim.emissions == null) {
      val message =
        s"Null emissions passed into EmissionsSkimmer.aggregateWithinIteration: prevEmissions=${prevSkim.emissions}, " +
        s"prevObservations=${prevSkim.observations}, prevIterations=${prevSkim.iterations}, " +
        s"currEmissions=${currSkim.emissions}, currObservations=${currSkim.observations}, currIterations=${currSkim.iterations}, " +
        s"currTravelTime=${currSkim.travelTime}, currParkingDuration=${currSkim.parkingDuration}"
      logger.error(message)
      throw new IllegalStateException(message)
    }
    EmissionsSkimmerInternal(
      emissions =
        Emissions.weightedAverage(prevSkim.emissions, prevSkim.observations, currSkim.emissions, currSkim.observations),
      travelTime =
        (prevSkim.travelTime * prevSkim.observations + currSkim.travelTime * currSkim.observations) / (prevSkim.observations + currSkim.observations),
      parkingDuration =
        (prevSkim.parkingDuration * prevSkim.observations + currSkim.parkingDuration * currSkim.observations) / (prevSkim.observations + currSkim.observations),
      observations = prevSkim.observations + currSkim.observations,
      iterations = prevSkim.iterations
    )
  }
}

object EmissionsSkimmer extends LazyLogging {
  private val vehicleTypeIdPool = new ConcurrentHashMap[String, String]()
  private val vehicleTypeIdToCode = new ConcurrentHashMap[String, Integer]()
  private val codeToVehicleTypeId = new ConcurrentHashMap[Integer, String]()
  private val nextVehicleTypeCode = new java.util.concurrent.atomic.AtomicInteger(0)

  private val processesById: Array[EmissionsProfile.EmissionsProcess] =
    EmissionsProfile.values.toSeq.sortBy(_.id).toArray
  private val processNamesById: Array[String] = processesById.map(_.toString)
  private val linkIdMask = 0xffffffffL
  private val vehicleTypeCodeMask = 0xfffffL
  private val hourMask = 0x1fL
  private val processMask = 0x7fL

  def canonicalVehicleTypeId(vehicleTypeId: String): String =
    vehicleTypeIdPool.computeIfAbsent(
      vehicleTypeId,
      new java.util.function.Function[String, String] {
        override def apply(value: String): String = value
      }
    )

  private def vehicleTypeCode(vehicleTypeId: String): Int = {
    val canonical = canonicalVehicleTypeId(vehicleTypeId)
    vehicleTypeIdToCode.computeIfAbsent(
      canonical,
      new java.util.function.Function[String, Integer] {
        override def apply(value: String): Integer = {
          val code = nextVehicleTypeCode.getAndIncrement()
          codeToVehicleTypeId.put(code, value)
          code
        }
      }
    )
  }

  def packKey(
    linkId: Int,
    vehicleTypeId: String,
    hour: Int,
    emissionsProcess: EmissionsProfile.EmissionsProcess
  ): Long = {
    val code = vehicleTypeCode(vehicleTypeId)
    (linkId.toLong & linkIdMask) |
    ((code.toLong & vehicleTypeCodeMask) << 32) |
    ((hour.toLong & hourMask) << 52) |
    ((emissionsProcess.id.toLong & processMask) << 57)
  }

  def unpackLinkId(packedKey: Long): Int = (packedKey & linkIdMask).toInt

  def unpackVehicleTypeId(packedKey: Long): String = {
    val code = ((packedKey >>> 32) & vehicleTypeCodeMask).toInt
    codeToVehicleTypeId.get(code)
  }

  def unpackHour(packedKey: Long): Int = ((packedKey >>> 52) & hourMask).toInt

  def unpackProcess(packedKey: Long): EmissionsProfile.EmissionsProcess = {
    val processId = ((packedKey >>> 57) & processMask).toInt
    processesById(processId)
  }

  def unpackProcessName(packedKey: Long): String = {
    val processId = ((packedKey >>> 57) & processMask).toInt
    processNamesById(processId)
  }

  case class EmissionsSkimmerKey(
    linkId: Int,
    vehicleTypeId: String,
    hour: Int,
    emissionsProcess: EmissionsProfile.EmissionsProcess
  ) extends AbstractSkimmerKey {
    private lazy val cachedProcessName: String = emissionsProcess.toString
    def processName: String = cachedProcessName

    override def toCsv: String = s"$hour,$linkId,$vehicleTypeId,$cachedProcessName"
  }

  case class EmissionsSkimmerInternal(
    emissions: Emissions,
    travelTime: Double,
    parkingDuration: Double,
    observations: Int = 0,
    iterations: Int = 0
  ) extends AbstractSkimmerInternal {
    private lazy val pollutants: String = emissions.toPollutantsString
    def pollutantsString: String = pollutants

    override def toCsv: String = s"$pollutants,$travelTime,$parkingDuration,$observations,$iterations"
  }

  def emissionsSkimOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("EmissionsSkimmer", "skimsEmissions.csv.gz", iterationLevel = true)(
      """
      hour          | Hour of the day
      linkId        | Link ID
      vehicleType   | Type of vehicle
      emissionsProcess | Emissions process (RUNEX, IDLEX, STREX, DIURN, HOTSOAK, RUNLOSS, PMTW, PMBW, PRDUST)
      emissions     | String representation of non-zero emissions in format 'pollutant1:value1;pollutant2:value2'
      travelTimeInSecond  | Average travel time in second
      parkingDuration | Parking duration in seconds
      observations  | Number of events
      iterations    | The current iteration number
      """
    )
}
