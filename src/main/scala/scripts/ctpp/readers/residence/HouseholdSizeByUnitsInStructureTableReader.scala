package scripts.ctpp.readers.residence

import scripts.ctpp.models.ResidenceGeography.State
import scripts.ctpp.models.{HouseholdSize, ResidenceGeography}
import scripts.ctpp.readers.BaseTableReader
import scripts.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData}
import scripts.ctpp.readers.BaseTableReader.Table.Residence.HouseholdSizeByUnitsInStructure

class HouseholdSizeByUnitsInStructureTableReader(dbInfo: CTPPDatabaseInfo, val residenceGeography: ResidenceGeography)
    extends BaseTableReader(dbInfo, HouseholdSizeByUnitsInStructure, Some(residenceGeography.level)) {
  private val `1-person household-lineNumber`: Int = 10
  private val `2-person household-lineNumber`: Int = 19
  private val `3-person household-lineNumber`: Int = 28
  private val `4-person household-lineNumber`: Int = 37

  def read(): Map[String, Map[HouseholdSize, Double]] = {
    // A112210 - Household size (5) by Units in Structure (9) (Households)  is 2-D data, but we use it only to get the size of households
    val map: Map[String, Map[HouseholdSize, Double]] = readRaw()
      .groupBy(x => x.geoId)
      .map { case (geoId, xs) =>
        val `1-person household` =
          findEstimateByLineNumberOr0(xs, `1-person household-lineNumber`, "1-person household")
        val `2-person household` =
          findEstimateByLineNumberOr0(xs, `2-person household-lineNumber`, "2-person household")
        val `3-person household` =
          findEstimateByLineNumberOr0(xs, `3-person household-lineNumber`, "3-person household")
        val `4-or-more-person household` =
          findEstimateByLineNumberOr0(xs, `4-person household-lineNumber`, "4-or-more-person household")
        val householdTypeToFreq: Map[HouseholdSize, Double] = Map(
          HouseholdSize.`1-person household`         -> `1-person household`,
          HouseholdSize.`2-person household`         -> `2-person household`,
          HouseholdSize.`3-person household`         -> `3-person household`,
          HouseholdSize.`4-or-more-person household` -> `4-or-more-person household`
        )
        geoId -> householdTypeToFreq
      }
    map
  }
}

object HouseholdSizeByUnitsInStructureTableReader {

  def main(args: Array[String]): Unit = {
    val databaseInfo = CTPPDatabaseInfo(PathToData("d:/Work/beam/Austin/input/CTPP/"), Set("48"))
    val rdr =
      new HouseholdSizeByUnitsInStructureTableReader(databaseInfo, State)
    val readData = rdr.read()
    readData.foreach { case (geoId, map) =>
      println(s"geoId: $geoId")
      map.foreach { case (size, count) =>
        println(s"$size: $count")

      }
    }
  }
}
