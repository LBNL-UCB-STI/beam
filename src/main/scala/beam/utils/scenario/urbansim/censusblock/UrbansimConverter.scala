package beam.utils.scenario.urbansim.censusblock

import java.io.{BufferedWriter, Closeable, FileWriter}

import beam.utils.FileUtils
import beam.utils.scenario.PlanElement
import beam.utils.scenario.urbansim.censusblock.entities.{InputPlanElement, OutputPlanElement}
import beam.utils.scenario.urbansim.censusblock.merger.PlanMerger
import beam.utils.scenario.urbansim.censusblock.reader.{PlanReader, Reader}
import org.slf4j.LoggerFactory
import org.supercsv.io.CsvMapWriter
import org.supercsv.prefs.CsvPreference
import scala.collection.JavaConverters._

object UrbansimConverter {

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    transform(
      "/Users/alex/Documents/Projects/Simultion/newCsv/plans.csv",
      "/Users/alex/Documents/Projects/Simultion/newCsv/plans.out.csv"
    )
  }

  def transform(inputPlanPath: String, outputPlanPath: String): Unit = {

    val modeMap = Map(
      "DRIVEALONEPAY"  -> "car",
      "DRIVEALONEFREE" -> "car",
      "WALK"           -> "walk",
      "BIKE"           -> "bike",
      "SHARED3FREE"    -> "car",
      "SHARED2PAY"     -> "car",
      "SHARED2FREE"    -> "car",
      "SHARED3PAY"     -> "car",
      "WALK_LOC"       -> "walk_transit",
      "DRIVE_LOC"      -> "drive_transit"
    )
    logger.info("Merging modes into plan...")

    val planReader = new PlanReader(inputPlanPath)

    try {
      val inputPlans = readPlan(planReader)
      val outputIter = merge(inputPlans, modeMap)
      FileUtils.using(new BufferedWriter(new FileWriter(outputPlanPath))) { outputBuffer =>
        logger.info("Writing output plan...")
        val writer = writePlans(outputBuffer, outputIter)
        writer.close()
      }
      logger.info("Finished")
    } finally {
      planReader.close()
    }
  }

  private def merge(
    inputPlans: Iterator[InputPlanElement],
    modeMap: Map[String, String]
  ): Iterator[PlanElement] = {
    val merger = new PlanMerger(modeMap)

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
      "x"                -> planElement.activityLocationX.getOrElse(null),
      "y"                -> planElement.activityLocationY.getOrElse(null),
      "endTime"          -> planElement.legDepartureTime.getOrElse(null),
      "mode"             -> planElement.legMode.getOrElse("")
    ).asJava
  }

}
