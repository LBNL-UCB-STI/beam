package beam.router.skim.core

import beam.agentsim.agents.vehicles.VehicleEmissions.Emissions._
import beam.agentsim.agents.vehicles.VehicleEmissions.{Emissions, EmissionsProfile}
import beam.router.skim.{readonly, ParquetSkimWriter, Skims}
import beam.sim.config.BeamConfig
import beam.utils.{OutputDataDescriptor, OutputDataDescriptorObject}
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericRecord}
import org.matsim.core.controler.MatsimServices

class EmissionsSkimmer @Inject() (matsimServices: MatsimServices, beamConfig: BeamConfig)
    extends AbstractSkimmer(beamConfig, matsimServices.getControlerIO) {

  import EmissionsSkimmer._

  private val config: BeamConfig.Beam.Router.Skim = beamConfig.beam.router.skim

  override lazy val readOnlySkim: AbstractSkimmerReadOnly = new readonly.EmissionsSkims()

  override protected val skimName: String = config.emissions_skimmer.name
  override protected val skimType: Skims.SkimType.Value = Skims.SkimType.EMISSIONS_SKIMMER
  override protected val skimFileBaseName: String = config.emissions_skimmer.fileBaseName
  override protected val skimOutputFormat: String = config.emissions_skimmer.fileOutputFormat

  private val parquetChunkSize: Int = config.emissions_skimmer.parquetWritingChunkSize
  private val parquetParallelism: Int = config.emissions_skimmer.parquetWritingMaxParallelism

  override protected val skimFileHeader: String = {
    s"hour,linkId,vehicleTypeId,process,emissions,travelTimeInSecond,parkingDurationInSecond,observations,iterations"
  }

  private def writeSkimsAsParquet(
    skim: collection.Map[AbstractSkimmerKey, AbstractSkimmerInternal],
    filePath: String
  ): Unit = {
    if (skim.isEmpty) {
      logger.warn("There are no emissions skim to write to Parquet file, step skipped.")
    } else {
      val parallelism = Math.min(Runtime.getRuntime.availableProcessors(), parquetParallelism)
      logger.info(
        s"Writing ${skim.size} emissions skim records (chunkSize $parquetChunkSize, parallelism $parallelism) to Parquet file: $filePath"
      )
      val parquetSkimWriter = new ParquetSkimWriter[EmissionsSkimmerKey, EmissionsSkimmerInternal](
        emissionsAvroSchema,
        logger,
        createEmissionsAvroRecord,
        chunkSize = parquetChunkSize,
        parallelism = parallelism
      )
      parquetSkimWriter.writeSkims(skim, filePath)
    }
  }

  private lazy val emissionsAvroSchema: Schema = {
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

    // Set value fields
    record.put("emissions", EmissionsSkimmerInternal.emissionsToString(value.emissions))
    record.put("travelTimeInSecond", value.travelTime)
    record.put("parkingDurationInSecond", value.parkingDuration)
    record.put("observations", value.observations)
    record.put("iterations", value.iterations)

    record
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

  override def fromParquetRow(rawRecord: Array[Any]): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    try {
      // Create the key
      // using the order and types of fields in schema!
      //    {"name": "hour", "type": "int"},
      //    {"name": "linkId", "type": "int"},
      //    {"name": "vehicleTypeId", "type": "string"},
      //    {"name": "process", "type": "string"},

      val key = EmissionsSkimmerKey(
        hour = rawRecord(0).asInstanceOf[Int],
        linkId = rawRecord(1).asInstanceOf[Int],
        vehicleTypeId = rawRecord(2).asInstanceOf[String],
        emissionsProcess = EmissionsProfile.withName(rawRecord(3).asInstanceOf[String])
      )

      // Create the value
      // using the order and types of fields in schema!
      //    {"name": "emissions", "type": "string"},
      //    {"name": "travelTimeInSecond", "type": "double"},
      //    {"name": "parkingDurationInSecond", "type": "double"},
      //    {"name": "observations", "type": "int"},
      //    {"name": "iterations", "type": "int"}

      val value = EmissionsSkimmerInternal(
        emissions = EmissionsSkimmerInternal.emissionsFromString(rawRecord(4).asInstanceOf[String]),
        travelTime = rawRecord(5).asInstanceOf[Double],
        parkingDuration = rawRecord(6).asInstanceOf[Double],
        observations = rawRecord(7).asInstanceOf[Int],
        iterations = rawRecord(8).asInstanceOf[Int]
      )

      (key, value)

    } catch {
      case ex: Exception =>
        logger.error(s"Failed to parse Parquet row: ${rawRecord.mkString(",")}", ex)
        throw new RuntimeException(s"Failed to parse Parquet row for EmissionsSkimmer", ex)
    }
  }

  override def fromCsv(
    line: scala.collection.Map[String, String]
  ): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    (
      EmissionsSkimmerKey(
        line("linkId").toInt,
        line("vehicleTypeId"),
        line("hour").toInt,
        EmissionsProfile.withName(line("process"))
      ),
      EmissionsSkimmerInternal(
        EmissionsSkimmerInternal.emissionsFromString(line.getOrElse("emissions", "")),
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
    private lazy val pollutants: String = EmissionsSkimmerInternal.emissionsToString(emissions)
    override def toCsv: String = s"$pollutants,$travelTime,$parkingDuration,$observations,$iterations"
  }

  object EmissionsSkimmerInternal {

    def emissionsToString(emissions: Emissions): String = {
      val sb = new StringBuilder()

      Emissions.values.foreach { emType =>
        val value = emissions.values.getOrElse(emType, 0.0)
        if (value > 0) {
          if (sb.nonEmpty) sb.append(";")
          sb.append(emType.toString).append(":").append(value)
        }
      }

      sb.toString()
    }

    def emissionsFromString(pollutantsString: String): Emissions = {
      if (pollutantsString == null || pollutantsString.isEmpty) Emissions()
      else
        Emissions(
          pollutantsString
            .split(";")
            .iterator
            .filter(_.contains(":"))
            .flatMap { entry =>
              try {
                val Array(k, v) = entry.split(":", 2)
                Some(Emissions.withName(k) -> v.toDouble)
              } catch {
                case _: Exception => None
              }
            }
            .toMap
        )
    }
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
