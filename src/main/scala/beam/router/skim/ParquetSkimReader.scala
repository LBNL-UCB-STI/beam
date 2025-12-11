package beam.router.skim

import beam.router.skim.core.{AbstractSkimmerInternal, AbstractSkimmerKey}
import com.typesafe.scalalogging.Logger
import org.apache.spark.sql.SparkSession

import java.io.{BufferedReader, File}
import scala.util.Try

class ParquetSkimReader[Key <: AbstractSkimmerKey, Value <: AbstractSkimmerInternal](
  val aggregatedSkimsFilePath: String,
  fromParquetRow: org.apache.spark.sql.Row => (Key, Value),
  val logger: Logger,
  sparkSession: Option[SparkSession] = None
) extends SkimReader[Key, Value] {

  // Validate file extension
  require(
    aggregatedSkimsFilePath.toLowerCase.endsWith(".parquet"),
    s"Invalid file extension for ParquetSkimsReader: $aggregatedSkimsFilePath. Only .parquet files are supported."
  )

  private lazy val spark: SparkSession = sparkSession.getOrElse(
    SparkSession
      .builder()
      .appName("ParquetSkimsReader")
      .config("spark.sql.parquet.binaryAsString", "true")
      .getOrCreate()
  )

  // Track whether we created the Spark session internally
  private val internallyCreatedSpark: Boolean = sparkSession.isEmpty

  def readAggregatedSkims: Map[Key, Value] = {
    if (!new File(aggregatedSkimsFilePath).isFile) {
      logger.info(s"Parquet skim NO PATH FOUND '$aggregatedSkimsFilePath'")
      Map.empty
    } else {
      readParquetFile(aggregatedSkimsFilePath).recover { case ex: Throwable =>
        logger.warn(s"Could not load Parquet skim from '$aggregatedSkimsFilePath'", ex)
        Map.empty[Key, Value]
      }.get
    }
  }

  def readSkims(reader: BufferedReader): Map[Key, Value] = {
    logger.error(
      """ParquetSkimReader cannot read from BufferedReader because:
        |1. Parquet is a binary format requiring random access
        |2. BufferedReader is designed for text data with sequential reading
        |3. Parquet files have internal structure and metadata at the end
        |
        |Please use readAggregatedSkims() with a file path instead.
        |If you have Parquet data in a stream, consider writing it to a temporary file first.
        |""".stripMargin
    )
    Map.empty[Key, Value]
  }

  private def readParquetFile(filePath: String): Try[Map[Key, Value]] = Try {
    val df = spark.read.parquet(filePath)
    df.collect().map(fromParquetRow).toMap
  }

  def close(): Unit = {
    if (internallyCreatedSpark && !spark.sparkContext.isStopped) {
      try {
        spark.close()
        logger.debug("ParquetSkimsReader closed successfully (Spark session terminated)")
      } catch {
        case ex: Exception =>
          logger.warn("Error closing Spark session in ParquetSkimsReader", ex)
      }
    } else {
      logger.debug("ParquetSkimsReader closed (using external Spark session)")
    }
  }
}
