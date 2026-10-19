package beam.utils.scenario.urbansim.censusblock

import beam.sim.common.GeoUtils
import beam.utils.TestConfigUtils
import beam.utils.scenario.{InputType, PlanElement}
import beam.utils.scenario.urbansim.censusblock.entities.{Activity, ActivityType, InputPlanElement, Leg}
import beam.utils.scenario.urbansim.censusblock.merger.PlanMerger
import beam.utils.scenario.urbansim.censusblock.reader.ParquetReaders
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

        // Test lowercase columns, NaN planScore preservation, and missing planSelected defaulting to planIndex == 0
        val csvFileLower = tempDir.resolve("plans_lower.csv")
        val contentLower =
          """trip_id,tour_id,person_id,trip_mode,ActivityElement,PlanElementIndex,ActivityType,x,y,departure_time,planindex,planscore,planselected
            |1,1,200,car,activity,0,Home,-122.4,37.7,8.0,2,100.0,1
            |1,1,201,car,activity,0,Home,-122.4,37.7,8.0,0,50.0,
            |1,1,201,car,activity,0,Home,-122.4,37.7,8.0,1,NaN,
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
        p201Plan1.planScore.isNaN shouldBe true
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
          .optionalString("planScore")
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
          r0.put("planScore", "-10.5")
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
          r1.put("planScore", "42.25")
          r1.put("planSelected", "true")
          writer.write(r1)

          // Missing/NaN planSelected on planIndex 2 should default to false (since planIndex != 0)
          // and "NaN" planScore should be preserved as Double.NaN
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
          r2.put("planScore", "NaN")
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
        plans(2).planScore.isNaN shouldBe true
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
      val person3 = scenario.getPopulation.getFactory.createPerson(org.matsim.api.core.v01.Id.createPersonId("p3"))
      scenario.getPopulation.addPerson(person1)
      scenario.getPopulation.addPerson(person2)
      scenario.getPopulation.addPerson(person3)

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
      // Provide p3's plan with score = Double.NaN to verify plan.getScore is null for unexecuted plans
      val elements = Seq(
        mkAct("p1", 1, 30.0, selected = true, 0, "Home", 0.0, 0.0, 9.0),
        mkAct("p1", 1, 30.0, selected = true, 1, "Work", 10.0, 10.0, 18.0),
        mkAct("p1", 0, 15.0, selected = false, 0, "Home", 0.0, 0.0, 8.0),
        mkAct("p1", 0, 15.0, selected = false, 1, "Work", 10.0, 10.0, 17.0),
        mkAct("p2", 2, 20.0, selected = false, 0, "Home", 0.0, 0.0, 8.0),
        mkAct("p2", 1, 10.0, selected = false, 0, "Home", 0.0, 0.0, 8.0),
        mkAct("p3", 0, Double.NaN, selected = true, 0, "Home", 0.0, 0.0, 8.0)
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

      person3.getPlans should have size 1
      person3.getPlans.get(0).getScore shouldBe null
      person3.getSelectedPlan shouldBe person3.getPlans.get(0)

      mockLoader.resolveSelectedPlanIndex(elements.filter(_.personId.id == "p1")) shouldBe 1
      mockLoader.resolveSelectedPlanIndex(elements.filter(_.personId.id == "p2")) shouldBe 1
      mockLoader.resolveSelectedPlanIndex(elements.filter(_.personId.id == "p3")) shouldBe 0
    }

    "preserve legDepartureTime and legTravelTime in InputPlanElement and PlanMerger" in {
      val mapSnake = new java.util.HashMap[String, String]()
      mapSnake.put("person_id", "p1")
      mapSnake.put("PlanElementIndex", "2")
      mapSnake.put("ActivityElement", "leg")
      mapSnake.put("trip_id", "trip_1")
      mapSnake.put("tour_id", "tour_1")
      mapSnake.put("trip_mode", "car")
      mapSnake.put("leg_departure_time", "28800.0")
      mapSnake.put("leg_travel_time", "1800.0")
      mapSnake.put("trip_dur_min", "30.0")
      mapSnake.put("trip_cost_dollars", "5.0")

      val elemSnake = InputPlanElement.transform(mapSnake)
      elemSnake.tripId shouldBe Some("trip_1")
      elemSnake.tourId shouldBe Some("tour_1")
      elemSnake.legDepartureTime shouldBe Some("28800.0")
      elemSnake.legTravelTime shouldBe Some("1800.0")

      val mapCamel = new java.util.HashMap[String, String]()
      mapCamel.put("personId", "p1")
      mapCamel.put("planElementIndex", "2")
      mapCamel.put("planElementType", "leg")
      mapCamel.put("tripId", "trip_2")
      mapCamel.put("tourId", "tour_2")
      mapCamel.put("legMode", "car")
      mapCamel.put("legDepartureTime", "29000.0")
      mapCamel.put("legTravelTime", "1500.0")

      val elemCamel = InputPlanElement.transform(mapCamel)
      elemCamel.tripId shouldBe Some("trip_2")
      elemCamel.tourId shouldBe Some("tour_2")
      elemCamel.legDepartureTime shouldBe Some("29000.0")
      elemCamel.legTravelTime shouldBe Some("1500.0")

      val act1 = InputPlanElement(
        tripId = None,
        tourId = None,
        personId = "p1",
        planElementIndex = 1,
        activityElement = Activity,
        tripMode = None,
        ActivityType = Some("Home"),
        x = Some(0.0),
        y = Some(0.0),
        departureTime = Some(8.0)
      )
      val act2 = InputPlanElement(
        tripId = None,
        tourId = None,
        personId = "p1",
        planElementIndex = 3,
        activityElement = Activity,
        tripMode = None,
        ActivityType = Some("Work"),
        x = Some(10.0),
        y = Some(10.0),
        departureTime = Some(17.0)
      )

      val modeMap = Map("CAR" -> "car", "WALK" -> "walk")
      val merger = new PlanMerger(modeMap)
      val merged = merger.merge(Iterator(act1, elemSnake, act2)).toSeq
      val leg = merged.find(_.planElementType == PlanElement.Leg).get
      leg.tripId shouldBe "trip_1"
      leg.tourId shouldBe "tour_1"
      leg.legDepartureTime shouldBe Some("28800.0")
      leg.legTravelTime shouldBe Some("1800.0")
      leg.legExpectedTravelTime shouldBe Some(30.0)
      leg.legExpectedCost shouldBe Some(5.0)
    }

    "parse pandas float formatted integers for planElementIndex in InputPlanElement" in {
      val mapFloat = new java.util.HashMap[String, String]()
      mapFloat.put("person_id", "p1")
      mapFloat.put("PlanElementIndex", "0.0")
      mapFloat.put("ActivityElement", "activity")
      mapFloat.put("ActivityType", "Home")
      mapFloat.put("x", "0.0")
      mapFloat.put("y", "0.0")
      val elem = InputPlanElement.transform(mapFloat)
      elem.planElementIndex shouldBe 0

      val mapFloatCamel = new java.util.HashMap[String, String]()
      mapFloatCamel.put("personId", "p1")
      mapFloatCamel.put("planElementIndex", "3.0")
      mapFloatCamel.put("planElementType", "activity")
      mapFloatCamel.put("activityType", "Work")
      mapFloatCamel.put("activityLocationX", "10.0")
      mapFloatCamel.put("activityLocationY", "20.0")
      val elemCamel = InputPlanElement.transform(mapFloatCamel)
      elemCamel.planElementIndex shouldBe 3
    }

    "parse pandas float, NaN, and null representations safely across ParquetReaders" in {
      // 1. readPlanSelected / parsePlanSelected
      ParquetReaders.parsePlanSelected(java.lang.Double.valueOf(Double.NaN)) shouldBe None
      ParquetReaders.parsePlanSelected(Double.NaN) shouldBe None
      ParquetReaders.parsePlanSelected("NaN") shouldBe None
      ParquetReaders.parsePlanSelected("nan") shouldBe None
      ParquetReaders.parsePlanSelected("null") shouldBe None
      ParquetReaders.parsePlanSelected("") shouldBe None
      ParquetReaders.parsePlanSelected(null) shouldBe None
      ParquetReaders.parsePlanSelected(java.lang.Double.valueOf(1.0)) shouldBe Some(true)
      ParquetReaders.parsePlanSelected(java.lang.Double.valueOf(0.0)) shouldBe Some(false)
      ParquetReaders.parsePlanSelected("1.0") shouldBe Some(true)
      ParquetReaders.parsePlanSelected("0.0") shouldBe Some(false)
      ParquetReaders.parsePlanSelected("true") shouldBe Some(true)
      ParquetReaders.parsePlanSelected("false") shouldBe Some(false)

      // 2. readPlanIndex / parsePlanIndex
      ParquetReaders.parsePlanIndex(java.lang.Double.valueOf(Double.NaN)) shouldBe None
      ParquetReaders.parsePlanIndex(Double.NaN) shouldBe None
      ParquetReaders.parsePlanIndex("NaN") shouldBe None
      ParquetReaders.parsePlanIndex("null") shouldBe None
      ParquetReaders.parsePlanIndex(null) shouldBe None
      ParquetReaders.parsePlanIndex(java.lang.Double.valueOf(2.0)) shouldBe Some(2)
      ParquetReaders.parsePlanIndex("2.0") shouldBe Some(2)
      ParquetReaders.parsePlanIndex(0) shouldBe Some(0)

      // 3. parsePlanScore
      ParquetReaders.parsePlanScore(java.lang.Double.valueOf(Double.NaN)).get.isNaN shouldBe true
      ParquetReaders.parsePlanScore("NaN").get.isNaN shouldBe true
      ParquetReaders.parsePlanScore("nan").get.isNaN shouldBe true
      ParquetReaders.parsePlanScore("null") shouldBe None
      ParquetReaders.parsePlanScore("") shouldBe None
      ParquetReaders.parsePlanScore(null) shouldBe None
      ParquetReaders.parsePlanScore(123.45) shouldBe Some(123.45)
      ParquetReaders.parsePlanScore("123.45") shouldBe Some(123.45)

      // 4. readDoubleOpt
      ParquetReaders.readDoubleOpt(Some(java.lang.Double.valueOf(Double.NaN))) shouldBe None
      ParquetReaders.readDoubleOpt(Some("NaN")) shouldBe None
      ParquetReaders.readDoubleOpt(Some("null")) shouldBe None
      ParquetReaders.readDoubleOpt(Some("15.5")) shouldBe Some(15.5)
      ParquetReaders.readDoubleOpt(Some(null)) shouldBe None
      ParquetReaders.readDoubleOpt(None) shouldBe None

      // 5. parseInt, parseLong, parseDouble
      ParquetReaders.parseInt(Double.NaN) shouldBe 0
      ParquetReaders.parseInt("NaN") shouldBe 0
      ParquetReaders.parseInt("2.0") shouldBe 2
      ParquetReaders.parseLong(Double.NaN) shouldBe 0L
      ParquetReaders.parseLong("NaN") shouldBe 0L
      ParquetReaders.parseLong("100.0") shouldBe 100L
      ParquetReaders.parseDouble(Double.NaN) shouldBe 0.0
      ParquetReaders.parseDouble("NaN") shouldBe 0.0
      ParquetReaders.parseDouble("42.5") shouldBe 42.5
    }
  }
}
