package beam.utils

import beam.utils.scenario.VehicleInfo
import beam.utils.scenario.urbansim.GenericRecordMock
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.JavaConverters._

class BeamVehicleUtilsSpec extends AnyWordSpecLike with Matchers {

  "BeamVehicleUtils" should {
    "map parquet vehicle rows to VehicleInfo" in {
      val record = new GenericRecordMock(
        Map(
          "vehicleId"     -> "veh-1".asInstanceOf[AnyRef],
          "vehicleTypeId" -> "sedan".asInstanceOf[AnyRef],
          "stateOfCharge" -> 0.75.asInstanceOf[AnyRef],
          "householdId"   -> "hh-1".asInstanceOf[AnyRef]
        ).asJava
      )

      BeamVehicleUtils.toVehicleInfo(record) shouldBe VehicleInfo(
        vehicleId = "veh-1",
        vehicleTypeId = "sedan",
        initialSoc = Some(0.75),
        householdId = "hh-1"
      )
    }

    "support parquet rows without state of charge" in {
      val record = new GenericRecordMock(
        Map(
          "vehicleId"     -> "veh-2".asInstanceOf[AnyRef],
          "vehicleTypeId" -> "suv".asInstanceOf[AnyRef],
          "stateOfCharge" -> null.asInstanceOf[AnyRef],
          "householdId"   -> "hh-2".asInstanceOf[AnyRef]
        ).asJava
      )

      BeamVehicleUtils.toVehicleInfo(record) shouldBe VehicleInfo(
        vehicleId = "veh-2",
        vehicleTypeId = "suv",
        initialSoc = None,
        householdId = "hh-2"
      )
    }
  }
}
