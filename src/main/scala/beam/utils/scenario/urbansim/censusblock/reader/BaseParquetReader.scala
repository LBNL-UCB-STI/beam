package beam.utils.scenario.urbansim.censusblock.reader

import org.apache.avro.generic.GenericRecord
import org.apache.parquet.hadoop.ParquetReader
import org.apache.hadoop.conf.Configuration
import org.apache.parquet.avro.AvroParquetReader
import org.apache.hadoop.fs.Path
import org.apache.parquet.io.InputFile
import org.apache.parquet.hadoop.util.HadoopInputFile

abstract class BaseParquetReader[T](path: String) extends Reader[T] {
  private val conf = new Configuration()

  private val parquetReader: ParquetReader[GenericRecord] = {
    val inputFile: InputFile = HadoopInputFile.fromPath(new Path(path), conf)
    AvroParquetReader
      .builder[GenericRecord](inputFile)
      .withConf(conf)
      .build()
  }

  override def iterator(): Iterator[T] = {
    new Iterator[T] {
      private var current: GenericRecord = parquetReader.read()

      override def hasNext: Boolean = current != null

      override def next(): T = {
        val record = current
        current = parquetReader.read()
        transform(record)
      }
    }
  }

  override def close(): Unit = {
    parquetReader.close()
  }

  protected def transform(record: GenericRecord): T
}
