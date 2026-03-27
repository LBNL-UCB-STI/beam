package beam.router.skim.core

import beam.agentsim.agents.vehicles.VehicleEmissions.Emissions._
import beam.agentsim.agents.vehicles.VehicleEmissions.{Emissions, EmissionsProfile}
import beam.router.skim.{readonly, Skims}
import beam.sim.config.BeamConfig
import beam.utils.{OutputDataDescriptor, OutputDataDescriptorObject}
import beam.utils.csv.writers.EmissionsSkimTotalsWriter
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
import org.matsim.core.controler.MatsimServices

import scala.util.{Failure, Success, Try}

class EmissionsSkimmer @Inject() (matsimServices: MatsimServices, beamConfig: BeamConfig)
    extends AbstractSkimmer(beamConfig, matsimServices.getControlerIO) {
  import EmissionsSkimmer._
  private val config: BeamConfig.Beam.Router.Skim = beamConfig.beam.router.skim
  private val totalsWriter = new EmissionsSkimTotalsWriter

  override lazy val readOnlySkim: AbstractSkimmerReadOnly = new readonly.EmissionsSkims()

  override protected val skimName: String = config.emissions_skimmer.name
  override protected val skimType: Skims.SkimType.Value = Skims.SkimType.EMISSIONS_SKIMMER
  override protected val skimFileBaseName: String = config.emissions_skimmer.fileBaseName
  override protected val skimOutputFormat: String = config.emissions_skimmer.fileOutputFormat

  override protected val skimFileHeader: String = {
    s"hour,linkId,vehicleTypeId,process,emissions,travelTimeInSecond,parkingDurationInSecond,observations,iterations"
  }

  override def writeToDisk(event: org.matsim.core.controler.events.IterationEndsEvent): Unit = {
    super.writeToDisk(event)

    if (config.writeSkimsInterval > 0 && event.getIteration % config.writeSkimsInterval == 0) {
      val sampleFraction = beamConfig.beam.agentsim.agentSampleSizeAsFractionOfPopulation
      val expansionFactor = 1.0 / sampleFraction
      val pollutantOrder = EmissionsSkimTotalsWriter
        .pollutantOrderFromFilter(beamConfig.beam.agentsim.agents.vehicles.emissions.pollutantsFilter)
      val filePath =
        matsimServices.getControlerIO.getIterationFilename(event.getIteration, EmissionsSkimTotalsWriter.fileName)
      totalsWriter.write(currentSkim, filePath, expansionFactor, pollutantOrder)
    }
  }

  private def writeSkimsAsParquet(
    skim: collection.Map[AbstractSkimmerKey, AbstractSkimmerInternal],
    filePath: String
  ): Unit = {
    logger.info(s"Writing ${skim.size} emissions skim records to Parquet file: $filePath")

    if (skim.isEmpty) {
      logger.warn("Attempting to write empty emissions skim map to Parquet file")
      return
    }

    // Filter and cast to concrete types
    val emissionsSkim = skim.flatMap { case (key, value) =>
      (key, value) match {
        case (k: EmissionsSkimmerKey, v: EmissionsSkimmerInternal) => Some((k, v))
        case (k, v) =>
          logger.warn(s"Skipping incompatible key-value types: ${k.getClass} -> ${v.getClass}")
          None
      }
    }

    if (emissionsSkim.isEmpty) {
      logger.error("No valid EmissionsSkimmerKey -> EmissionsSkimmerInternal pairs found")
      return
    }

    Try {
      val schema = createEmissionsAvroSchema()
      val conf = new Configuration()

      val hadoopPath = new Path(filePath)
      val fs = hadoopPath.getFileSystem(conf)
      val hadoopFile = HadoopOutputFile.fromPath(hadoopPath, conf)

      // Create parent directories
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
        emissionsSkim.foreach { case (key, value) =>
          val record = createEmissionsAvroRecord(schema, key, value)
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

  // The createEmissionsAvroRecord method now takes concrete types
  private def createEmissionsAvroRecord(
    schema: Schema,
    key: EmissionsSkimmerKey,
    value: EmissionsSkimmerInternal
  ): GenericRecord = {
    val record = new GenericData.Record(schema)

    // Set key fields
    record.put("hour", key.hour)
    record.put("linkId", key.linkId)
    record.put("vehicleTypeId", key.vehicleTypeId)
    record.put("process", key.emissionsProcess.toString)

    // Convert emissions to pollutants string
    val pollutantsString = convertEmissionsToPollutantsString(value.emissions)
    record.put("emissions", pollutantsString)

    // Set value fields
    record.put("travelTimeInSecond", value.travelTime)
    record.put("parkingDurationInSecond", value.parkingDuration)
    record.put("observations", value.observations)
    record.put("iterations", value.iterations)

    record
  }

  /**
    * Converts Emissions object to pollutants string format
    */
  private def convertEmissionsToPollutantsString(emissions: Emissions): String = {
    Emissions.values.toList
      .flatMap { emType =>
        val value = emissions.get(emType).getOrElse(0.0)
        if (value > 0) Some(s"${emType.toString}:$value")
        else None
      }
      .mkString(";")
  }

  override def writeSkim(skim: collection.Map[AbstractSkimmerKey, AbstractSkimmerInternal], filePath: String): Unit = {
    filePath.toLowerCase match {
      case path if path.endsWith(".parquet") =>
        writeSkimsAsParquet(skim, filePath)

      case path if path.endsWith(".csv.gz") || path.endsWith(".csv.gzip") || path.endsWith(".csv") =>
        super.writeSkim(skim, filePath)

      case _ =>
        val error = s"Unsupported file format for writing skims: $filePath"
        println(error)
        throw new IllegalArgumentException(error)
    }
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
              Some(Emissions.withName(parts(0)) -> parts(1).toDouble)
            } catch {
              case _: Exception => None
            }
          } else None
        }
        .collect { case Some(kv) => kv }
        .toMap
    } else Map.empty[EmissionType, Double]

    new Emissions(emissionsMap)
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

      // Convert string back to EmissionsProfile enum with fallback
      val emissionsProfile = EmissionsProfile.fromString(emissionsProcessStr).getOrElse {
        logger.warn(s"Unknown emissions process: '$emissionsProcessStr', using RUNEX as default")
        EmissionsProfile.RUNEX
      }

      // Create the key
      val key = EmissionsSkimmerKey(linkId, vehicleTypeId, hour, emissionsProfile)

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
            Some(Emissions.withName(parts(0)) -> parts(1).toDouble)
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
        line("vehicleTypeId"),
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
        (prevSkim.emissions * prevSkim.iterations + currSkim.emissions * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
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
    EmissionsSkimmerInternal(
      emissions =
        (prevSkim.emissions * prevSkim.observations + currSkim.emissions * currSkim.observations) / (prevSkim.observations + currSkim.observations),
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

  case class EmissionsSkimmerKey(
    linkId: Int,
    vehicleTypeId: String,
    hour: Int,
    emissionsProcess: EmissionsProfile.EmissionsProcess
  ) extends AbstractSkimmerKey {
    override def toCsv: String = s"$hour,$linkId,$vehicleTypeId,${emissionsProcess.toString}"
  }

  case class EmissionsSkimmerInternal(
    emissions: Emissions,
    travelTime: Double,
    parkingDuration: Double,
    observations: Int = 0,
    iterations: Int = 0
  ) extends AbstractSkimmerInternal {
    // Replace this line:
    // private val pollutants: String = Emissions.values.toList.map(emissions.get(_).getOrElse(0.0).toString).mkString(",")

    // With this implementation:
    private val pollutants: String = Emissions.values.toList
      .flatMap(emType => {
        val value = emissions.get(emType).getOrElse(0.0)
        if (value > 0) Some(s"${emType.toString}:$value")
        else None
      })
      .mkString(";")

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

  def aggregatedEmissionsSkimOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("EmissionsSkimmer", "skimsEmissions_Aggregated.csv.gz", iterationLevel = true)(
      """
      hour          | Hour of the day
      linkId        | Link ID
      vehicleType   | Type of vehicle
      emissionsProcess | Emissions process (RUNEX, IDLEX, STREX, DIURN, HOTSOAK, RUNLOSS, PMTW, PMBW, PRDUST)
      emissions     | Average (over last n iterations) non-zero emissions in format 'pollutant1:value1;pollutant2:value2'
      travelTimeInSecond  | Average (over last n iterations) travel time
      parkingDuration | Parking (over last n iterations) duration
      observations  | Average (over last n iterations) number of events
      iterations    | Number of iterations
      """
    )
}
