package scripts.ctpp.readers.flow

import beam.utils.csv.CsvWriter
import scripts.ctpp.models.ResidenceToWorkplaceFlowGeography.`TAZ To TAZ`
import scripts.ctpp.models.{FlowGeoParser, HouseholdIncome, OD, ResidenceToWorkplaceFlowGeography}
import scripts.ctpp.readers.BaseTableReader
import scripts.ctpp.readers.BaseTableReader.Table.Flow.HouseholdIncomeInThePast12Months
import scripts.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData, Table}

class HouseholdIncomeTableReader(
  dbInfo: CTPPDatabaseInfo,
  val residenceToWorkplaceFlowGeography: ResidenceToWorkplaceFlowGeography
) extends BaseTableReader(
      dbInfo,
      HouseholdIncomeInThePast12Months,
      Some(residenceToWorkplaceFlowGeography.level)
    ) {

  def read(): Iterable[OD[HouseholdIncome]] = {
    readRaw()
      .map { entry =>
        val (fromGeoId, toGeoId) = FlowGeoParser.parse(entry.geoId)
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
      new HouseholdIncomeTableReader(databaseInfo, `TAZ To TAZ`)
        .read()
        .toVector
    val nonZeros = readData.filterNot(x => x.value.equals(0d))
    val distinctHomeLocations = readData.map(_.source).distinct.size
    val distintWorkLocations = readData.map(_.destination).distinct.size
    val sumOfValues = readData.map(_.value).sum

    val csvWriter = new CsvWriter("household_income_aggregated.csv", Array("source", "destination", "cttp_count"))
    try {
      readData
        .groupBy { x =>
          (x.source, x.destination)
        }
        .foreach { case ((src, dst), xs) =>
          val sumAllCounts = xs.map(_.value).sum.toInt
          csvWriter.write(quoteCsv(src), quoteCsv(dst), sumAllCounts)
        }
    } finally {
      csvWriter.close()
    }
  }

}
