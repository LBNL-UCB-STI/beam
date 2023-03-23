package beam.utils

import com.univocity.parsers.common.ParsingContext
import com.univocity.parsers.common.processor.RowProcessor
import com.univocity.parsers.csv.CsvParser
import com.univocity.parsers.csv.CsvParserSettings

import scala.collection.mutable

trait LineProcessor[A] {
  var headers: Array[String]
  val lines: mutable.ListBuffer[A]
}

object CsvFileUtils {

  /*def readCsvFileByLineToMap[K, V](
    filePath: String
  )(lineConverter: java.util.Map[String, String] => (K, V)): scala.collection.Map[K, V] = {
    val fileReader = FileUtils.readerFromFile(filePath)
    val csvParserSettings = new CsvParserSettings
    csvParserSettings.setHeaderExtractionEnabled(true)
    new RowProcessor {
      var headers: Array[String] = Array.empty[String]
      override def processStarted(context: ParsingContext): Unit = headers = context.headers

      override def rowProcessed(row: Array[String], context: ParsingContext): Unit = {

      }

      override def processEnded(context: ParsingContext): Unit = ???
    }
    val rowProcessor = new RowListProcessor()
    val concurrentProcessor = new ConcurrentRowProcessor(rowProcessor)
    val parser = new CsvParser(csvParserSettings)
    parser.parse(fileReader)
    rowProcessor.getHeaders
    FileUtils
      .using(new CsvMapReader(fileReader, CsvPreference.STANDARD_PREFERENCE)) { mapReader =>
        val res = mutable.HashMap[K, V]()
        val header = rowProcessor.getHeaders
        val lines = rowProcessor.
        //val header = mapReader.getHeader(true)
        var line: java.util.Map[String, String] = mapReader.read(header: _*)
        while (null != line) {
          res += lineConverter(line)
          line = mapReader.read(header: _*)
        }
        res
      }
  }*/

  def readCsvFileByLineToList[A](
    filePath: String
  )(lineConverter: Map[String, String] => A): scala.collection.Seq[A] = {
    val fileReader = FileUtils.readerFromFile(filePath)


    val csvParserSettings = new CsvParserSettings
    csvParserSettings.setHeaderExtractionEnabled(true)
    val converterProcessor = new RowProcessor with LineProcessor[A] {
      var headers: Array[String] = Array.empty[String]
      val lines = mutable.ListBuffer.empty[A]
      override def processStarted(context: ParsingContext): Unit = headers = context.headers

      override def rowProcessed(row: Array[String], context: ParsingContext): Unit = {
        lines += lineConverter(headers.zip(row).toMap)
      }

      override def processEnded(context: ParsingContext): Unit = {}
    }
    //val concurrentProcessor = new ConcurrentRowProcessor(converterProcessor)
    csvParserSettings.setProcessor(converterProcessor)
    val parser = new CsvParser(csvParserSettings)
    parser.parse(fileReader)
    converterProcessor.lines


   /* FileUtils
      .using(new CsvMapReader(fileReader, CsvPreference.STANDARD_PREFERENCE)) { mapReader =>
        val res = mutable.ArrayBuffer[A]()
        val header = mapReader.getHeader(true)
        var line: java.util.Map[String, String] = mapReader.read(header: _*)
        while (null != line) {
          res += lineConverter(line)
          line = mapReader.read(header: _*)
        }
        res
      }*/
  }
}
