package beam.utils.scenario.urbansim.censusblock

import beam.sim.common.GeoUtils
import beam.utils.TestConfigUtils
import beam.utils.scenario.InputType
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path, Paths}

class UrbansimReaderV2Test extends AnyWordSpec with Matchers {
  private val testDataPath = "test/test-resources/urbansim_v2"

  private val testGeoUtils = new GeoUtils {
    override def wgs2Utm(coord: org.matsim.api.core.v01.Coord): org.matsim.api.core.v01.Coord = coord
    override def localCRS: String = "epsg:26910" // Default UTM Zone 10N (San Francisco)
  }

  "UrbansimReaderV2" should {
    "read CSV files successfully" in {
      // Create test data paths
      val csvPath = s"$testDataPath/csv"

      // Ensure test data exists
      val requiredFiles = List(
        s"$csvPath/households.csv.gz",
        s"$csvPath/persons.csv.gz",
        s"$csvPath/plans.csv.gz",
        s"$csvPath/blocks.csv.gz"
      )

      // Skip test if test data is not available
      assume(
        requiredFiles.forall(path => Files.exists(Paths.get(path))),
        "Test data files not found. Please provide CSV test data."
      )

      val modeMap = Map("WALK" -> "walk", "CAR" -> "car", "TRANSIT" -> "transit")

      val reader = new UrbansimReaderV2(
        inputPersonPath = s"$csvPath/persons.csv.gz",
        inputPlanPath = s"$csvPath/plans.csv.gz",
        inputHouseholdPath = s"$csvPath/households.csv.gz",
        inputVehiclePath = s"$csvPath/vehicles.csv.gz",
        inputBlockPath = s"$csvPath/blocks.csv.gz",
        geoUtils = testGeoUtils,
        shouldConvertWgs2Utm = false,
        modeMap = modeMap,
        fileFormat = "csv"
      )

      // Test reading persons
      val persons = reader.getPersons.toSeq
      persons should not be empty

      // Test reading plans
      val plans = reader.getPlans.toSeq
      plans should not be empty
      plans.foreach { p =>
        p.planIndex shouldBe 0
        p.planScore shouldBe 0.0
        p.planSelected shouldBe true
      }

      // Test reading households
      val households = reader.getHousehold.toSeq
      households should not be empty

      // Test reading vehicles (optional)
      val vehicles = reader.getVehicles.toSeq
      vehicles should have size 2
      vehicles.map(_.vehicleId) should contain theSameElementsInOrderAs Seq("veh-csv-1", "veh-csv-2")
      vehicles.map(_.householdId) should contain theSameElementsInOrderAs Seq("226798", "580065")
      vehicles.map(_.initialSoc) should contain theSameElementsInOrderAs Seq(Some(0.75), None)
    }

    "read Parquet files successfully when all required files exist" in {
      // Create test data paths
      val parquetPath = s"$testDataPath/parquet"

      // Ensure test data exists
      val requiredFiles = List(
        s"$parquetPath/households.parquet",
        s"$parquetPath/persons.parquet",
        s"$parquetPath/plans.parquet",
        s"$parquetPath/blocks.parquet"
      )

      // Skip test if test data is not available
      assume(
        requiredFiles.forall(path => Files.exists(Paths.get(path))),
        "Test data files not found. Please provide Parquet test data."
      )

      val modeMap = Map("WALK" -> "walk", "CAR" -> "car", "TRANSIT" -> "transit")

      val reader = new UrbansimReaderV2(
        inputPersonPath = s"$parquetPath/persons.parquet",
        inputPlanPath = s"$parquetPath/plans.parquet",
        inputHouseholdPath = s"$parquetPath/households.parquet",
        inputVehiclePath = s"$parquetPath/vehicles.parquet",
        inputBlockPath = s"$parquetPath/blocks.parquet",
        geoUtils = testGeoUtils,
        shouldConvertWgs2Utm = false,
        modeMap = modeMap,
        fileFormat = "parquet"
      )

      // Test reading persons
      val persons = reader.getPersons.toSeq
      persons should not be empty

      // Test reading plans
      val plans = reader.getPlans.toSeq
      plans should not be empty
      plans.foreach { p =>
        p.planIndex shouldBe 0
        p.planScore shouldBe 0.0
        p.planSelected shouldBe true
      }

      // Test reading households
      val households = reader.getHousehold.toSeq
      households should not be empty

      // Test reading vehicles (optional)
      val vehicles = reader.getVehicles.toSeq
      vehicles should have size 2
      vehicles.map(_.vehicleId) should contain theSameElementsInOrderAs Seq("veh-parquet-1", "veh-parquet-2")
      vehicles.map(_.householdId) should contain theSameElementsInOrderAs Seq("226798", "580065")
      vehicles.map(_.initialSoc) should contain theSameElementsInOrderAs Seq(Some(0.75), None)
    }

    "throw an exception when Parquet files are missing" in {
      val parquetPath = s"$testDataPath/missing"
      val modeMap = Map("WALK" -> "walk", "CAR" -> "car", "TRANSIT" -> "transit")

      an[IllegalArgumentException] should be thrownBy {
        new UrbansimReaderV2(
          inputPersonPath = s"$parquetPath/persons.parquet",
          inputPlanPath = s"$parquetPath/plans.parquet",
          inputHouseholdPath = s"$parquetPath/households.parquet",
          inputVehiclePath = s"$parquetPath/vehicles.parquet",
          inputBlockPath = s"$parquetPath/blocks.parquet",
          geoUtils = testGeoUtils,
          shouldConvertWgs2Utm = false,
          modeMap = modeMap,
          fileFormat = "parquet"
        )
      }
    }

    "read CSV plans with camelCase, lowercase, and omitted planSelected memory fields correctly" in {
      val tempDir = Files.createTempDirectory("urbansim_plan_test")
      try {
        val csvFile = tempDir.resolve("plans.csv")
        val content =
          """trip_id,tour_id,person_id,trip_mode,ActivityElement,PlanElementIndex,ActivityType,x,y,departure_time,planIndex,planScore,planSelected
            |1,1,100,car,activity,0,Home,-122.4,37.7,8.0,0,12.5,false
            |1,1,100,car,leg,1,,,,8.0,0,12.5,false
            |1,1,100,car,activity,2,Work,-122.3,37.8,17.0,0,12.5,false
            |2,1,100,walk,activity,0,Home,-122.4,37.7,8.5,1,25.0,true
            |2,1,100,walk,leg,1,,,,8.5,1,25.0,true
            |2,1,100,walk,activity,2,Work,-122.3,37.8,17.0,1,25.0,true
            |""".stripMargin
        Files.write(csvFile, content.getBytes("UTF-8"))

        val reader = new UrbansimReaderV2(
          inputPersonPath = s"$testDataPath/csv/persons.csv.gz",
          inputPlanPath = csvFile.toString,
          inputHouseholdPath = s"$testDataPath/csv/households.csv.gz",
          inputVehiclePath = s"$testDataPath/csv/vehicles.csv.gz",
          inputBlockPath = s"$testDataPath/csv/blocks.csv.gz",
          geoUtils = testGeoUtils,
          shouldConvertWgs2Utm = false,
          modeMap = Map("car" -> "car", "walk" -> "walk"),
          fileFormat = "csv"
        )

        val plans = reader.getPlans.toSeq
        plans should have size 6

        val plan0 = plans.filter(_.planIndex == 0)
        plan0 should have size 3
        plan0.foreach { p =>
          p.planScore shouldBe 12.5
          p.planSelected shouldBe false
        }

        val plan1 = plans.filter(_.planIndex == 1)
        plan1 should have size 3
        plan1.foreach { p =>
          p.planScore shouldBe 25.0
          p.planSelected shouldBe true
        }

        // Test lowercase columns and missing planSelected defaulting to planIndex == 0
        val csvFileLower = tempDir.resolve("plans_lower.csv")
        val contentLower =
          """trip_id,tour_id,person_id,trip_mode,ActivityElement,PlanElementIndex,ActivityType,x,y,departure_time,planindex,planscore,planselected
            |1,1,200,car,activity,0,Home,-122.4,37.7,8.0,2,100.0,1
            |1,1,201,car,activity,0,Home,-122.4,37.7,8.0,0,50.0,
            |1,1,201,car,activity,0,Home,-122.4,37.7,8.0,1,60.0,
            |""".stripMargin
        Files.write(csvFileLower, contentLower.getBytes("UTF-8"))

        val readerLower = new UrbansimReaderV2(
          inputPersonPath = s"$testDataPath/csv/persons.csv.gz",
          inputPlanPath = csvFileLower.toString,
          inputHouseholdPath = s"$testDataPath/csv/households.csv.gz",
          inputVehiclePath = s"$testDataPath/csv/vehicles.csv.gz",
          inputBlockPath = s"$testDataPath/csv/blocks.csv.gz",
          geoUtils = testGeoUtils,
          shouldConvertWgs2Utm = false,
          modeMap = Map("car" -> "car"),
          fileFormat = "csv"
        )
        val plansLower = readerLower.getPlans.toSeq
        plansLower should have size 3
        val p200 = plansLower.find(_.personId.id == "200").get
        p200.planIndex shouldBe 2
        p200.planScore shouldBe 100.0
        p200.planSelected shouldBe true

        val p201Plan0 = plansLower.find(p => p.personId.id == "201" && p.planIndex == 0).get
        p201Plan0.planSelected shouldBe true
        val p201Plan1 = plansLower.find(p => p.personId.id == "201" && p.planIndex == 1).get
        p201Plan1.planSelected shouldBe false
      } finally {
        org.apache.commons.io.FileUtils.deleteDirectory(tempDir.toFile)
      }
    }

    "read Parquet plans with populated planIndex, planScore, and planSelected correctly" in {
      import org.apache.avro.SchemaBuilder
      import org.apache.avro.generic.{GenericData, GenericRecord}
      import org.apache.hadoop.conf.Configuration
      import org.apache.hadoop.fs.{Path => HadoopPath}
      import org.apache.parquet.avro.AvroParquetWriter
      import org.apache.parquet.hadoop.util.HadoopOutputFile

      val parquetBase = s"$testDataPath/parquet"
      val requiredFiles = List(
        s"$parquetBase/households.parquet",
        s"$parquetBase/persons.parquet",
        s"$parquetBase/blocks.parquet"
      )
      assume(
        requiredFiles.forall(path => Files.exists(Paths.get(path))),
        "Parquet base test data files not found."
      )

      val tempDir = Files.createTempDirectory("urbansim_parquet_plan_test")
      try {
        val parquetPlanFile = tempDir.resolve("plans_with_memory.parquet")
        val schema = SchemaBuilder
          .record("PlanRecord")
          .fields()
          .optionalString("trip_id")
          .optionalString("tour_id")
          .requiredString("person_id")
          .requiredInt("PlanElementIndex")
          .requiredString("ActivityElement")
          .optionalString("trip_mode")
          .optionalString("ActivityType")
          .optionalDouble("x")
          .optionalDouble("y")
          .optionalDouble("departure_time")
          .optionalInt("planIndex")
          .optionalDouble("planScore")
          .optionalString("planSelected")
          .endRecord()

        val writer = AvroParquetWriter
          .builder[GenericRecord](HadoopOutputFile.fromPath(new HadoopPath(parquetPlanFile.toUri), new Configuration()))
          .withSchema(schema)
          .build()
        try {
          val r0 = new GenericData.Record(schema)
          r0.put("trip_id", "1")
          r0.put("tour_id", "1")
          r0.put("person_id", "100")
          r0.put("PlanElementIndex", 0)
          r0.put("ActivityElement", "activity")
          r0.put("ActivityType", "Home")
          r0.put("x", -122.4)
          r0.put("y", 37.7)
          r0.put("departure_time", 8.0)
          r0.put("planIndex", 0)
          r0.put("planScore", -10.5)
          r0.put("planSelected", "false")
          writer.write(r0)

          val r1 = new GenericData.Record(schema)
          r1.put("trip_id", "2")
          r1.put("tour_id", "1")
          r1.put("person_id", "100")
          r1.put("PlanElementIndex", 0)
          r1.put("ActivityElement", "activity")
          r1.put("ActivityType", "Home")
          r1.put("x", -122.4)
          r1.put("y", 37.7)
          r1.put("departure_time", 9.0)
          r1.put("planIndex", 1)
          r1.put("planScore", 42.25)
          r1.put("planSelected", "true")
          writer.write(r1)

          // Missing/NaN planSelected on planIndex 2 should default to false (since planIndex != 0)
          val r2 = new GenericData.Record(schema)
          r2.put("trip_id", "3")
          r2.put("tour_id", "1")
          r2.put("person_id", "100")
          r2.put("PlanElementIndex", 0)
          r2.put("ActivityElement", "activity")
          r2.put("ActivityType", "Home")
          r2.put("x", -122.4)
          r2.put("y", 37.7)
          r2.put("departure_time", 10.0)
          r2.put("planIndex", 2)
          r2.put("planScore", 5.0)
          r2.put("planSelected", "NaN")
          writer.write(r2)
        } finally {
          writer.close()
        }

        val reader = new UrbansimReaderV2(
          inputPersonPath = s"$parquetBase/persons.parquet",
          inputPlanPath = parquetPlanFile.toString,
          inputHouseholdPath = s"$parquetBase/households.parquet",
          inputVehiclePath = s"$parquetBase/vehicles.parquet",
          inputBlockPath = s"$parquetBase/blocks.parquet",
          geoUtils = testGeoUtils,
          shouldConvertWgs2Utm = false,
          modeMap = Map("car" -> "car", "walk" -> "walk"),
          fileFormat = "parquet"
        )

        val plans = reader.getPlans.toSeq
        plans should have size 3
        plans(0).planIndex shouldBe 0
        plans(0).planScore shouldBe -10.5
        plans(0).planSelected shouldBe false

        plans(1).planIndex shouldBe 1
        plans(1).planScore shouldBe 42.25
        plans(1).planSelected shouldBe true

        plans(2).planIndex shouldBe 2
        plans(2).planScore shouldBe 5.0
        plans(2).planSelected shouldBe false
      } finally {
        org.apache.commons.io.FileUtils.deleteDirectory(tempDir.toFile)
      }
    }

    "preserve multiple plans, sort by planIndex, and deterministically resolve selectedPlan in scenario loader" in {
      import org.matsim.core.scenario.ScenarioUtils
      import org.matsim.core.config.ConfigUtils
      import beam.utils.scenario.PersonId
      import beam.utils.scenario.PlanElement
      import beam.sim.BeamScenario
      import beam.sim.config.BeamConfig
      import org.mockito.Mockito.{mock, when}

      val scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
      val person1 = scenario.getPopulation.getFactory.createPerson(org.matsim.api.core.v01.Id.createPersonId("p1"))
      val person2 = scenario.getPopulation.getFactory.createPerson(org.matsim.api.core.v01.Id.createPersonId("p2"))
      scenario.getPopulation.addPerson(person1)
      scenario.getPopulation.addPerson(person2)

      val mockBeamScenario = mock(classOf[BeamScenario])
      val beamConfigBase = BeamConfig(TestConfigUtils.testConfig("test/input/beamville/beam.conf").resolve())
      when(mockBeamScenario.beamConfig).thenReturn(beamConfigBase)

      def mkAct(
        personId: String,
        planIdx: Int,
        score: Double,
        selected: Boolean,
        elemIdx: Int,
        actType: String,
        x: Double,
        y: Double,
        end: Double
      ): PlanElement = PlanElement(
        tripId = "1",
        tourId = "1",
        personId = PersonId(personId),
        planIndex = planIdx,
        planScore = score,
        planSelected = selected,
        planElementType = PlanElement.Activity,
        planElementIndex = elemIdx,
        activityType = Some(actType),
        activityLocationX = Some(x),
        activityLocationY = Some(y),
        activityEndTime = Some(end),
        legMode = None,
        legDepartureTime = None,
        legTravelTime = None,
        legExpectedTravelTime = None,
        legExpectedCost = None,
        legRouteType = None,
        legRouteStartLink = None,
        legRouteEndLink = None,
        legRouteTravelTime = None,
        legRouteDistance = None,
        legRouteLinks = Seq.empty,
        geoId = None
      )

      // Provide p1's plans out-of-order (planIndex 1 before planIndex 0)
      // Provide p2's plans with all planSelected = false to verify fallback to lowest planIndex
      val elements = Seq(
        mkAct("p1", 1, 30.0, selected = true, 0, "Home", 0.0, 0.0, 9.0),
        mkAct("p1", 1, 30.0, selected = true, 1, "Work", 10.0, 10.0, 18.0),
        mkAct("p1", 0, 15.0, selected = false, 0, "Home", 0.0, 0.0, 8.0),
        mkAct("p1", 0, 15.0, selected = false, 1, "Work", 10.0, 10.0, 17.0),
        mkAct("p2", 2, 20.0, selected = false, 0, "Home", 0.0, 0.0, 8.0),
        mkAct("p2", 1, 10.0, selected = false, 0, "Home", 0.0, 0.0, 8.0)
      )

      val mockLoader = new beam.utils.scenario.UrbanSimScenarioLoader(
        scenario = scenario.asInstanceOf[org.matsim.core.scenario.MutableScenario],
        beamScenario = mockBeamScenario,
        scenarioSource = null,
        geo = testGeoUtils
      )

      mockLoader.applyPlans(elements)

      person1.getPlans should have size 2
      val p0 = person1.getPlans.get(0)
      val p1 = person1.getPlans.get(1)
      p0.getScore shouldBe 15.0
      p1.getScore shouldBe 30.0
      person1.getSelectedPlan shouldBe p1

      person2.getPlans should have size 2
      person2.getPlans.get(0).getScore shouldBe 10.0
      person2.getPlans.get(1).getScore shouldBe 20.0
      person2.getSelectedPlan shouldBe person2.getPlans.get(0)
      mockLoader.resolveSelectedPlanIndex(elements.filter(_.personId.id == "p1")) shouldBe 1
      mockLoader.resolveSelectedPlanIndex(elements.filter(_.personId.id == "p2")) shouldBe 1
    }
  }
}
