package beam.utils

import beam.utils.scenario.VehicleInfo
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.JavaConverters._

class BeamVehicleUtilsSpec extends AnyWordSpecLike with Matchers {

  "BeamVehicleUtils" should {
    "map parquet vehicle rows to VehicleInfo" in {
      val record = recordOf(
        "vehicleId"     -> "veh-1",
        "vehicleTypeId" -> "sedan",
        "stateOfCharge" -> 0.75,
        "householdId"   -> "hh-1"
      )

      BeamVehicleUtils.toVehicleInfo(record) shouldBe VehicleInfo(
        vehicleId = "veh-1",
        vehicleTypeId = "sedan",
        initialSoc = Some(0.75),
        householdId = "hh-1"
      )
    }

    "support parquet rows without state of charge" in {
      val record = recordOf(
        "vehicleId"     -> "veh-2",
        "vehicleTypeId" -> "suv",
        "stateOfCharge" -> null,
        "householdId"   -> "hh-2"
      )

      BeamVehicleUtils.toVehicleInfo(record) shouldBe VehicleInfo(
        vehicleId = "veh-2",
        vehicleTypeId = "suv",
        initialSoc = None,
        householdId = "hh-2"
      )
    }

    "support parquet rows with snake_case ids" in {
      val record = recordOf(
        "vehicle_id"    -> "veh-3",
        "vehicleTypeId" -> "van",
        "stateOfCharge" -> 0.5,
        "household_id"  -> "hh-3"
      )

      BeamVehicleUtils.toVehicleInfo(record) shouldBe VehicleInfo(
        vehicleId = "veh-3",
        vehicleTypeId = "van",
        initialSoc = Some(0.5),
        householdId = "hh-3"
      )
    }
  }

  private def recordOf(fields: (String, Any)*): GenericData.Record = {
    val schemaFields = fields.map { case (name, value) =>
      val schema = value match {
        case _: java.lang.Double | _: Double =>
          Schema.createUnion(java.util.Arrays.asList(Schema.create(Schema.Type.NULL), Schema.create(Schema.Type.DOUBLE)))
        case _ =>
          Schema.createUnion(java.util.Arrays.asList(Schema.create(Schema.Type.NULL), Schema.create(Schema.Type.STRING)))
      }
      new Schema.Field(name, schema, null, Schema.Field.NULL_DEFAULT_VALUE)
    }
    val schema = Schema.createRecord("VehicleRecord", null, "beam.utils", false, schemaFields.toList.asJava)
    val record = new GenericData.Record(schema)
    fields.foreach { case (name, value) => record.put(name, value.asInstanceOf[AnyRef]) }
    record
  }
}
