package beam.utils.scenario.urbansim.censusblock.reader

import beam.utils.FileUtils
import beam.utils.scenario.urbansim.censusblock.EntityTransformer

import java.io.BufferedReader
import com.univocity.parsers.csv.CsvParser
import com.univocity.parsers.csv.CsvParserSettings

import scala.collection.JavaConverters._

abstract class BaseCsvReader[T](path: String) extends Reader[T] {
  val transformer: EntityTransformer[T]

  private val csvParserSettings = new CsvParserSettings
  csvParserSettings.setHeaderExtractionEnabled(true)
  private val parser = new CsvParser(csvParserSettings)
  private val reader: BufferedReader = FileUtils.readerFromFile(path)

  override def iterator(): Iterator[T] = {
    parser.beginParsing(reader)
    parser.iterateRecords(reader)
      .iterator.asScala
      .map(transformer.transform)
  }

  override def close(): Unit = {
    parser.stopParsing()
    reader.close()
  }
}
