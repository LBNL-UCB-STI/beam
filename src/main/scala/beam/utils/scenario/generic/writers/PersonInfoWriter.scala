package beam.utils.scenario.generic.writers

import beam.utils.csv.CsvWriter
import beam.utils.scenario.PersonInfo

import scala.util.Try

trait PersonInfoWriter {
  def write(path: String, xs: Iterator[PersonInfo]): Unit
}

class CsvPersonInfoWriter(val path: String) extends AutoCloseable {
  import beam.utils.scenario.generic.writers.CsvPersonInfoWriter._

  private val csvWriter = new CsvWriter(path, headers)

  def write(xs: Iterator[PersonInfo]): Unit = {
    writeTo(xs, csvWriter)
  }

  override def close(): Unit = {
    Try(csvWriter.close())
  }
}

object CsvPersonInfoWriter extends PersonInfoWriter {
  private val headers: Array[String] =
    Array("personId", "householdId", "age", "isFemale", "householdRank", "valueOfTime", "industry")
  override def write(path: String, xs: Iterator[PersonInfo]): Unit = {
    val csvWriter: CsvWriter = new CsvWriter(path, headers)
    try {
      writeTo(xs, csvWriter)
    } finally {
      Try(csvWriter.close())
    }
  }

  private def writeTo(xs: Iterator[PersonInfo], csvWriter: CsvWriter): Unit = {
    xs.foreach { person =>
      val industry = person.industry.getOrElse("")
      val escapedIndustry = s""""$industry""""
      csvWriter.write(
        person.personId.id,
        person.householdId.id,
        person.age,
        person.isFemale,
        person.rank,
        person.valueOfTime,
        escapedIndustry
      )
    }
  }
}
