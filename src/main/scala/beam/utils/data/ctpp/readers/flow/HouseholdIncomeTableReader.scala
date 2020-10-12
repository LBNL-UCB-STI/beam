package beam.utils.data.ctpp.readers.flow

import beam.utils.csv.CsvWriter
import beam.utils.data.ctpp.models.{FlowGeoParser, HouseholdIncome, OD, ResidenceToWorkplaceFlowGeography}
import beam.utils.data.ctpp.readers.BaseTableReader
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData, Table}

class HouseholdIncomeTableReader(
  dbInfo: CTPPDatabaseInfo,
  val residenceToWorkplaceFlowGeography: ResidenceToWorkplaceFlowGeography
) extends BaseTableReader(
      dbInfo,
      Table.Flow.HouseholdIncomeInThePast12Months,
      Some(residenceToWorkplaceFlowGeography.level)
    ) {

  def read(): Iterable[OD[HouseholdIncome]] = {
    readRaw()
      .map { entry =>
        val (fromGeoId, toGeoId) = FlowGeoParser.parse(entry.geoId).get
        val income = HouseholdIncome.all(entry.lineNumber - 1)
        OD(fromGeoId, toGeoId, income, entry.estimate)
      }
      .filter(_.attribute != HouseholdIncome.Total)
  }
}

object HouseholdIncomeTableReader {
  def quoteCsv(s: String): String = "\"" + s + "\""

  def main(args: Array[String]): Unit = {
    val pathToData = PathToData("D:/Work/beam/CTPP/")
    // 34 - New Jersey
    // 36 - New York
    val databaseInfo = CTPPDatabaseInfo(pathToData, Set("34", "36"))
    val readData =
      new HouseholdIncomeTableReader(databaseInfo, ResidenceToWorkplaceFlowGeography.`TAZ To TAZ`)
        .read()
        .toVector

    val csvWriter = new CsvWriter("household_income_aggregated.csv", Array("source", "destination", "cttp_count"))
    try {
      readData
        .groupBy { x =>
          (x.source, x.destination)
        }
        .foreach {
          case ((src, dst), xs) =>
            val sumAllCounts = xs.map(_.value).sum.toInt
            csvWriter.write(quoteCsv(src), quoteCsv(dst), sumAllCounts)
        }
    } finally {
      csvWriter.close()
    }
  }

}
