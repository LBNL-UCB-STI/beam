package beam.utils.scenario.generic.writers

import beam.utils.csv.CsvWriter
import beam.utils.scenario.PersonInfo

import scala.util.Try

trait PersonInfoWriter {
  def write(path: String, xs: Iterable[PersonInfo]): Unit
}

object CsvPersonInfoWriter extends PersonInfoWriter {

  private val headers: Array[String] =
    Array("personId", "householdId", "age", "isFemale", "householdRank", "valueOfTime")

  override def write(path: String, xs: Iterable[PersonInfo]): Unit = {
    val csvWriter = new CsvWriter(path, headers)
    try {
      xs.foreach { person =>
        csvWriter.write(
          person.personId.id,
          person.householdId.id,
          person.age,
          person.isFemale,
          person.rank,
          person.valueOfTime
        )
      }
    } finally {
      Try(csvWriter.close())
    }
  }
}
