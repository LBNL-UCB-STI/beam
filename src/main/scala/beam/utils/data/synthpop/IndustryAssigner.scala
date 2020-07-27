package beam.utils.data.synthpop

import beam.utils.ProfilingUtils
import beam.utils.csv.{CsvWriter, GenericCsvReader}
import beam.utils.data.ctpp.models.residence.{Industry => ResidenceIndustry}
import beam.utils.data.ctpp.models.flow.{Industry => FlowIndustry}
import beam.utils.data.ctpp.models.{ResidenceGeography, ResidenceToWorkplaceFlowGeography}
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData}
import beam.utils.data.ctpp.readers.flow.IndustryTableReader
import beam.utils.data.ctpp.readers.residence
import beam.utils.scenario.generic.readers.CsvPlanElementReader

class IndustryAssigner {}

object IndustryAssigner {


  def main(args: Array[String]): Unit = {
    require(args.length == 2, "Expected two args: 1) path to CTPP 2) Path to plans")
    val pathToCTPP: String = args(0) // "d:/Work/beam/CTPP/"
    val pathToPlans: String = args(1) // "D:/Work/beam/NewYork/results_07-10-2020_22-13-14/plans.csv.gz"
    val databaseInfo = CTPPDatabaseInfo(PathToData(pathToCTPP), Set("36", "34"))

    val odList = new IndustryTableReader(databaseInfo, ResidenceToWorkplaceFlowGeography.`TAZ To TAZ`).read()
    println(s"Read ${odList.size} OD pairs from industry table")


    val odToIndustrySeq = odList
      .groupBy { od =>
        (od.source, od.destination)
      }
      .toSeq
      .map { case (key, xs) => key -> xs.toArray }
      .sortBy { case (key, xs) => -xs.length }
    println(s"odToIndustrySeq: ${odToIndustrySeq.size}")

    val odToIndustryMap = odToIndustrySeq.toMap

    val homeGeoIdToWorkGeoIdWithCounts: Seq[((String, String), Int)] =
      if (false) readFromPlans(pathToPlans) else readFromCsv("homeGeoIdToWorkGeoIdWithCounts.csv")
    println(s"homeGeoIdToWorkGeoIdWithCounts ${homeGeoIdToWorkGeoIdWithCounts.size}")

    // writeToCsv(homeGeoIdToWorkGeoIdWithCounts)

    val nKeyIsNotFoundInOdToIndustryMap = homeGeoIdToWorkGeoIdWithCounts.count { case (key, _) => !odToIndustryMap.contains(key) }
    println(s"nKeyIsNotFoundInOdToIndustryMap: ${nKeyIsNotFoundInOdToIndustryMap}")

    val residenceIndustryOdMap = new residence.IndustryTableReader(databaseInfo, ResidenceGeography.TAZ).read()
    println(s"residenceIndustryOdMap: ${residenceIndustryOdMap.size}")


    val headers =  Array("origin", "destination", "total_people_from_scenario", "total_by_flow", "total_by_residence") ++
      FlowIndustry.all.map(col => s"flow_${col.toString}") ++ FlowIndustry.all.map(col => s"residence_${col.toString}")

    val csvWriter = new CsvWriter("industry.csv", headers)

    homeGeoIdToWorkGeoIdWithCounts.foreach { case (key, totalNumberOfPeople) =>
      (odToIndustryMap.get(key), residenceIndustryOdMap.get(key._1)) match {
        case (Some(flowIndustries), Some(residenceIndustries)) =>
          val residenceIndustriesToFlowIndustries = residenceIndustries.map(toFlowIndustry)
            .groupBy { case (industry, _) =>  industry }
            .toSeq
            .map { case (industry, xs) =>
                industry -> xs.map(_._2).sum
            }
          val totalByFlow = flowIndustries.map(_.value).sum
          val totalByResidence = residenceIndustriesToFlowIndustries.map(_._2).sum

          val row = Array(
            key._1,
            key._2,
            totalNumberOfPeople,
            totalByFlow,
            totalByResidence,
          ) ++ FlowIndustry.all.map { c => flowIndustries.find(x => x.attribute == c).map(_.value).getOrElse(0.0)} ++
            FlowIndustry.all.map { c => residenceIndustriesToFlowIndustries.find { case (industry, _) => industry == c }.map(_._2).getOrElse(0.0)}

          csvWriter.write(row: _*)

//          println(s"totalNumberOfPeople: $totalNumberOfPeople, totalByFlow of industries: $totalByFlow, totalByResidence: $totalByResidence")
//          println(s"Industries: ${flowIndustries.mkString(" ")}")
//          println(s"residenceIndustriesToFlowIndustries: ${residenceIndustriesToFlowIndustries.mkString(" ")}")

        case _ =>
      }

    }
    csvWriter.close()

  }

