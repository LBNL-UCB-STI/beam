package beam.utils.plan_converter

import beam.utils.FileUtils
import org.matsim.core.utils.io.IOUtils
import org.slf4j.LoggerFactory
import org.supercsv.io.{CsvMapReader, CsvMapWriter}
import org.supercsv.prefs.CsvPreference

object UrbansimConverter {

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    transform("/Users/alex/Documents/Projects/Simultion/csv/trips.csv")
  }

  def transform(inputTripFile: String) = {
    logger.info("Reading of the trips...")

    val trips = getTripPlan(inputTripFile)

    logger.info("Trips have been read")

    trips.size
  }

  private def mergeModeIntoPlan(
    planInputPath: String,
    planOutputPlan: String,
    tripElements: Map[Int, TripElement]
  ): Unit = {
    val planCsvReader = new CsvMapReader(FileUtils.readerFromFile(planInputPath), CsvPreference.STANDARD_PREFERENCE)
    val inputHeaders = planCsvReader.getHeader(true)

    val bufferedWriter = IOUtils.getBufferedWriter(planOutputPlan)
    val planCsvWriter = new CsvMapWriter(bufferedWriter, CsvPreference.STANDARD_PREFERENCE)
    val outputHeader = Seq("personId", "planElement", "planElementIndex", "activityType", "x", "y", "endTime", "mode")
    planCsvWriter.writeHeader()

    planCsvReader.close()

    planCsvWriter.flush()
    planCsvWriter.close()
  }

  private def getTripPlan(path: String): Map[Int, TripElement] =
    FileUtils.using(new CsvMapReader(FileUtils.readerFromFile(path), CsvPreference.STANDARD_PREFERENCE)) { csvRdr =>
      val header = csvRdr.getHeader(true)
      Iterator
        .continually(csvRdr.read(header: _*))
        .takeWhile(data => data != null)
        .map(TripElement.transform)
        .map(tripElement => tripElement.tripId -> tripElement)
        .toMap
    }

}
