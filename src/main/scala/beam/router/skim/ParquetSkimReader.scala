package beam.router.skim

import beam.router.skim.core.{AbstractSkimmerInternal, AbstractSkimmerKey}
import com.typesafe.scalalogging.Logger
import org.apache.spark.sql.SparkSession

import java.io.{BufferedReader, File}
import scala.util.Try

class ParquetSkimReader[Key <: AbstractSkimmerKey, Value <: AbstractSkimmerInternal](
  val aggregatedSkimsFilePath: String,
  fromParquetRow: org.apache.spark.sql.Row => (Key, Value),
  val logger: Logger
) extends SkimReader[Key, Value] {

  // Validate file extension
  require(
    aggregatedSkimsFilePath.toLowerCase.endsWith(".parquet"),
    s"Invalid file extension for ParquetSkimsReader: $aggregatedSkimsFilePath. Only .parquet files are supported."
  )

  private def createSparkSession(): SparkSession = SparkSession
    .builder()
    .appName("SkimReader")
    .master("local[*]")
    .config("spark.driver.maxResultSize", "0")
    .config("spark.sql.parquet.binaryAsString", "true")
    .getOrCreate()

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
    val sparkSession = createSparkSession()
    try {
      val df = sparkSession.read.parquet(filePath)

      if (df.isEmpty) {
        logger.warn(s"Empty Parquet file: $filePath")
        return Try(Map.empty)
      }

      // Ensure reasonable partition sizes (200 partitions is default; adjust if needed)
      val targetPartitions = 200
      val repartitioned = if (df.rdd.getNumPartitions < targetPartitions) df.repartition(targetPartitions) else df

      val iter = repartitioned.toLocalIterator()
      val mapBuilder = Map.newBuilder[Key, Value]
      var records = 0
      while (iter.hasNext) {
        val (k, v) = fromParquetRow(iter.next())
        mapBuilder += (k -> v)
        records += 1
      }
      val result = mapBuilder.result()
      logger.info(s"Successfully read ${result.size} entries from $records records from $filePath")
      result
    } finally {
      sparkSession.close()
    }
  }

  def close(): Unit = {}
}
