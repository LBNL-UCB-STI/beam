package beam.utils.plan_converter.reader

import beam.utils.FileUtils
import beam.utils.plan_converter.entities.InputPlanElement
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

class PlanReader(path: String) extends Reader[InputPlanElement] {

  private val csvReader = new CsvMapReader(FileUtils.readerFromFile(path), CsvPreference.STANDARD_PREFERENCE)

  override def iterator(): Iterator[InputPlanElement] = {
    val headers = csvReader.getHeader(true)
    Iterator
      .continually(csvReader.read(headers: _*))
      .takeWhile(data => data != null)
      .map(InputPlanElement.transform)
  }

  override def close(): Unit = csvReader.close()
}
