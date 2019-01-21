package beam.analysis.plots

import beam.analysis.summary.PersonCostAnalysis
import org.scalatest.Matchers

class PersonCostAnalysisSpec extends GenericAnalysisSpec with Matchers {

  override def beforeAll(): Unit = {
    super.beforeAll()

    runAnalysis(new PersonCostAnalysis())
  }

  "Person cost analyser " must {
    "calculate average trip expense" in {
      assert(summaryStats.get("averageTripExpenditure") > 0.3)
    }

    "calculate total cost" in {
      print(summaryStats)
      assert(summaryStats.get("totalCost_ride_hail") > 55.0)
      assert(summaryStats.get("totalCost_car") > 8.0)
      /*{totalToll_ride_hail=0.0,
      totalIncentive_car=0.0}*/
    }

    "calculate total ride_hail incentive" in {
      assert(summaryStats.get("totalIncentive_ride_hail") > 96.0)
    }

    "calculate total car toll" in {
      assert(summaryStats.get("totalToll_car") > 5.0)
    }
  }
}
