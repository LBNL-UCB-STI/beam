package beam.utils

import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.mutable

object CsvFileUtils {

  def readCsvFileByLineToMap[K, V](
    filePath: String
  )(lineConverter: java.util.Map[String, String] => (K, V)): Map[K, V] = {
    val fileReader = FileUtils.readerFromFile(filePath)
    FileUtils
      .using(new CsvMapReader(fileReader, CsvPreference.STANDARD_PREFERENCE)) { mapReader =>
        val res = mutable.HashMap[K, V]()
        val header = mapReader.getHeader(true)
        var line: java.util.Map[String, String] = mapReader.read(header: _*)
        while (null != line) {
          res += lineConverter(line)
          line = mapReader.read(header: _*)
        }
        res
      }
      .toMap
  }

  def readCsvFileByLineToList[A](
    filePath: String
  )(lineConverter: java.util.Map[String, String] => A): List[A] = {
    val fileReader = FileUtils.readerFromFile(filePath)
    FileUtils
      .using(new CsvMapReader(fileReader, CsvPreference.STANDARD_PREFERENCE)) { mapReader =>
        val res = mutable.ArrayBuffer[A]()
        val header = mapReader.getHeader(true)
        var line: java.util.Map[String, String] = mapReader.read(header: _*)
        while (null != line) {
          res += lineConverter(line)
          line = mapReader.read(header: _*)
        }
        res
      }
      .toList
  }
}
