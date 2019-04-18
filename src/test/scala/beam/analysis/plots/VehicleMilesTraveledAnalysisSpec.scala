package beam.analysis.plots

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.analysis.summary.VehicleMilesTraveledAnalysis
import org.matsim.api.core.v01.Id
import org.scalatest.Matchers

import scala.collection.Set

class VehicleMilesTraveledAnalysisSpec extends GenericAnalysisSpec with Matchers {
  var vehicleTypes: Set[Id[BeamVehicleType]] = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    vehicleTypes = beamServices.vehicleTypes.keySet
    runAnalysis(new VehicleMilesTraveledAnalysis(vehicleTypes))
  }

  "Vehicle miles traveled analyser " must {

    "calculate total vehicle traveled by vehicle type " in {
      vehicleTypes.foreach(t => summaryStats.get(s"motorizedVehicleMilesTraveled_$t") should not be 0)
    }

    "calculate total vehicle traveled " in {

      import scala.collection.JavaConverters._
      summaryStats.asScala.filterNot(_._1.equalsIgnoreCase("motorizedVehicleMilesTraveled_total"))
        .filterNot(_._1.equalsIgnoreCase(s"motorizedVehicleMilesTraveled_${BeamVehicleType.defaultHumanBodyBeamVehicleType.id}"))
        .filterNot(_._1.startsWith("vehicleMilesTraveled_")).values.map(_.doubleValue()).sum.round shouldBe
        summaryStats.get("motorizedVehicleMilesTraveled_total").doubleValue().round
    }
  }
}