  private def readFromPlans(pathToPlans: String): Seq[((String, String), Int)] = {
    val homeWorkActivities = ProfilingUtils.timed("Read plans", println) {
      CsvPlanElementReader
        .read(pathToPlans)
        .filter { plan =>
          val isActivity = plan.planElementType.equalsIgnoreCase("activity")
          val isHomeOrWork = plan.activityType.exists(
            act => act.equalsIgnoreCase("home") || act.equalsIgnoreCase("work")
          )
          isActivity && isHomeOrWork
        }
    }
    println(s"Read ${homeWorkActivities.length} home-work activities")

    val homeGeoIdToWorkGeoId = homeWorkActivities
      .groupBy(plan => plan.personId.id)
      .filter { case (_, xs) =>
        val firstActivity = xs.lift(0)
        val secondActivity = xs.lift(1)
        val isFirstHome = firstActivity.exists(x => x.activityType.exists(actType => actType.equalsIgnoreCase("home")))
        val isSecondWork = secondActivity.exists(x => x.activityType.exists(actType => actType.equalsIgnoreCase("work")))
        isFirstHome && isSecondWork
      }
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
    homeGeoIdToWorkGeoIdWithCounts
  }

  private def readFromCsv(path: String): Seq[((String, String), Int)] = {
    def mapper(rec: java.util.Map[String, String]): ((String, String), Int) = {
      val origin = rec.get("origin_geoid")
      val destination = rec.get("destination_geoid")
      val count = rec.get("count").toInt
      ((origin, destination), count)
    }
    val (it, toClose) = GenericCsvReader.readAs[((String, String), Int)](path, mapper, _ => true)
    try {
      it.toVector
    } finally {
      toClose.close()
    }
  }

  private def writeToCsv(homeGeoIdToWorkGeoIdWithCounts: Seq[((String, String), Int)]): Unit = {
    val csvWriter =
      new CsvWriter("homeGeoIdToWorkGeoIdWithCounts.csv", Array("origin_geoid", "destination_geoid", "count"))
    homeGeoIdToWorkGeoIdWithCounts.foreach {
      case ((origin, dest), count) =>
        csvWriter.write(origin, dest, count)
    }
    csvWriter.close()
  }

  def toFlowIndustry(input: (ResidenceIndustry, Double)): (FlowIndustry, Double) = {
    val (workIndustry, count) = input
    val flowIndustry = workIndustry match {
      case ResidenceIndustry.Agriculture | ResidenceIndustry.Construction | ResidenceIndustry.ArmedForces => FlowIndustry.Agriculture
      case ResidenceIndustry.Manufacturing =>  FlowIndustry.Manufacturing
      case ResidenceIndustry.WholesaleTrade | ResidenceIndustry.RetailTrade | ResidenceIndustry.Transportation => FlowIndustry.WholesaleTrade
      case ResidenceIndustry.Information | ResidenceIndustry.Finance | ResidenceIndustry.Professional => FlowIndustry.Information
      case ResidenceIndustry.Educational => FlowIndustry.Educational
      case ResidenceIndustry.Arts => FlowIndustry.Arts
      case ResidenceIndustry.OtherServices | ResidenceIndustry.PublicAdministration  => FlowIndustry.OtherServices
    }
    (flowIndustry, count)
  }

}
