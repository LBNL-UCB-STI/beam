package beam.router.skim.urbansim

import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.router.skim.ActivitySimPathType.{DRV_COM_WLK, WLK_LOC_WLK}
import beam.router.skim.ActivitySimSkimmer.ExcerptData
import com.bc.zarr.ZarrGroup
import com.bc.zarr.storage.FileSystemStore
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.Paths
import scala.jdk.CollectionConverters._

class ActivitySimZarrWriterSpec extends AnyWordSpecLike with Matchers {
  "ActivitySimZarrWriter" should {
    "write all activitysim skims to a zarr directory" in {
      val tazMap = TAZTreeMap("test/input/sf-light/taz-centers.csv", scenarioCRS = "epsg:26910")
      val geoUnits = tazMap.orderedTazIds
      val TOTIVT_test = math.Pi // Choose an arbitrary non-integer value for testing
      val excerptData = IndexedSeq(
        ExcerptData(
          "AM",
          DRV_COM_WLK,
          "",
          "100827",
          "100413",
          100,
          TOTIVT_test,
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
        ),
        ExcerptData(
          "AM",
          WLK_LOC_WLK,
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
      val timePeriods = attrs.get("timePeriods").asInstanceOf[java.util.List[String]].asScala
      val timePeriodIdx = timePeriods.indexOf("AM")
      val originIdx = geoUnits.indexOf("100827")
      val destIdx = geoUnits.indexOf("100413")
      val indexToLookUp = Array[Int](originIdx, destIdx, timePeriodIdx)
      val data = arr.read(Array[Int](1, 1, 1), indexToLookUp)
      val outcome = data match {
        case floatArray: Array[Float] => floatArray(0)
        case other                    =>
          // If it's not a float array directly, it might be wrapped
          val floatArray = other.asInstanceOf[Array[_]].map(_.asInstanceOf[Float])
          floatArray(0)
      }
      outcome should be(TOTIVT_test.toFloat +- 1e-6f) // Account for double -> float conversion
    }

    "include WALK with TIME and DIST in ActivitySimZarrWriter matrix data" in {
      val walkMatrixData = ActivitySimZarrWriter.activitySimMatrixData.find(
        _.pathTypes.contains(beam.router.skim.ActivitySimPathType.WALK)
      )
      walkMatrixData should be('defined)
      walkMatrixData.get.metrics should contain allOf (beam.router.skim.ActivitySimMetric.TIME, beam.router.skim.ActivitySimMetric.DIST)
    }

    "include WALK with TIME and DIST in ActivitySimOmxWriter matrix data" in {
      val walkMatrixData =
        ActivitySimOmxWriter.activitySimMatrixData.find(_.pathTypes.contains(beam.router.skim.ActivitySimPathType.WALK))
      walkMatrixData should be('defined)
      walkMatrixData.get.metrics should contain allOf (beam.router.skim.ActivitySimMetric.TIME, beam.router.skim.ActivitySimMetric.DIST)
    }

    "produce byte-identical Zarr stores with provenance metadata and match OMX logical_matrices_sha256" in {
      val smallGeo = Seq("101", "102", "103")
      val excerptData = IndexedSeq(
        ExcerptData(
          "AM",
          DRV_COM_WLK,
          "",
          "101",
          "102",
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
          "PM",
          WLK_LOC_WLK,
          "",
          "102",
          "103",
          120,
          95,
          85,
          75,
          65,
          55,
          45,
          35,
          25,
          15,
          5,
          4,
          3,
          2,
          1,
          1,
          0
        )
      )
      val meta = SkimsProvenanceMetadata(
        skimsKind = "activitySimZarr",
        routerType = "r5+gh",
        skimsGeoType = "taz",
        peakHours = "8.5,17.5",
        modesBuilt = "drive,drive_transit,transit,walk",
        bifurcateTolls = true,
        generateReturnTrips = true,
        tazCount = smallGeo.size
      )
      val zarrPath1 = "output/test/determinism_1.zarr"
      val zarrPath2 = "output/test/determinism_2.zarr"

      ActivitySimZarrWriter.writeToZarr(zarrPath1, excerptData.iterator, smallGeo, Some(meta))
      ActivitySimZarrWriter.writeToZarr(zarrPath2, excerptData.reverse.iterator, smallGeo, Some(meta))

      val root1 = ZarrGroup.open(new FileSystemStore(Paths.get(zarrPath1)))
      val root2 = ZarrGroup.open(new FileSystemStore(Paths.get(zarrPath2)))
      val attrs1 = root1.getAttributes
      val attrs2 = root2.getAttributes

      attrs1.get("beam_skims_schema_version") shouldBe "1.0"
      attrs1.get("router_type") shouldBe "r5+gh"
      attrs1.get("modes_built") shouldBe "drive,drive_transit,transit,walk"
      attrs1.get("logical_matrices_sha256").toString should startWith("sha256:")
      attrs1.get("logical_matrices_sha256") shouldBe attrs2.get("logical_matrices_sha256")

      // Verify 100% byte-for-byte identical files across both Zarr directory trees
      def listRelativeFiles(rootDir: java.nio.file.Path): Seq[String] = {
        val stream = java.nio.file.Files.walk(rootDir)
        try {
          stream
            .iterator()
            .asScala
            .filter(p => java.nio.file.Files.isRegularFile(p))
            .map(p => rootDir.relativize(p).toString)
            .toSeq
            .sorted
        } finally {
          stream.close()
        }
      }

      val p1 = Paths.get(zarrPath1)
      val p2 = Paths.get(zarrPath2)
      val files1 = listRelativeFiles(p1)
      val files2 = listRelativeFiles(p2)
      files1 should not be empty
      files1 shouldEqual files2
      files1.foreach { rel =>
        java.nio.file.Files.readAllBytes(p1.resolve(rel)) shouldEqual java.nio.file.Files.readAllBytes(p2.resolve(rel))
      }
    }
  }
}
