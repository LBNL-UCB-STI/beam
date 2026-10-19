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

    "extract and write skims across thousands of OD pairs in milliseconds without calling currentSkimSnapshot per OD pair" in {
      import beam.router.skim.{ActivitySimSkimmer, GeoUnit}
      import beam.router.skim.ActivitySimSkimmer.{ActivitySimSkimmerInternal, ActivitySimSkimmerKey}
      import beam.router.skim.core.{AbstractSkimmerInternal, AbstractSkimmerKey}
      import beam.sim.BeamScenario
      import beam.sim.BeamServices
      import beam.sim.config.BeamConfig
      import beam.utils.TestConfigUtils.testConfig
      import org.matsim.api.core.v01.Coord
      import org.matsim.core.controler.{MatsimServices, OutputDirectoryHierarchy}
      import org.mockito.Mockito
      import org.mockito.Mockito.when
      import scripts.FullSkimsCreatorApp
      import scripts.FullSkimsCreatorApp.ODRow

      val beamConfig = BeamConfig(testConfig("test/input/beamville/beam.conf").resolve())
      val matsimServices = Mockito.mock(classOf[MatsimServices])
      val controlerIO = Mockito.mock(classOf[OutputDirectoryHierarchy])
      when(matsimServices.getControlerIO).thenReturn(controlerIO)
      val beamScenario = Mockito.mock(classOf[BeamScenario])
      val beamServices = Mockito.mock(classOf[BeamServices])
      when(beamServices.beamConfig).thenReturn(beamConfig)
      when(beamServices.beamScenario).thenReturn(beamScenario)
      when(beamServices.matsimServices).thenReturn(matsimServices)

      val numZones = 50
      val zoneIds = (1 to numZones).map(_.toString)
      val tazTreeMap = Mockito.mock(classOf[TAZTreeMap])
      when(tazTreeMap.orderedTazIds).thenReturn(zoneIds)
      when(beamScenario.tazTreeMapForASimSkimmer).thenReturn(tazTreeMap)

      val geoUnits = zoneIds.map(id => GeoUnit.TAZ(id, new Coord(550000.0, 4180000.0), 1000.0)).toVector
      val odRows = for {
        o <- geoUnits
        d <- geoUnits
      } yield ODRow(o, d)
      odRows.size shouldBe 2500

      var snapshotCalls = 0
      val skimmer: ActivitySimSkimmer = new ActivitySimSkimmer(matsimServices, beamScenario, beamConfig) {
        override protected[skim] def currentSkimSnapshot: Map[AbstractSkimmerKey, AbstractSkimmerInternal] = {
          snapshotCalls += 1
          super.currentSkimSnapshot
        }

        private val sampleInternal = ActivitySimSkimmerInternal(
          travelTimeInMinutes = 15.0,
          generalizedTimeInMinutes = 15.0,
          generalizedCost = 5.0,
          distanceInMeters = 10000.0,
          cost = 2.5,
          energy = 0.0,
          walkAccessInMinutes = 2.0,
          walkEgressInMinutes = 2.0,
          walkAuxiliaryInMinutes = 0.0,
          waitInitialInMinutes = 3.0,
          waitAuxiliaryInMinutes = 0.0,
          totalInVehicleTimeInMinutes = 8.0,
          driveTimeInMinutes = 0.0,
          driveDistanceInMeters = 0.0,
          keyInVehicleTimeInMinutes = 8.0,
          ferryInVehicleTimeInMinutes = 0.0,
          transitBoardingsCount = 1.0,
          failedTrips = 0,
          observations = 1,
          iterations = 1
        )
        for (i <- 1 to 10000) {
          val o = ((i % numZones) + 1).toString
          val d = (((i * 7) % numZones) + 1).toString
          currentSkimInternal.put(ActivitySimSkimmerKey(8, WLK_LOC_WLK, o, d), sampleInternal)
        }
      }

      val startNs = System.nanoTime()
      val extracted = odRows.flatMap(row => skimmer.getExcerptDataForOD(row.origin, row.destination))
      val elapsedMs = (System.nanoTime() - startNs) / 1000000

      snapshotCalls shouldBe 0
      extracted should not be empty
      elapsedMs should be < 5000L

      val zarrSkimmer = FullSkimsCreatorApp.createActivitySimZarrSkimmer(beamServices, odRows, Map.empty, None)
      val zarrOutPath = "output/test/perf_zarr_skimmer_test.zarr"
      new java.io.File("output/test").mkdirs()
      val startWriteNs = System.nanoTime()
      zarrSkimmer.writeToDisk(zarrOutPath)
      val elapsedWriteMs = (System.nanoTime() - startWriteNs) / 1000000
      elapsedWriteMs should be < 5000L
    }
  }
}
