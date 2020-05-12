package beam.utils.plan_converter.reader

import beam.utils.FileUtils
import beam.utils.plan_converter.entities.TripElement
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

class TripReader(path: String) extends Reader[TripElement]{
  override def iterator(): Iterator[TripElement] = {
    FileUtils.using(new CsvMapReader(FileUtils.readerFromFile(path), CsvPreference.STANDARD_PREFERENCE)) { csvRdr =>
      val header = csvRdr.getHeader(true)
      Iterator
        .continually(csvRdr.read(header: _*))
        .takeWhile(data => data != null)
        .map(TripElement.transform)
    }
  }
}
