package beam.utils.plan_converter

import java.io.Closeable

import beam.utils.FileUtils
import org.slf4j.LoggerFactory
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

object UrbansimConverter {

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    transform(
      "/Users/alex/Documents/Projects/Simultion/csv/trips.csv",
      "/Users/alex/Documents/Projects/Simultion/csv/plans.csv",
      "/Users/alex/Documents/Projects/Simultion/csv/plans.out.csv"
    )
  }

  def transform(inputTripPath: String, inputPlanPath: String, outputPlanPath: String) = {
    logger.info("Reading of the trips...")
    val trips = getTripPlan(inputTripPath)
    logger.info("Mergin modes into plan...")
    mergeModeIntoPlan(inputPlanPath, outputPlanPath, trips)
    logger.info("Work is done")
  }

  private def mergeModeIntoPlan(
    planInputPath: String,
    planOutputPlan: String,
    tripElements: Map[Int, TripElement]
  ): Unit = {
    val (inputPlans, reader) = readPlan(planInputPath)

    try {
      inputPlans.take(1).toList.foreach(println)
      println("debug")
    } finally {
      reader.close()
    }
  }

  private def readPlan(path: String): (Iterator[InputPlanElement], Closeable) = {
    val csvRdr = new CsvMapReader(FileUtils.readerFromFile(path), CsvPreference.STANDARD_PREFERENCE)
    val headers = csvRdr.getHeader(true)
    val iter = Iterator
      .continually(csvRdr.read(headers: _*))
      .takeWhile(data => data != null)
      .map(InputPlanElement.transform)

    (iter, csvRdr)
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
