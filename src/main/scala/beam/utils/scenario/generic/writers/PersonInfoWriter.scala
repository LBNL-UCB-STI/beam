package beam.utils.scenario.generic.writers

import beam.utils.csv.CsvWriter
import beam.utils.scenario.PersonInfo

import scala.util.Try

trait PersonInfoWriter {
  def write(path: String, xs: Iterable[PersonInfo]): Unit
}

object CsvPersonInfoWriter extends PersonInfoWriter {
  private val headers: Array[String] =
    Array("personId", "householdId", "age", "isFemale", "householdRank", "valueOfTime", "industry")
  override def write(path: String, xs: Iterable[PersonInfo]): Unit = {
    val csvWriter = new CsvWriter(path, headers)
    try {
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
    } finally {
      Try(csvWriter.close())
    }
  }
}
