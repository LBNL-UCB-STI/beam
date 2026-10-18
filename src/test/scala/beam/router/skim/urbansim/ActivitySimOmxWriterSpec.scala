package beam.router.skim.urbansim

import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.router.skim.ActivitySimPathType.{DRV_COM_WLK, TNC_SINGLE, WLK_LOC_WLK}
import beam.router.skim.ActivitySimSkimmer.ExcerptData
import omx.OmxFile
import omx.OmxMatrix.OmxFloatMatrix
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.jdk.CollectionConverters.asScalaSetConverter

/**
  * @author Dmitry Openkov
  */
class ActivitySimOmxWriterSpec extends AnyWordSpecLike with Matchers {
  "ActivitySimOmxWriter" should {

    "write all activitysim skims to an omx file" in {
      val tazMap = TAZTreeMap("test/input/sf-light/taz-centers.csv", scenarioCRS = "epsg:26910")
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
          50,
          40,
          1,
          2,
          1,
          1,
          0
        ),
        ExcerptData(
          "PM",
          DRV_COM_WLK,
          "",
          "100627",
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
          "MD",
          WLK_LOC_WLK,
          "",
          "100574",
          "10069A",
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
          TNC_SINGLE,
          "Cruise",
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
          TNC_SINGLE,
          "GlobalRHM",
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
        )
      )
      val path = "output/test/activitysim_skims.omx"
      ActivitySimOmxWriter.writeToOmx(path, excerptData.iterator, geoUnits)
      val omxFile = new OmxFile(path)
      omxFile.openReadOnly()
      val matrixNames = omxFile.getMatrixNames.asScala.toList
      matrixNames should contain theSameElementsAs List(
        "DRV_COM_WLK_DDIST__PM",
        "WLK_LOC_WLK_WAUX__MD",
        "DRV_COM_WLK_DDIST__AM",
        "DRV_COM_WLK_FAR__AM",
        "DRV_COM_WLK_KEYIVT__PM",
        "WLK_LOC_WLK_TOTIVT__MD",
        "WLK_LOC_WLK_BOARDS__MD",
        "WLK_LOC_WLK_FAR__MD",
        "DRV_COM_WLK_KEYIVT__AM",
        "DRV_COM_WLK_BOARDS__AM",
        "DRV_COM_WLK_WAUX__PM",
        "DRV_COM_WLK_TOTIVT__PM",
        "DRV_COM_WLK_BOARDS__PM",
        "DRV_COM_WLK_DTIM__PM",
        "DRV_COM_WLK_DTIM__AM",
        "DRV_COM_WLK_TOTIVT__AM",
        "DRV_COM_WLK_WAUX__AM",
        "DRV_COM_WLK_FAR__PM",
        "WLK_LOC_WLK_TRIPS__MD",
        "WLK_LOC_WLK_FAILURES__MD",
        "DRV_COM_WLK_TRIPS__AM",
        "DRV_COM_WLK_FAILURES__AM",
        "DRV_COM_WLK_TRIPS__PM",
        "DRV_COM_WLK_FAILURES__PM",
        "WLK_LOC_WLK_XWAIT__MD",
        "DRV_COM_WLK_XWAIT__AM",
        "DRV_COM_WLK_IWAIT__PM",
        "DRV_COM_WLK_XWAIT__PM",
        "DRV_COM_WLK_IWAIT__AM",
        "WLK_LOC_WLK_IWAIT__MD",
        "TNC_SINGLE_CRUISE_IWAIT__AM",
        "TNC_SINGLE_CRUISE_TOTIVT__AM",
        "TNC_SINGLE_CRUISE_DDIST__AM",
        "TNC_SINGLE_CRUISE_FAR__AM",
        "TNC_SINGLE_CRUISE_FAILURES__AM",
        "TNC_SINGLE_CRUISE_TRIPS__AM",
        "TNC_SINGLE_GLOBALRHM_IWAIT__AM",
        "TNC_SINGLE_GLOBALRHM_TOTIVT__AM",
        "TNC_SINGLE_GLOBALRHM_DDIST__AM",
        "TNC_SINGLE_GLOBALRHM_FAR__AM",
        "TNC_SINGLE_GLOBALRHM_FAILURES__AM",
        "TNC_SINGLE_GLOBALRHM_TRIPS__AM"
      )
      //total in vehicle time data for path type DRV_LOC_WLK and time bin MD
      val matrix = omxFile.getMatrix("WLK_LOC_WLK_TOTIVT__MD").asInstanceOf[OmxFloatMatrix]
      matrix.getShape()(0) shouldBe 864
      matrix.getShape()(1) shouldBe 864
      val mapping = geoUnits.zipWithIndex.toMap
      val originRow = mapping("100574")
      val destinationColum = mapping("10069A")
      matrix.getData()(originRow)(destinationColum) shouldBe 90.0
      omxFile.getLookupNames should contain("zone_number")
      omxFile.close()
    }

    "produce bit-identical logical matrices, lookup/zone_number, and provenance metadata across shuffled runs" in {
      val smallGeo = Seq("101", "102", "103")
      val sampleData = Seq(
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
        skimsKind = "activitySimOmx",
        routerType = "r5+gh",
        skimsGeoType = "taz",
        peakHours = "8.5,17.5",
        modesBuilt = "drive,drive_transit,transit,walk",
        bifurcateTolls = true,
        generateReturnTrips = true,
        tazCount = smallGeo.size
      )
      val path1 = "output/test/determinism_1.omx"
      val path2 = "output/test/determinism_2.omx"
      val zarrPath = "output/test/determinism_omx_cross.zarr"
      new java.io.File("output/test").mkdirs()

      ActivitySimOmxWriter.writeToOmx(path1, sampleData.iterator, smallGeo, Some(meta))
      ActivitySimOmxWriter.writeToOmx(path2, sampleData.reverse.iterator, smallGeo, Some(meta))
      ActivitySimZarrWriter.writeToZarr(
        zarrPath,
        sampleData.iterator,
        smallGeo,
        Some(meta.copy(skimsKind = "activitySimZarr"))
      )

      val omx1 = new OmxFile(path1)
      val omx2 = new OmxFile(path2)
      omx1.openReadOnly()
      omx2.openReadOnly()
      try {
        // Verify /lookup/zone_number
        omx1.getLookupNames should contain("zone_number")
        omx2.getLookupNames should contain("zone_number")
        val lookup1 = omx1.getLookup("zone_number").getLookup.asInstanceOf[Array[Int]]
        val lookup2 = omx2.getLookup("zone_number").getLookup.asInstanceOf[Array[Int]]
        lookup1 shouldEqual Array(101, 102, 103)
        lookup2 shouldEqual Array(101, 102, 103)

        // Verify root provenance attributes and cross-format logical_matrices_sha256 parity
        omx1.getAttribute("beam_skims_schema_version") shouldBe "1.0"
        omx1.getAttribute("skims_kind") shouldBe "activitySimOmx"
        omx1.getAttribute("router_type") shouldBe "r5+gh"
        omx1.getAttribute("skims_geo_type") shouldBe "taz"
        omx1.getAttribute("peak_hours") shouldBe "8.5,17.5"
        omx1.getAttribute("modes_built") shouldBe "drive,drive_transit,transit,walk"
        omx1.getAttribute("bifurcate_tolls") shouldBe "true"
        omx1.getAttribute("generate_return_trips") shouldBe "true"
        omx1.getAttribute("taz_count") shouldBe 3

        val sha1 = omx1.getAttribute("logical_matrices_sha256").toString
        val sha2 = omx2.getAttribute("logical_matrices_sha256").toString
        sha1 should startWith("sha256:")
        sha1 shouldBe sha2

        val zarrRoot =
          com.bc.zarr.ZarrGroup.open(new com.bc.zarr.storage.FileSystemStore(java.nio.file.Paths.get(zarrPath)))
        zarrRoot.getAttributes.get("logical_matrices_sha256") shouldBe sha1

        // Verify every matrix dataset and attribute matches bit-for-bit across the two OMX files
        val names1 = omx1.getMatrixNames
        val names2 = omx2.getMatrixNames
        names1 shouldEqual names2
        names1.forEach { name =>
          val m1 = omx1.getMatrix(name).asInstanceOf[OmxFloatMatrix]
          val m2 = omx2.getMatrix(name).asInstanceOf[OmxFloatMatrix]
          m1.getAttributeKeys shouldEqual m2.getAttributeKeys
          m1.getAttributeKeys.forEach { attrKey =>
            m1.getAttribute(attrKey) shouldEqual m2.getAttribute(attrKey)
          }
          val d1 = m1.getData
          val d2 = m2.getData
          d1.length shouldBe d2.length
          for (r <- d1.indices; c <- d1(r).indices) {
            java.lang.Float.floatToRawIntBits(d1(r)(c)) shouldBe java.lang.Float.floatToRawIntBits(d2(r)(c))
          }
        }
      } finally {
        omx1.close()
        omx2.close()
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

      // Also verify FullSkimsCreatorApp.createActivitySimOmxSkimmer.writeToDisk
      val omxSkimmer = FullSkimsCreatorApp.createActivitySimOmxSkimmer(beamServices, odRows, Map.empty, None)
      val omxOutPath = "output/test/perf_omx_skimmer_test.omx"
      new java.io.File("output/test").mkdirs()
      val startWriteNs = System.nanoTime()
      omxSkimmer.writeToDisk(omxOutPath)
      val elapsedWriteMs = (System.nanoTime() - startWriteNs) / 1000000
      elapsedWriteMs should be < 5000L
    }
  }
}
