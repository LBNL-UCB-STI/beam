package beam.utils.csv.writers

import java.io.File

import beam.utils.FileUtils
import org.matsim.api.core.v01.Scenario
import ScenarioCsvWriter._

trait ScenarioCsvWriter {
  protected def fields: Seq[String]

  private def header: Iterator[String] = Iterator(fields.mkString(FieldSeparator), LineSeparator)

  def contentIterator(scenario: Scenario): Iterator[String]

  def contentIterator[A](elements: Iterator[A]): Iterator[String]

  def toCsv(scenario: Scenario): Iterator[String] = header ++ contentIterator(scenario)

  def toCsv(scenario: Scenario, outputFile: String): File = {
    FileUtils.writeToFile(outputFile, toCsv(scenario))
    new File(outputFile)
  }

  final def toCsv[A](elements: Iterator[A], outputFile: String): File = {
    FileUtils.writeToFile(outputFile, contentIterator(elements))
    new File(outputFile)
  }

}

object ScenarioCsvWriter {

  val LineSeparator: String = "\n"
  val FieldSeparator: String = ","
  val ArrayStartString: String = "\""
  val ArrayEndString: String = "\""
  val ArrayItemSeparator: String = ";"

}
