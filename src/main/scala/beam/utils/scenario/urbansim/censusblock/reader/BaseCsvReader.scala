package beam.utils.scenario.urbansim.censusblock.reader

import beam.utils.FileUtils
import beam.utils.scenario.urbansim.censusblock.EntityTransformer
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

abstract class BaseCsvReader[T](path: String) extends Reader[T] {
  val transformer: EntityTransformer[T]

  private val csvReader = new CsvMapReader(FileUtils.readerFromFile(path), CsvPreference.STANDARD_PREFERENCE)

  override def iterator(): Iterator[T] = {
    val headers = csvReader.getHeader(true)
    Iterator
      .continually(csvReader.read(headers: _*))
      .takeWhile(data => data != null)
      .map(transformer.transform)
  }

  override def close(): Unit = csvReader.close()
}
