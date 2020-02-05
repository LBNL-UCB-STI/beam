package beam.utils.data.ctpp.readers

import beam.utils.data.ctpp.CTPPParser
import beam.utils.data.ctpp.models.ResidenceGeography
import beam.utils.data.ctpp.readers.BaseTableReader.Table
import com.typesafe.scalalogging.LazyLogging

class TotalPopulationTableReader(pathToData: String, val residenceGeography: ResidenceGeography)
    extends BaseTableReader(pathToData, Table.PopulationInHouseholds, Some(residenceGeography.level))
    with LazyLogging {
  logger.info(s"Path to table $table is '$pathToCsvTable'")

  def read(): Map[String, Int] = {
    val totalHouseholdMap = CTPPParser
      .readTable(pathToCsvTable, geographyLevelFilter)
      .groupBy(x => x.geoId)
      .map {
        case (geoId, xs) =>
          // It is one to one relation, that's why we get the head
          geoId -> xs.head.estimate.toInt
      }
    totalHouseholdMap
  }
}
