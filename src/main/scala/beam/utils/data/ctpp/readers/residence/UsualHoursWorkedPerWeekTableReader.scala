package beam.utils.data.ctpp.readers.residence

import beam.utils.data.ctpp.models.{ResidenceGeography, WorkedHours}
import beam.utils.data.ctpp.readers.BaseTableReader
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, Table}

class UsualHoursWorkedPerWeekTableReader(dbInfo: CTPPDatabaseInfo, val residenceGeography: ResidenceGeography)
    extends BaseTableReader(dbInfo, Table.UsualHoursWorkedPerWeek, Some(residenceGeography.level)) {

  private val `Usually worked 1 to 14 hours per week-lineNumber`: Int = 2
  private val `Usually worked 15 to 20 hours per week-lineNumber`: Int = 3
  private val `Usually worked 21 to 34 hours per week-lineNumber`: Int = 4
  private val `Usually worked 35 to 40 hours per week-lineNumber`: Int = 5
  private val `Usually worked 41 to 55 hours per week-lineNumber`: Int = 6
  private val `Usually worked 56 or more hours per week-lineNumber`: Int = 7

  def read(): Map[String, Map[WorkedHours, Double]] = {
    val map: Map[String, Map[WorkedHours, Double]] = readRaw()
      .groupBy(x => x.geoId)
      .map { case (geoId, xs) =>
        val v1 = findEstimateByLineNumberOr0(
          xs,
          `Usually worked 1 to 14 hours per week-lineNumber`,
          "Usually worked 1 to 14 hours per week"
        )
        val v2 = findEstimateByLineNumberOr0(
          xs,
          `Usually worked 15 to 20 hours per week-lineNumber`,
          "Usually worked 15 to 20 hours per week"
        )
        val v3 = findEstimateByLineNumberOr0(
          xs,
          `Usually worked 21 to 34 hours per week-lineNumber`,
          "Usually worked 21 to 34 hours per week"
        )
        val v4 = findEstimateByLineNumberOr0(
          xs,
          `Usually worked 35 to 40 hours per week-lineNumber`,
          "Usually worked 35 to 40 hours per week"
        )
        val v5 = findEstimateByLineNumberOr0(
          xs,
          `Usually worked 41 to 55 hours per week-lineNumber`,
          "Usually worked 41 to 55 hours per week"
        )
        val v6 = findEstimateByLineNumberOr0(
          xs,
          `Usually worked 56 or more hours per week-lineNumber`,
          "Usually worked 56 or more hours per week"
        )
        val workedHoursFreq: Map[WorkedHours, Double] = Map(
          WorkedHours.`Usually worked 1 to 14 hours per week`    -> v1,
          WorkedHours.`Usually worked 15 to 20 hours per week`   -> v2,
          WorkedHours.`Usually worked 21 to 34 hours per week`   -> v3,
          WorkedHours.`Usually worked 35 to 40 hours per week`   -> v4,
          WorkedHours.`Usually worked 41 to 55 hours per week`   -> v5,
          WorkedHours.`Usually worked 56 or more hours per week` -> v6
        )
        geoId -> workedHoursFreq
      }
    map
  }
}
