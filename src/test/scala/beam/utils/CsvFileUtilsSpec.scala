package beam.utils

import java.time.LocalDate

import org.scalatest.{Matchers, WordSpecLike}

class CsvFileUtilsSpec extends WordSpecLike with Matchers {

  private val validCsvFile = getClass.getResource("/csv/valid.csv").getPath

  "CsvFileUtilsSpec" should {
    "parse valid csv file into a map of a structure" in {
      val res = CsvFileUtils.readCsvFileByLineToMap(validCsvFile) { line =>
        val v = TestStructure(
          line.get("column_id").toInt,
          line.get("text_column").toString,
          line.get("double_column").toDouble,
          LocalDate.parse(line.get("date_column")),
          line.get("bool_column").toBoolean
        )
        (v.id, v)
      }

      res should have size 2
      res should contain only (
        1 -> TestStructure(1, "test", 1.2, LocalDate.parse("2020-06-04"), boolValue = false),
        2 -> TestStructure(2, "no-test", 6.8, LocalDate.parse("2021-06-04"), boolValue = true)
      )
    }

    "parse valid csv file into a list of a structure" in {
      val res = CsvFileUtils.readCsvFileByLineToList(validCsvFile) { line =>
        TestStructure(
          line.get("column_id").toInt,
          line.get("text_column").toString,
          line.get("double_column").toDouble,
          LocalDate.parse(line.get("date_column")),
          line.get("bool_column").toBoolean
        )
      }

      res should have size 2
      res should contain only (
        TestStructure(1, "test", 1.2, LocalDate.parse("2020-06-04"), boolValue = false),
        TestStructure(2, "no-test", 6.8, LocalDate.parse("2021-06-04"), boolValue = true)
      )
    }
  }

  private case class TestStructure(id: Int, text: String, doubleVal: Double, dateValue: LocalDate, boolValue: Boolean)
}
