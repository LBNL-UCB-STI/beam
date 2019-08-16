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

    vehicleTypes = beamServices.beamScenario.vehicleTypes.keySet
    runAnalysis(new VehicleMilesTraveledAnalysis(vehicleTypes))
  }

  "Vehicle miles traveled analyser " must {

    "calculate total vehicle traveled by vehicle type " ignore {
      vehicleTypes.foreach(
        t =>
          withClue(s"vehicle type $t travels zero miles. ") {
            summaryStats.get(s"motorizedVehicleMilesTraveled_$t") should not be 0
        }
      )
    }

    "calculate total vehicle traveled " in {

      val defaultHumanBodyBeamVehicleTypeId = Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType])

      import scala.collection.JavaConverters._
      summaryStats.asScala
        .filterNot(_._1.equalsIgnoreCase("motorizedVehicleMilesTraveled_total"))
        .filterNot(
          _._1.equalsIgnoreCase(s"motorizedVehicleMilesTraveled_$defaultHumanBodyBeamVehicleTypeId")
        )
        .filterNot(_._1.startsWith("vehicleMilesTraveled_"))
        .values
        .map(_.doubleValue())
        .sum
        .round shouldBe
      summaryStats.get("motorizedVehicleMilesTraveled_total").doubleValue().round
    }
  }
}
