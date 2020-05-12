package beam.utils.plan_converter.reader

import beam.utils.FileUtils
import beam.utils.plan_converter.Transformer
import beam.utils.plan_converter.entities.InputPersonInfo
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

abstract class BaseCsvReader[T](path: String) extends Reader[T]{
  val transformer: Transformer[T]

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
