package beam.utils

import java.io._
import java.util.zip.GZIPInputStream

import org.supercsv.cellprocessor.constraint.{NotNull, UniqueHashCode}
import org.supercsv.cellprocessor.ift.CellProcessor
import org.supercsv.io.{CsvMapReader, CsvMapWriter, ICsvMapReader, ICsvMapWriter}
import org.supercsv.prefs.CsvPreference

object CsvUtils {

  def readerFromFile(filePath: String): java.io.Reader = {
    if (filePath.endsWith(".gz")) {
      new InputStreamReader(
        new GZIPInputStream(new BufferedInputStream(new FileInputStream(filePath)))
      )
    } else {
      new FileReader(filePath)
    }
  }

  def getHash(concatParams: Any*): Int = {
    val concatString = concatParams.foldLeft("")((a, b) => a + b)
    concatString.hashCode
  }

}
