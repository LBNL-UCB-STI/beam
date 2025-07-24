package beam.router.skim.urbansim

import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.router.skim.ActivitySimPathType.{DRV_COM_WLK, DRV_LOC_WLK, TNC_SINGLE, WLK_LOC_WLK, WLK_LRF_WLK}
import beam.router.skim.ActivitySimSkimmer.ExcerptData
import com.bc.zarr.ZarrGroup
import com.bc.zarr.storage.FileSystemStore
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.Paths
import scala.collection.immutable.SortedSet

class ActivitySimZarrWriterSpec extends AnyWordSpecLike with Matchers {
  "ActivitySimZarrWriter" should {
    "write all activitysim skims to a zarr directory" in {
      val tazMap = TAZTreeMap.getTazTreeMap("test/input/sf-light/taz-centers.csv")
      val geoUnits = tazMap.orderedTazIds
      val excerptData = IndexedSeq(
        ExcerptData(
          "AM",
          DRV_COM_WLK,
          "",
          "100827",
          "100413",
          100,
          90,
          80,
          70,
          60,
          50,
          40,
          30,
          20,
          10,
          5,
          4,
          3,
          2,
          1,
          1,
          0
        ),
        ExcerptData(
          "AM",
          DRV_COM_WLK,
          "None",
          "100413",
          "100827",
          120,
          190,
          180,
          170,
          160,
          150,
          140,
          130,
          120,
          110,
          105,
          104,
          103,
          102,
          101,
          101,
          100
        )
        // Add more ExcerptData as needed for coverage
      )
      val path = "output/test/activitysim_skims.zarr"
      ActivitySimZarrWriter.writeToZarr(path, excerptData.iterator, geoUnits)
      val store = new FileSystemStore(Paths.get(path))
      val rootGroup = ZarrGroup.open(store)
      val arrayNames = rootGroup.getArrayKeys
      arrayNames should not be empty
      // Example: check that expected arrays exist
      arrayNames should contain("DRV_COM_WLK_TOTIVT")
      // Optionally, check attributes and data shape
      val arr = rootGroup.openArray("DRV_COM_WLK_TOTIVT")
      arr.getShape shouldEqual Array(geoUnits.size, geoUnits.size, 5) // 5 time bins
      val attrs = arr.getAttributes
      attrs.get("mode") shouldBe "DRV_COM_WLK"
      attrs.get("measure") shouldBe "TOTIVT"
    }
  }
}
