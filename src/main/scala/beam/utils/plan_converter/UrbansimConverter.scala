package beam.utils.plan_converter

import java.io.{BufferedReader, BufferedWriter, Closeable, FileWriter}

import beam.utils.FileUtils
import org.slf4j.LoggerFactory
import org.supercsv.io.{CsvMapReader, CsvMapWriter}
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._

object UrbansimConverter {

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    transform(
      "/Users/alex/Documents/Projects/Simultion/newCsv/trips.csv",
      "/Users/alex/Documents/Projects/Simultion/newCsv/plans.csv",
      "/Users/alex/Documents/Projects/Simultion/newCsv/plans.out.csv"
    )
  }

  def transform(inputTripPath: String, inputPlanPath: String, outputPlanPath: String) = {
    logger.info("Reading of the trips...")
    val modes = getTripModes(inputTripPath)
    logger.info("Merging modes into plan...")

    FileUtils.using(FileUtils.readerFromFile(inputPlanPath)) { inputBuffer =>
      val (inputPlans, reader) = readPlan(inputBuffer)
      try {
        val outputIter = merge(inputPlans, modes)
        FileUtils.using(new BufferedWriter(new FileWriter(outputPlanPath))) { outputBuffer =>
          logger.info("Writing output plan...")
          val writer = writePlans(outputBuffer, outputIter)
          writer.close()
        }

        logger.info("Finished")
      } finally {
        reader.close()
      }
    }

  }

  private def merge(
    inputPlans: Iterator[InputPlanElement],
    modes: Map[(Int, Double), String]
  ): Iterator[OutputPlanElement] = {
    val merger = new Merger(modes)

    merger.merge(inputPlans)
  }

  private def readPlan(reader: BufferedReader): (Iterator[InputPlanElement], Closeable) = {
    val csvReader = new CsvMapReader(reader, CsvPreference.STANDARD_PREFERENCE)
    val headers = csvReader.getHeader(true)
    val iter = Iterator
      .continually(csvReader.read(headers: _*))
      .takeWhile(data => data != null)
      .map(InputPlanElement.transform)

    (iter, csvReader)
  }

  private def writePlans(writer: BufferedWriter, iter: Iterator[OutputPlanElement]): Closeable = {
    val csvWriter = new CsvMapWriter(writer, CsvPreference.STANDARD_PREFERENCE)
    csvWriter.writeHeader(OutputPlanElement.headers: _*)
    iter.foreach(out => csvWriter.write(out.toRow().asJava, OutputPlanElement.headers: _*))

    csvWriter.flush()
    csvWriter
  }

  private def getTripModes(path: String): Map[(Int, Double), String] =
    FileUtils.using(new CsvMapReader(FileUtils.readerFromFile(path), CsvPreference.STANDARD_PREFERENCE)) { csvRdr =>
      val header = csvRdr.getHeader(true)
      Iterator
        .continually(csvRdr.read(header: _*))
        .takeWhile(data => data != null)
        .map(TripElement.transform)
        .map(tripElement => (tripElement.personId, tripElement.depart) -> tripElement.trip_mode)
        .toMap
    }

}
