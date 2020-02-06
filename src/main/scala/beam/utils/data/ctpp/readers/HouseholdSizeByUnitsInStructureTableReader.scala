package beam.utils.data.ctpp.readers

import beam.utils.data.ctpp.CTPPParser
import beam.utils.data.ctpp.Models.CTPPEntry
import beam.utils.data.ctpp.models.{Household, ResidenceGeography}
import beam.utils.data.ctpp.readers.BaseTableReader.{PathToData, Table}

class HouseholdSizeByUnitsInStructureTableReader(pathToData: PathToData, val residenceGeography: ResidenceGeography)
    extends BaseTableReader(pathToData, Table.HouseholdSizeByUnitsInStructure, Some(residenceGeography.level)) {
  private val `1-person household-lineNumber`: Int = 10
  private val `2-person household-lineNumber`: Int = 19
  private val `3-person household-lineNumber`: Int = 28
  private val `4-person household-lineNumber`: Int = 37

  def read(): Map[String, Map[Household, Double]] = {

    // A112210 - Household size (5) by Units in Structure (9) (Households)  is 2-D data, but we use it only to get the size of households
    val map: Map[String, Map[Household, Double]] = CTPPParser
      .readTable(pathToCsvTable, geographyLevelFilter)
      .groupBy(x => x.geoId)
      .map {
        case (geoId, xs) =>
          val `1-person household` = findOr0(xs, `1-person household-lineNumber`, "1-person household")
          val `2-person household` = findOr0(xs, `2-person household-lineNumber`, "2-person household")
          val `3-person household` = findOr0(xs, `3-person household-lineNumber`, "3-person household")
          val `4-or-more-person household` = findOr0(xs, `4-person household-lineNumber`, "4-or-more-person household")
          val householdTypeToFreq: Map[Household, Double] = Map(
            Household.`1-person household`         -> `1-person household`,
            Household.`2-person household`         -> `2-person household`,
            Household.`3-person household`         -> `3-person household`,
            Household.`4-or-more-person household` -> `4-or-more-person household`
          )
          geoId -> householdTypeToFreq
      }
    map
  }

  private def findOr0(xs: Seq[CTPPEntry], lineNumber: Int, what: String): Double = {
    xs.find(x => x.lineNumber == lineNumber).map(_.estimate).getOrElse {
      // TODO better data missing handling
      // logger.warn(s"Could not find total count for '$what' in input ${xs.mkString(" ")}")
      0
    }
  }
}
