package beam.router.skim

import beam.router.skim.core.{AbstractSkimmerInternal, AbstractSkimmerKey}
import beam.utils.ProducerConsumer
import com.typesafe.scalalogging.Logger
import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.parquet.avro.AvroParquetWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.hadoop.util.{HadoopInputFile, HadoopOutputFile, HadoopStreams}
import org.apache.parquet.hadoop.{ParquetFileReader, ParquetFileWriter}

import scala.concurrent.ExecutionContext.Implicits.global

import java.io.File
import java.nio.file.{Files, Paths}
import java.util.UUID
import java.util.concurrent.{ConcurrentLinkedQueue, Executors, Semaphore}
import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.reflect.io.Directory

class ParquetSkimWriter[Key <: AbstractSkimmerKey: ClassTag, Value <: AbstractSkimmerInternal: ClassTag](
  val schema: Schema,
  val logger: Logger,
  val recordConstructor: (Schema, Key, Value) => GenericRecord,
  val chunkSize: Int,
  val parallelism: Int
) {

  def writeSkims(
    skims: collection.Iterable[(AbstractSkimmerKey, AbstractSkimmerInternal)],
    outputFilePath: String
  ): Unit = {
    if (skims.size > chunkSize)
      writeSkimsParallelAndMergeIntoSingleFile(skims, outputFilePath)
    else
      writeSkimsSingleThread(skims, outputFilePath)
  }

  private def writeSkimsSingleThread(
    skim: collection.Iterable[(AbstractSkimmerKey, AbstractSkimmerInternal)],
    outputFilePath: String
  ): Unit = {
    val validSkims: Iterable[(Key, Value)] = skim.iterator.collect { case (k: Key, v: Value) => (k, v) }.toIterable

    val path = new Path(outputFilePath)
    val recordCount = writeSkimsToParquetInternal(path, validSkims, skim.size / 7)

    logger.info(s"Successfully wrote $recordCount records to $outputFilePath")
  }

  private def writeSkimsStreaming(
    skim: collection.Iterable[(AbstractSkimmerKey, AbstractSkimmerInternal)],
    chunksPath: String
  ): Array[Int] = {
    val results = new ConcurrentLinkedQueue[Int]()

    // lazy iterator over the original map – no extra collection
    val chunks: Iterator[(Seq[(Key, Value)], Int)] = skim.iterator
      .collect { case (k: Key, v: Value) => (k, v) }
      .grouped(chunkSize)
      .zipWithIndex

    def produceChunk(): Option[(Seq[(Key, Value)], Int)] = {
      if (chunks.hasNext) Some(chunks.next())
      else None
    }

    def writeChunk(chunkWithIndex: (Seq[(Key, Value)], Int)): Unit = {
      val (chunk, idx) = chunkWithIndex
      val filePath = new Path(chunksPath, s"chunk_$idx.parquet")
      val recordsWritten = writeSkimsToParquetInternal(filePath, chunk, chunkSize / 3)
      results.add(recordsWritten)
    }

    val pcWriter = new ProducerConsumer[(Seq[(Key, Value)], Int)](
      produce = produceChunk,
      consume = writeChunk,
      log = s => logger.info(s),
      numberOfParallelTransformers = parallelism,
      desiredInternalWorkQueueSize = parallelism
    )
    pcWriter.waitForTransformationToComplete(Duration.Inf)

    results.toArray.map(_.asInstanceOf[Int])
  }

  private def writeSkimsParallelAndMergeIntoSingleFile(
    skim: collection.Iterable[(AbstractSkimmerKey, AbstractSkimmerInternal)],
    outputFilePath: String
  ): Unit = {
    val runId = UUID.randomUUID().toString.take(8)
    val chunksPath = outputFilePath.replace(".parquet", s"_chunks_$runId")
    val path = Paths.get(chunksPath)
    if (!Files.exists(path)) { Files.createDirectories(path) }

    val results = writeSkimsStreaming(skim, chunksPath)

    logger.info(s"Successfully wrote ${results.sum} records to $chunksPath as ${results.length} chunks.")
    mergeParquetFiles(chunksPath, outputFilePath)
  }

  private def writeSkimsToParquetInternal(
    filePath: Path,
    skimsSeq: Iterable[(Key, Value)],
    logEachChunksMightBe0: Int
  ): Int = {
    val conf = new Configuration()
    val hadoopFile = HadoopOutputFile.fromPath(filePath, conf)
    val logEachChunks = Math.max(logEachChunksMightBe0, 3)

    val writer = AvroParquetWriter
      .builder[GenericRecord](hadoopFile)
      .withSchema(schema)
      .withConf(conf)
      .withCompressionCodec(CompressionCodecName.GZIP)
      .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
      .build()

    var recordCount = 0
    try {
      skimsSeq.foreach { case (key, value) =>
        val record = recordConstructor(schema, key, value)
        writer.write(record)

        recordCount += 1
        if (recordCount % logEachChunks == 0) {
          logger.info(s"Written $recordCount records to $filePath")
        }
      }
    } finally {
      writer.close()
    }
    recordCount
  }

  private def mergeParquetFiles(inputDir: String, outputFile: String): Unit = {
    val conf = new Configuration()
    val fs = FileSystem.get(conf)

    val inputFiles = fs
      .listStatus(new Path(inputDir))
      .filter(_.getPath.getName.endsWith(".parquet"))
      .sortBy(_.getPath.getName)
      .map(_.getPath)

    require(inputFiles.nonEmpty, s"No Parquet files in $inputDir")

    // 1. Extract schema from the first file
    val firstReader = ParquetFileReader.open(HadoopInputFile.fromPath(inputFiles.head, conf))
    val firstFileMetaData = firstReader.getFooter.getFileMetaData
    val schema = firstFileMetaData.getSchema
    val footMetaData = firstFileMetaData.getKeyValueMetaData
    firstReader.close()

    val writer = new ParquetFileWriter(
      HadoopOutputFile.fromPath(new Path(outputFile), conf),
      schema,
      ParquetFileWriter.Mode.OVERWRITE,
      256L * 1024 * 1024, // rowGroupSize in bytes (Long)
      8 * 1024, // maxPaddingSize
      Int.MaxValue, // columnIndexTruncateLength
      Int.MaxValue, // statisticsTruncateLength
      true // pageWriteChecksumEnabled
    )

    try {
      writer.start()
      logger.info(s"Merge of ${inputFiles.length} chunks to $outputFile started.")

      // 3. Append row groups from each chunk as raw bytes
      inputFiles.foreach { file =>
        val fReader = ParquetFileReader.open(HadoopInputFile.fromPath(file, conf))
        val rowGroups = fReader.getRowGroups
        fReader.close()

        // Open the file for raw reading
        val inputStream = fs.open(file) // FSDataInputStream extends SeekableInputStream
        try {
          rowGroups.asScala.foreach { blockMeta =>
            val offset = blockMeta.getColumns.asScala.map(_.getStartingPos).min
            inputStream.seek(offset)
            val hadoopStream = HadoopStreams.wrap(inputStream)
            writer.appendRowGroup(hadoopStream, blockMeta, false)
          }
        } finally {
          inputStream.close()
        }
      }
    } finally {
      writer.end(footMetaData)
    }

    val dir = new Directory(new File(inputDir))
    if (dir.exists) dir.deleteRecursively()
    logger.info(s"Merged ${inputFiles.length} chunks into $outputFile, chunks were deleted.")
  }

}
