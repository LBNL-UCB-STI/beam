package beam.utils

import java.io.Closeable

import io.circe.Printer
import io.circe.parser._
import org.apache.avro.generic.GenericRecord
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.avro.AvroParquetReader
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.parquet.hadoop.{ParquetFileReader, ParquetReader => HadoopParquetReader}

object ParquetReader {

  def main(args: Array[String]): Unit = {
    assert(args.nonEmpty, "Expected path to the file, but got nothig")

    val parquetPath = new Path(args.head)
    println(s"Path to parquet file: ${parquetPath}")

    showMetadata(parquetPath)

    // Read some rows
    val (iter, toClose) = read(args.head)
    try {
      iter.take(20).zipWithIndex.foreach {
        case (row, idx) =>
          println(s"$idx: $row")
      }
    } finally {
      toClose.close()
    }
  }

  def showMetadata(parquetPath: Path): Unit = {
    val inputFile = HadoopInputFile.fromPath(parquetPath, new Configuration)
    FileUtils.using(ParquetFileReader.open(inputFile)) { rdr =>
      println(s"Number of rows: ${rdr.getRecordCount}")
      val metaData = rdr.getFooter.getFileMetaData
      // Get parquet schema
      println("Parquet schema (Thrift definitions): ")
      println("#############################################################")
      print(metaData.getSchema)
      println("#############################################################")
      println

      println("Metadata: ")
      println("#############################################################")
      val map = metaData.getKeyValueMetaData
      val pandasKey = "pandas"
      Option(map.get(pandasKey)) match {
        case Some(json) =>
          val js = parse(json).map(_.printWith(Printer.spaces2))
          println(s"$pandasKey => $js")
        case None =>
          println(map)
      }
      println("#############################################################")
      println
    }
  }

  def read(filePath: String): (Iterator[GenericRecord], Closeable) = {
    val inputFile = HadoopInputFile.fromPath(new Path(filePath), new Configuration)
    val reader: HadoopParquetReader[GenericRecord] = AvroParquetReader.builder[GenericRecord](inputFile).build()
    val iter = Iterator.continually(reader.read).takeWhile(_ != null)
    (iter, reader)
  }
}
