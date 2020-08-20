package beam.analysis.plots

import beam.analysis.summary.PersonCostAnalysis
import org.scalatest.Matchers

class PersonCostAnalysisSpec extends GenericAnalysisSpec with Matchers {

  override def beforeAll(): Unit = {
    super.beforeAll()

    runAnalysis(new PersonCostAnalysis(beamServices))
  }

  "Person cost analyser " must {
    "calculate average trip expense" in {
      summaryStats.get("averageTripExpenditure") should not be 0
    }

    "calculate total cost" ignore {
      print(summaryStats)
      summaryStats.get("totalCost_ride_hail") should not be 0
      summaryStats.get("totalCost_car") should not be 0
      /*{totalToll_ride_hail=0.0,
      totalIncentive_car=0.0}*/
    }

    "calculate total ride_hail incentive" ignore {
      summaryStats.get("totalIncentive_ride_hail") should not be 0
    }

    "calculate total car toll" in {
      val result = summaryStats.get("totalToll_car")
      result should not be 0
    }
  }
}
