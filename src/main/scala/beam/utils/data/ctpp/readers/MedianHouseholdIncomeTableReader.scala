package beam.utils.data.ctpp.readers

import beam.utils.data.ctpp.CTPPParser
import beam.utils.data.ctpp.models.ResidenceGeography
import beam.utils.data.ctpp.readers.BaseTableReader.{PathToData, Table}

class MedianHouseholdIncomeTableReader(pathToData: PathToData, val residenceGeography: ResidenceGeography)
    extends BaseTableReader(pathToData, Table.MedianHouseholdIncome, Some(residenceGeography.level)) {

  def read(): Map[String, Double] = {
    val map = CTPPParser
      .readTable(pathToCsvTable, geographyLevelFilter)
      .groupBy(x => x.geoId)
      .map {
        case (geoId, xs) =>
          // It is one to one relation, that's why we get the head
          val income = xs.head.estimate
          geoId -> income
      }
    map
  }

}
