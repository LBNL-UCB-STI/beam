package beam.utils.scenario.urbansim

import beam.utils.scenario.urbansim.DataExchange._
import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.JavaConverters._

class GenericRecordMock(val map: java.util.Map[String, AnyRef]) extends GenericRecord {
  override def put(key: String, v: Any): Unit = ???
  override def get(key: String): AnyRef = map.get(key)
  override def put(i: Int, v: Any): Unit = ???
  override def get(i: Int): AnyRef = ???
  override def getSchema: Schema = ???
}

class ParquetScenarioReaderTest extends AnyWordSpec with Matchers {
  "A ParquetScenarioReader" should {
    "get the value by key returns value" when {
      "it is not null" in {
        val gr = new GenericRecordMock(Map("key" -> "value".asInstanceOf[AnyRef]).asJava)
        ParquetScenarioReader.getIfNotNull(gr, "key") should be("value")
      }
    }
    "get the value by key throws an exception" when {
      "it is not null" in {
        val gr = new GenericRecordMock(Map("key" -> null.asInstanceOf[AnyRef]).asJava)
        the[java.lang.AssertionError] thrownBy {
          ParquetScenarioReader.getIfNotNull(gr, "key")
        } should have message "assertion failed: Value in column 'key' is null"
      }
    }
    "be able to create UnitInfo from GenericRecord" in {
      val gr = new GenericRecordMock(
        Map("unit_id" -> "1".asInstanceOf[AnyRef], "building_id" -> "2".asInstanceOf[AnyRef]).asJava
      )
      ParquetScenarioReader.toUnitInfo(gr) should be(UnitInfo(unitId = "1", buildingId = "2"))
    }

    "be able to create ParcelAttribute from GenericRecord" in {
      val gr = new GenericRecordMock(
        Map(
          "parcel_id" -> "1".asInstanceOf[AnyRef],
          "x"         -> 1.0.asInstanceOf[AnyRef],
          "y"         -> 2.0.asInstanceOf[AnyRef]
        ).asJava
      )
      ParquetScenarioReader.toParcelAttribute(gr) should be(ParcelAttribute(primaryId = "1", x = 1.0, y = 2.0))
    }

    "be able to create BuildingInfo from GenericRecord" in {
      val gr = new GenericRecordMock(
        Map("building_id" -> "1".asInstanceOf[AnyRef], "parcel_id" -> "2".asInstanceOf[AnyRef]).asJava
      )
      ParquetScenarioReader.toBuildingInfo(gr) should be(BuildingInfo(buildingId = "1", parcelId = "2"))
    }

    "be able to create PersonInfo from GenericRecord" in {
      val gr = new GenericRecordMock(
        Map(
          "person_id"    -> "1".asInstanceOf[AnyRef],
          "household_id" -> "2".asInstanceOf[AnyRef],
          "age"          -> 3L.asInstanceOf[AnyRef]
        ).asJava
      )
      ParquetScenarioReader.toPersonInfo(gr) should be(
        PersonInfo(
          personId = "1",
          householdId = "2",
          age = 3,
          rank = 0,
          excludedModes = "",
          isFemale = false,
          valueOfTime = 0
        )
      )
    }

    "be able to create PlanInfo from GenericRecord" in {
      val gr = new GenericRecordMock(
        Map(
          "tripId"           -> "1".asInstanceOf[AnyRef],
          "personId"         -> "1".asInstanceOf[AnyRef],
          "planElement"      -> "leg".asInstanceOf[AnyRef],
          "planElementIndex" -> 1L.asInstanceOf[AnyRef],
          "x"                -> 2.0.asInstanceOf[AnyRef],
          "y"                -> 3.0.asInstanceOf[AnyRef],
          "endTime"          -> 4.0.asInstanceOf[AnyRef],
          "mode"             -> "mode".asInstanceOf[AnyRef]
        ).asJava
      )
      ParquetScenarioReader.toPlanInfo(gr) should be(
        PlanElement(
          tripId = "1",
          personId = "1",
          planElement = "leg",
          planElementIndex = 1,
          activityType = None,
          x = Some(2.0),
          y = Some(3.0),
          endTime = Some(4.0),
          mode = Some("mode")
        )
      )
    }

    "be able to create HouseholdInfo from GenericRecord" in {
      val gr = new GenericRecordMock(
        Map(
          "household_id" -> "1".asInstanceOf[AnyRef],
          "cars"         -> 1.0.asInstanceOf[AnyRef],
          "unit_id"      -> "2".asInstanceOf[AnyRef],
          "building_id"  -> "3".asInstanceOf[AnyRef],
          "income"       -> 4.0.asInstanceOf[AnyRef]
        ).asJava
      )
      ParquetScenarioReader.toHouseholdInfo(gr) should be(
        HouseholdInfo(householdId = "1", cars = 1, income = 4.0, unitId = "2", buildingId = "3")
      )
    }
  }
}
