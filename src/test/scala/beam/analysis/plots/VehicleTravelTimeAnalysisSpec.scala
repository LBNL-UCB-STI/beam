package beam.analysis.plots

import beam.analysis.summary.VehicleTravelTimeAnalysis
import org.scalatest.matchers.should.Matchers

class VehicleTravelTimeAnalysisSpec extends GenericAnalysisSpec with Matchers {

  override def beforeAll(): Unit = {
    super.beforeAll()

    val vehicleTypes = beamServices.beamScenario.vehicleTypes.keySet
    runAnalysis(new VehicleTravelTimeAnalysis(scenario, beamServices.networkHelper, vehicleTypes))
  }

  "Vehicle travel time analyser " must {
    "calculate vehicle hours traveled by mode " in {
      summaryStats.get("vehicleHoursTraveled_walk") should not be 0
      summaryStats.get("vehicleHoursTraveled_car") should not be 0
      summaryStats.get("vehicleHoursTraveled_bus") should not be 0
      summaryStats.get("vehicleHoursTraveled_subway") should not be 0
    }

    "calculate average vehicle delay by activity " in {
      summaryStats.get("averageVehicleDelayPerMotorizedLeg_home") should not be 0
      summaryStats.get("averageVehicleDelayPerMotorizedLeg_work") should not be 0
      summaryStats.get("averageVehicleDelayPerMotorizedLeg_secondary") should not be 0
    }

    "calculate total vehicle delay " in {
      summaryStats.get("totalHoursOfVehicleTrafficDelay") should not be 0
    }
  }
}
