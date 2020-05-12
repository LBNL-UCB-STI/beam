package beam.utils.plan_converter.reader

import beam.utils.FileUtils
import beam.utils.plan_converter.entities.TripElement
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

class TripReader(path: String) extends Reader[TripElement]{

  private val csvMapReader = new CsvMapReader(FileUtils.readerFromFile(path), CsvPreference.STANDARD_PREFERENCE)

  override def iterator(): Iterator[TripElement] = {
      val header = csvMapReader.getHeader(true)
      Iterator
        .continually(csvMapReader.read(header: _*))
        .takeWhile(data => data != null)
        .map(TripElement.transform)
  }

  override def close(): Unit = csvMapReader.close()
}
