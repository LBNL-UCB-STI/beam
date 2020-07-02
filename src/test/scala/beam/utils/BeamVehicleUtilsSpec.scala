package beam.utils

import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, FuelType}
import org.matsim.api.core.v01.Id
import org.mockito.Mockito.when
import org.scalatest.{Inside, Matchers, WordSpecLike}
import org.scalatestplus.mockito.MockitoSugar

class BeamVehicleUtilsSpec extends WordSpecLike with MockitoSugar with Matchers with Inside {

  private val vehiclesFile = getClass.getResource("/input/vehicles.csv").getPath
  private val vehicleTypesFile = getClass.getResource("/input/vehicleTypes.csv").getPath
  private val fuelTypesFile = getClass.getResource("/input/fuelTypes.csv").getPath

  "BeamVehicleUtils" must {
    "read vehicles.csv file to a map of (Id -> BeamVehicle)" in {
      val mockVehiclesTypeMap = List(
        mockVehicleType("Bicycle", 10.0),
        mockVehicleType("Car", 3655.98),
        mockVehicleType("VAN", 122303.54),
        mockVehicleType("CAV", 3655.98),
      ).map(v => (v.id -> v)).toMap

      val res = BeamVehicleUtils.readVehiclesFile(vehiclesFile, mockVehiclesTypeMap, 123L)

      res should have size 4
      res(vehicleId("1")).beamVehicleType.id shouldBe vehicleTypeId("Bicycle")
      res(vehicleId("2")).beamVehicleType.id shouldBe vehicleTypeId("Car")
      res(vehicleId("3")).beamVehicleType.id shouldBe vehicleTypeId("VAN")
      res(vehicleId("4")).beamVehicleType.id shouldBe vehicleTypeId("CAV")
    }

    "read vehicleTypes.csv file to a map of (Id -> BeamVehicleType)" in {
      val res = BeamVehicleUtils.readBeamVehicleTypeFile(vehicleTypesFile)

      res should have size 4
      inside(res(vehicleTypeId("beamVilleCar"))) {
        case vehicleType =>
          vehicleType.seatingCapacity shouldBe 4
          vehicleType.lengthInMeter shouldBe 4.5
          vehicleType.primaryFuelType shouldBe FuelType.Gasoline
          vehicleType.primaryFuelConsumptionInJoulePerMeter shouldBe 3655.98
      }
      inside(res(vehicleTypeId("Bicycle"))) {
        case vehicleType =>
          vehicleType.seatingCapacity shouldBe 1
          vehicleType.lengthInMeter shouldBe 1.5
          vehicleType.primaryFuelType shouldBe FuelType.Food
          vehicleType.primaryFuelConsumptionInJoulePerMeter shouldBe 10.0
      }
    }

    "read fuelTypes.csv file to a map of (FuelType -> Double)" in {
      val res = BeamVehicleUtils.readFuelTypeFile(fuelTypesFile)

      res should have size 3
      res(FuelType.Gasoline) shouldBe 0.03
      res(FuelType.Electricity) shouldBe 0.01
      res(FuelType.Food) shouldBe 0.0
    }
  }

  private def vehicleId(id: String) = Id.create(id, classOf[BeamVehicle])
  private def vehicleTypeId(id: String) = Id.create(id, classOf[BeamVehicleType])

  private def mockVehicleType(vehicleTypeKey: String, fuelConsumption: Double) = {
    val vehicleType = mock[BeamVehicleType]
    val vehicleTypeId = Id.create(vehicleTypeKey, classOf[BeamVehicleType])

    when(vehicleType.id).thenReturn(vehicleTypeId)
    when(vehicleType.primaryFuelConsumptionInJoulePerMeter).thenReturn(fuelConsumption)
    when(vehicleType.secondaryFuelCapacityInJoule).thenReturn(None)

    vehicleType
  }
}
