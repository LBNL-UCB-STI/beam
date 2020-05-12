package beam.utils.plan_converter

import java.io.{BufferedReader, BufferedWriter, Closeable, FileWriter}

import beam.utils.FileUtils
import beam.utils.plan_converter.entities.{InputPlanElement, OutputPlanElement, TripElement}
import beam.utils.plan_converter.merger.PlanMerger
import beam.utils.plan_converter.reader.{PlanReader, Reader, TripReader}
import beam.utils.scenario.PlanElement
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
    val tripReader = new TripReader(inputTripPath)

    val modes = getTripModes(tripReader)
    logger.info("Merging modes into plan...")

    val planReader = new PlanReader(inputPlanPath)

    try {
      val inputPlans = readPlan(planReader)
      val outputIter = merge(inputPlans, modes)
      FileUtils.using(new BufferedWriter(new FileWriter(outputPlanPath))) { outputBuffer =>
        logger.info("Writing output plan...")
        val writer = writePlans(outputBuffer, outputIter)
        writer.close()
      }
      logger.info("Finished")
    } finally {
      planReader.close()
      tripReader.close()
    }
  }

  private def merge(
    inputPlans: Iterator[InputPlanElement],
    modes: Map[(Int, Double), String]
  ): Iterator[PlanElement] = {
    val merger = new PlanMerger(modes)

    merger.merge(inputPlans)
  }

  private def readPlan(reader: Reader[InputPlanElement]): Iterator[InputPlanElement] = reader.iterator()

  private def writePlans(writer: BufferedWriter, iter: Iterator[PlanElement]): Closeable = {
    val csvWriter = new CsvMapWriter(writer, CsvPreference.STANDARD_PREFERENCE)
    csvWriter.writeHeader(OutputPlanElement.headers: _*)
    iter.foreach(out => csvWriter.write(transformPlanElement(out), OutputPlanElement.headers: _*))

    csvWriter.flush()
    csvWriter
  }

  private def transformPlanElement(planElement: PlanElement): java.util.Map[String, Any] = {
    Map(
      "personId"         -> planElement.personId,
      "planElement"      -> planElement.planElementType,
      "planElementIndex" -> planElement.planElementIndex,
      "activityType"     -> planElement.activityType.getOrElse(""),
      "x"                -> planElement.activityLocationX.orNull,
      "y"                -> planElement.activityLocationY.orNull,
      "endTime"          -> planElement.activityEndTime.orNull,
      "mode"             -> planElement.legMode.getOrElse("")
    ).asJava
  }

  private def getTripModes(reader: Reader[TripElement]): Map[(Int, Double), String] =
    reader
      .iterator()
      .map(tripElement => (tripElement.personId, tripElement.depart) -> tripElement.trip_mode)
      .toMap

}
