package beam.utils.data.synthpop

import beam.utils.data.ctpp.models.ResidenceToWorkplaceFlowGeography
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData}
import beam.utils.data.ctpp.readers.flow.IndustryTableReader
import beam.utils.scenario.generic.readers.CsvPlanElementReader

class IndustryAssigner {}

object IndustryAssigner {

  def main(args: Array[String]): Unit = {
    require(args.length == 2, "Expected two args: 1) path to CTPP 2) Path to plans")
    val pathToCTPP: String = args(0) // "d:/Work/beam/CTPP/"
    val pathToPlans: String = args(1) // "D:/Work/beam/NewYork/results_07-10-2020_22-13-14/plans.csv.gz"
    val databaseInfo = CTPPDatabaseInfo(PathToData(pathToCTPP), Set("36", "34"))

    val odList = new IndustryTableReader(databaseInfo, ResidenceToWorkplaceFlowGeography.`TAZ To TAZ`).read()
    println(s"Read ${odList.size}")

    val tazToTazCounts: Map[(String, String), Int] = odList
      .groupBy { od =>
        (od.source, od.destination)
      }
      .map { case (key, xs) => key -> xs.size }
    println(s"tazToTazCounts: ${tazToTazCounts.keys.size}")

    val homeWorkActivities = CsvPlanElementReader
      .read(pathToPlans)
      .filter { plan =>
        plan.planElementType.equalsIgnoreCase("activity") && plan.activityType.exists(
          act => act.equalsIgnoreCase("home") || act.equalsIgnoreCase("Work")
        )
      }
    println(s"Read ${homeWorkActivities.length} home-work activities")

    val homeGeoIdToWorkGeoId = homeWorkActivities
      .groupBy(plan => plan.personId.id)
      .filter { case (_, xs) => xs.length >= 2 }
      .toSeq
      .map {
        case (_, xs) =>
          // First activity is home, so we can get its geoid
          val homeGeoId = xs(0).geoId.get.replace("-", "")
          // The second activity is work
          val workGeoId = xs(1).geoId.get.replace("-", "")
          ((homeGeoId, workGeoId), 1)
      }

    val homeGeoIdToWorkGeoIdWithCounts = homeGeoIdToWorkGeoId
      .groupBy { case ((o, d), _) => (o, d) }
      .toSeq
      .map {
        case ((o, d), xs) =>
          ((o, d), xs.map(_._2).sum)
      }
      .sortBy(x => -x._2)
    println(s"homeGeoIdToWorkGeoIdWithCounts ${homeGeoIdToWorkGeoIdWithCounts.size}")

  }
}
