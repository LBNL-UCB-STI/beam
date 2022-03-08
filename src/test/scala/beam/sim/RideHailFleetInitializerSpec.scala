package beam.sim

import java.io.File
import java.nio.file.Files

import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers

import scala.util.Try
import scala.collection.JavaConverters._

//#Test needs to be updated/fixed on LBNL side
class RideHailFleetInitializerSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll {
  val filePath: String = File.createTempFile("0.rideHailFleet", ".csv.gz").getAbsolutePath

  override def afterAll(): Unit = {
    Try {
      Files.delete(new File(filePath).toPath)
    }
    super.afterAll()
  }

  "RideHailFleetInitializer" should {
    "be able to write fleet data to CSV and read it back" in {
      /*      val data1 = RideHailFleetInitializer.RideHailAgentInputData(
        "1",
        "2",
        "CAR",
        1.0,
        2.0,
        Some("{1:2}"),
        Some(3.0),
        Some(4.0),
        Some(5.0)
      )
      val data2 = RideHailFleetInitializer.RideHailAgentInputData(
        "2",
        "2",
        "CAV",
        6.0,
        7.0,
        None,
        None,
        None,
        None
      )
      val expectedFleetData = Seq(data1, data2)

      RideHailFleetInitializer.writeFleetData(filePath, expectedFleetData)

      val readFleetData = RideHailFleetInitializer.readFleetFromCSV(filePath)
      readFleetData shouldBe expectedFleetData*/
    }

    "be able to create RideHailAgentInputData from the map" in {
      /*   val map = Map[String, String](
        "id"                -> "1",
        "rideHailManagerId" -> "2",
        "vehicleType"       -> "CAR",
        "initialLocationX"  -> "1.0",
        "initialLocationY"  -> "2.0",
        "shifts"            -> "{1:2}",
        "geofenceX"         -> null, // Yes, non-exist value of column is `null`
        "geofenceY"         -> null,
        "geofenceRadius"    -> null
      )

      val data = RideHailFleetInitializer.toRideHailAgentInputData(map.asJava)
      data shouldBe RideHailFleetInitializer.RideHailAgentInputData(
        "1",
        "2",
        "CAR",
        1.0,
        2.0,
        Some("{1:2}"),
        None,
        None,
        None
      )*/
    }

  }

  "Geofence" can {
    "check is point inside it" in {
      CircularGeofence(0, 0, 5).contains(1, 1) shouldBe true
      CircularGeofence(0, 0, 5).contains(0, 0) shouldBe true
      CircularGeofence(0, 0, 5).contains(2, 2) shouldBe true
      CircularGeofence(0, 0, 5).contains(5, 0) shouldBe true
      CircularGeofence(0, 0, 5).contains(0, 5) shouldBe true
      CircularGeofence(0, 0, 5).contains(1, 5) shouldBe false
      CircularGeofence(0, 0, 5).contains(5, 1) shouldBe false
    }
  }
}
