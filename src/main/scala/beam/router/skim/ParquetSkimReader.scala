package beam.router.skim

import beam.router.skim.core.{AbstractSkimmerInternal, AbstractSkimmerKey}
import beam.utils.ProducerConsumer
import com.typesafe.scalalogging.Logger
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.column.page.PageReadStore
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.parquet.io.api.{Binary, GroupConverter, PrimitiveConverter, RecordMaterializer}
import org.apache.parquet.io.{ColumnIOFactory, MessageColumnIO}
import org.apache.parquet.schema.MessageType

import java.io.{BufferedReader, File}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Using

class ParquetSkimReader[Key <: AbstractSkimmerKey, Value <: AbstractSkimmerInternal](
  val aggregatedSkimsFilePath: String,
  fromParquetRow: Array[Any] => (Key, Value),
  val logger: Logger
) extends SkimReader[Key, Value] {

  // Validate file extension
  require(
    aggregatedSkimsFilePath.toLowerCase.endsWith(".parquet"),
    s"Invalid file extension for ParquetSkimsReader: $aggregatedSkimsFilePath. Only .parquet files are supported."
  )

  def readAggregatedSkims: Map[Key, Value] = {
    if (!new File(aggregatedSkimsFilePath).isFile) {
      logger.info(s"Parquet skim NO PATH FOUND '$aggregatedSkimsFilePath'")
      Map.empty
    } else {
      readParquetFileParallel(aggregatedSkimsFilePath)
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

  private def readParquetFileParallel(filePath: String): Map[Key, Value] = {
    val conf = new Configuration()
    val path = new Path(filePath)
    val inputFile = HadoopInputFile.fromPath(path, conf)

    val tryResult = Using(ParquetFileReader.open(inputFile)) { reader =>
      val schema = reader.getFooter.getFileMetaData.getSchema
      val totalRowGroups = reader.getRowGroups.size() // blocks to read total
      val columnIOFactory = new ColumnIOFactory()

      val totalRecords = reader.getRecordCount
      logger.info(s"Going to read $filePath, est $totalRecords records, $totalRowGroups row groups.")

      val readerBlockIterator = (0 until totalRowGroups).toIterator
      def readNextRaw(): Option[Int] = {
        if (readerBlockIterator.hasNext) Some(readerBlockIterator.next())
        else None
      }

      val trieMap = new scala.collection.concurrent.TrieMap[Key, Value]()

      class GenericArrayConverter(schema: MessageType) extends GroupConverter {
        // The internal "row" state
        val currentRow = new Array[Any](schema.getFieldCount)

        private val converters = Array.tabulate(schema.getFieldCount) { i =>
          new PrimitiveConverter {
            override def addLong(value: Long): Unit = currentRow(i) = value
            override def addInt(value: Int): Unit = currentRow(i) = value
            override def addDouble(value: Double): Unit = currentRow(i) = value
            override def addBoolean(value: Boolean): Unit = currentRow(i) = value
            override def addBinary(value: Binary): Unit = currentRow(i) = value.toStringUsingUTF8
          }
        }

        override def getConverter(fieldIndex: Int): PrimitiveConverter = converters(fieldIndex)
        // Optional: Reset array to nulls if columns might be missing/optional
        override def start(): Unit = java.util.Arrays.fill(currentRow.asInstanceOf[Array[Object]], null)
        override def end(): Unit = {}
      }

      class GenericArrayMaterializer(schema: MessageType) extends RecordMaterializer[Array[Any]] {
        private val converter = new GenericArrayConverter(schema)
        override def getRootConverter: GroupConverter = converter
        override def getCurrentRecord: Array[Any] = converter.currentRow
      }

      def rowGroupToRecords(pages: PageReadStore): Long = {
        if (pages == null) 0L
        else {
          val columnIO: MessageColumnIO = columnIOFactory.getColumnIO(schema)
          val materializer = new GenericArrayMaterializer(schema)
          val recordReader = columnIO.getRecordReader(pages, materializer)
          val rowsInGroup = pages.getRowCount

          for (_ <- 0L until rowsInGroup) {
            recordReader.read() // Fills materializer.currentRow

            val arr: Array[Any] = materializer.getCurrentRecord
            val (k, v) = fromParquetRow(arr)
            trieMap.put(k, v)
          }
          rowsInGroup
        }
      }

      def transformRawToResult(rowGroupIdx: Int): Unit = {
        val inputFile = HadoopInputFile.fromPath(path, conf)
        val localReader = ParquetFileReader.open(inputFile)
        try {
          // Skip to the assigned row group
          for (_ <- 0 until rowGroupIdx) localReader.skipNextRowGroup()
          val prs: PageReadStore = localReader.readNextRowGroup()
          val rowsCompleted = rowGroupToRecords(prs)
          logger.info(s"Read $rowsCompleted from single row group.")
        } finally {
          localReader.close()
        }
      }

      val parallelMapReader = new ProducerConsumer[Int](
        produce = readNextRaw,
        consume = transformRawToResult,
        log = st => logger.debug(st),
        err = st => logger.error(st),
        numberOfParallelTransformers = 4
      )

      parallelMapReader.waitForTransformationToComplete()
      trieMap.toMap
    }

    tryResult.get
  }

  def close(): Unit = {}
}
