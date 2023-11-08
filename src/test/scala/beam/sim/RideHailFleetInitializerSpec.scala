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

  "ShpGeofence" should {
    "check if a point is inside it" in {
      val shpGeofence = ShpGeofence("test/input/sf-light/geofence/multiple-areas.shp", "epsg:26910")
      shpGeofence.contains(549612.49290970, 4183523.42542734) shouldBe true
      shpGeofence.contains(555302.70929476, 4175585.53179180) shouldBe true
      shpGeofence.contains(546395.55706793, 4175585.53179180) shouldBe true
      shpGeofence.contains(546362.13435789, 4174925.43326842) shouldBe false
      shpGeofence.contains(553105.16610934, 4178585.22001828) shouldBe false
      shpGeofence.contains(549915.40305993, 4179989.21944668) shouldBe false
    }
    "load shp file in any projection" in {
      val shpGeofence2 = ShpGeofence("test/test-resources/geofence/sfbay-geofence-1.shp", "epsg:26910")
      shpGeofence2.contains(593035.17647173, 4140765.32228349) shouldBe true
      shpGeofence2.contains(594867.66445433, 4138732.92652097) shouldBe true
      shpGeofence2.contains(593734.85370145, 4138699.60855765) shouldBe false
      shpGeofence2.contains(574572, 4144615) shouldBe false
      shpGeofence2.contains(593168.44832501, 4142264.63063289) shouldBe false
    }
  }
}
