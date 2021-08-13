package beam.utils.data.ctpp.readers.flow

import beam.utils.data.ctpp.models.{FlowGeoParser, HouseholdIncome, OD, ResidenceToWorkplaceFlowGeography}
import beam.utils.data.ctpp.readers.BaseTableReader
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData, Table}

class HouseholdIncomeTableReader(
  dbInfo: CTPPDatabaseInfo,
  val residenceToWorkplaceFlowGeography: ResidenceToWorkplaceFlowGeography
) extends BaseTableReader(
      dbInfo,
      Table.HouseholdIncomeInThePast12Months,
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

  def main(args: Array[String]): Unit = {
    val databaseInfo = CTPPDatabaseInfo(PathToData("d:/Work/beam/Austin/input/CTPP/"), Set("48"))
    val readData =
      new HouseholdIncomeTableReader(databaseInfo, ResidenceToWorkplaceFlowGeography.`PUMA5 To POWPUMA`)
        .read()
        .toVector
    val nonZeros = readData.filterNot(x => x.value.equals(0d))
    val distinctHomeLocations = readData.map(_.source).distinct.size
    val distintWorkLocations = readData.map(_.destination).distinct.size
    val sumOfValues = readData.map(_.value).sum

    println(s"Read ${readData.size} OD pairs. ${nonZeros.size} is non-zero")
    println(s"distinctHomeLocations: $distinctHomeLocations")
    println(s"distintWorkLocations: $distintWorkLocations")
    println(s"sumOfValues: ${sumOfValues.toInt}")
  }
}
