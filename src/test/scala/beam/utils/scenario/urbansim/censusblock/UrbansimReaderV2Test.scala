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

    "read CSV plans with camelCase and lowercase plan memory fields correctly" in {
      val tempDir = Files.createTempDirectory("urbansim_plan_test")
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

      // Test lowercase columns as well
      val csvFileLower = tempDir.resolve("plans_lower.csv")
      val contentLower =
        """trip_id,tour_id,person_id,trip_mode,ActivityElement,PlanElementIndex,ActivityType,x,y,departure_time,planindex,planscore,planselected
          |1,1,200,car,activity,0,Home,-122.4,37.7,8.0,2,100.0,1
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
      plansLower should have size 1
      plansLower.head.planIndex shouldBe 2
      plansLower.head.planScore shouldBe 100.0
      plansLower.head.planSelected shouldBe true
    }

    "preserve multiple plans and selectedPlan when loaded into scenario loader" in {
      import org.matsim.core.scenario.ScenarioUtils
      import org.matsim.core.config.ConfigUtils
      import beam.utils.scenario.PersonId
      import beam.utils.scenario.PlanElement
      import beam.sim.BeamScenario
      import beam.sim.config.BeamConfig
      import org.mockito.Mockito.{mock, when}

      val scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
      val person = scenario.getPopulation.getFactory.createPerson(org.matsim.api.core.v01.Id.createPersonId("p1"))
      scenario.getPopulation.addPerson(person)

      val mockBeamScenario = mock(classOf[BeamScenario])
      val beamConfigBase = BeamConfig(TestConfigUtils.testConfig("test/input/beamville/beam.conf").resolve())
      when(mockBeamScenario.beamConfig).thenReturn(beamConfigBase)

      val elements = Seq(
        PlanElement(
          tripId = "1",
          tourId = "1",
          personId = PersonId("p1"),
          planIndex = 0,
          planScore = 15.0,
          planSelected = false,
          planElementType = PlanElement.Activity,
          planElementIndex = 0,
          activityType = Some("Home"),
          activityLocationX = Some(0.0),
          activityLocationY = Some(0.0),
          activityEndTime = Some(8.0),
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
        ),
        PlanElement(
          tripId = "1",
          tourId = "1",
          personId = PersonId("p1"),
          planIndex = 0,
          planScore = 15.0,
          planSelected = false,
          planElementType = PlanElement.Activity,
          planElementIndex = 1,
          activityType = Some("Work"),
          activityLocationX = Some(10.0),
          activityLocationY = Some(10.0),
          activityEndTime = Some(17.0),
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
        ),
        PlanElement(
          tripId = "2",
          tourId = "1",
          personId = PersonId("p1"),
          planIndex = 1,
          planScore = 30.0,
          planSelected = true,
          planElementType = PlanElement.Activity,
          planElementIndex = 0,
          activityType = Some("Home"),
          activityLocationX = Some(0.0),
          activityLocationY = Some(0.0),
          activityEndTime = Some(9.0),
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
        ),
        PlanElement(
          tripId = "2",
          tourId = "1",
          personId = PersonId("p1"),
          planIndex = 1,
          planScore = 30.0,
          planSelected = true,
          planElementType = PlanElement.Activity,
          planElementIndex = 1,
          activityType = Some("Work"),
          activityLocationX = Some(10.0),
          activityLocationY = Some(10.0),
          activityEndTime = Some(18.0),
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
      )

      val mockLoader = new beam.utils.scenario.UrbanSimScenarioLoader(
        scenario = scenario.asInstanceOf[org.matsim.core.scenario.MutableScenario],
        beamScenario = mockBeamScenario,
        scenarioSource = null,
        geo = testGeoUtils
      )

      mockLoader.applyPlans(elements)

      person.getPlans should have size 2
      val p0 = person.getPlans.get(0)
      val p1 = person.getPlans.get(1)
      p0.getScore shouldBe 15.0
      p1.getScore shouldBe 30.0
      person.getSelectedPlan shouldBe p1
    }
  }
}
