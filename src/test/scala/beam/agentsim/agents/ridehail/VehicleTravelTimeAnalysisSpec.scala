package beam.agentsim.agents.ridehail

import beam.analysis.summary.VehicleTravelTimeAnalysis
import org.scalatest.Matchers

class VehicleTravelTimeAnalysisSpec extends GenericAnalysisSpec with Matchers {


  override def beforeAll(): Unit = {
    super.beforeAll()

    val vehicleTypes = beamServices.vehicleTypes.keySet
    runAnalysis(new VehicleTravelTimeAnalysis(scenario, vehicleTypes))
  }

  "Vehicle travel time analyser " must {
    "calculate vehicle hours traveled by mode " in {
      assert(summaryStats.get("vehicleHoursTraveled_walk") >  36.0)
      assert(summaryStats.get("vehicleHoursTraveled_car") >  1.0)
      assert(summaryStats.get("vehicleHoursTraveled_bus") >  3.0)
      assert(summaryStats.get("vehicleHoursTraveled_subway") >  0.0)
    }

    "calculate average vehicle delay by activity " in {
      assert(summaryStats.get("averageVehicleDelayPerMotorizedLeg_home") >  106.0)
      assert(summaryStats.get("averageVehicleDelayPerMotorizedLeg_work") >  128.0)
      assert(summaryStats.get("averageVehicleDelayPerMotorizedLeg_secondary") >  128.0)
    }

    "calculate total vehicle delay " in {
      assert(summaryStats.get("totalHoursOfVehicleTrafficDelay") >  5.0)
    }
  }
}
