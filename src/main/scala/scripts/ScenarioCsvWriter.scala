package scripts

import java.io.File

import beam.utils.FileUtils
import org.matsim.api.core.v01.Scenario

trait ScenarioCsvWriter {
  protected def fields: Seq[String]

  protected val LineSeparator: String = "\n"
  protected val FieldSeparator: String = ","

  private def header: Iterator[String] = Iterator(fields.mkString(FieldSeparator), LineSeparator)

  def contentIterator(scenario: Scenario): Iterator[String]

  def toCsv(scenario: Scenario): Iterator[String] = header ++ contentIterator(scenario)

  def toCsv(scenario: Scenario, outputFile: String): File = {
    FileUtils.writeToFile(outputFile, toCsv(scenario))
    new File(outputFile)
  }

}
