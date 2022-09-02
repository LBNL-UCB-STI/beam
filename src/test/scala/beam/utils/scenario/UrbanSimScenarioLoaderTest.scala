package beam.utils.scenario

import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.agents.vehicles.VehicleCategory.Bike
import beam.sim.BeamScenario
import beam.sim.RunBeam.{buildUrbansimV2ScenarioSource, loadScenario}
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Id
import org.matsim.core.scenario.{MutableScenario, ScenarioBuilder}
import org.matsim.households.Household
import org.mockito.Mockito.{mock, when}
import org.scalatest.wordspec.AsyncWordSpec
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers

import scala.collection.convert.ImplicitConversions.`collection AsScalaIterable`

class UrbanSimScenarioLoaderTest extends AsyncWordSpec with Matchers with BeforeAndAfterEach {
  private val mutableScenario = mock(classOf[MutableScenario])
  private val beamScenario = mock(classOf[BeamScenario])

  private val beamConfigBase = BeamConfig(testConfig("test/input/beamville/beam.conf").resolve())

  private val scenarioSource = mock(classOf[ScenarioSource])

  private val geoUtils = new GeoUtilsImpl(beamConfigBase)

  private var idIter = Iterator.from(1)

  "UrbanSimScenarioLoader with fractionOfInitialVehicleFleet < 1.0 and downsamplingMethod : SECONDARY_VEHICLES_FIRST" should {
    "assign vehicles properly in case 1" in {
      when(beamScenario.beamConfig).thenReturn(getConfig(0.5))
      val urbanSimScenario = new UrbanSimScenarioLoader(mutableScenario, beamScenario, scenarioSource, geoUtils)

      val (houseHolds, h2ps, people2Score) = generateData(2, 2, 3, 3)

      val res = urbanSimScenario.assignVehicles(houseHolds, h2ps, people2Score)

      assertCarNumbers(res, "1" -> 0, "2" -> 1, "3" -> 2, "4" -> 2)
    }

    "assign vehicles properly in case 2" in {
      when(beamScenario.beamConfig).thenReturn(getConfig(0.5))
      val urbanSimScenario = new UrbanSimScenarioLoader(mutableScenario, beamScenario, scenarioSource, geoUtils)

      val (houseHolds, h2ps, people2Score) = generateData(10, 10, 10, 10)

      val res = urbanSimScenario.assignVehicles(houseHolds, h2ps, people2Score)

      assertCarNumbers(res, "1" -> 5, "2" -> 5, "3" -> 5, "4" -> 5)
    }

    "assign vehicles properly in case 3" in {
      when(beamScenario.beamConfig).thenReturn(getConfig(0.5))
      val urbanSimScenario = new UrbanSimScenarioLoader(mutableScenario, beamScenario, scenarioSource, geoUtils)

      val (houseHolds, h2ps, people2Score) = generateData(1, 2, 3, 4)

      val res = urbanSimScenario.assignVehicles(houseHolds, h2ps, people2Score)

      assertCarNumbers(res, "1" -> 0, "2" -> 1, "3" -> 2, "4" -> 2)
    }
  }

  "UrbanSimScenarioLoader with fractionOfInitialVehicleFleet >= 1.0 and downsamplingMethod : SECONDARY_VEHICLES_FIRST" should {
    "assign vehicles properly in case1" in {
      when(beamScenario.beamConfig).thenReturn(getConfig(5.0))
      val urbanSimScenario = new UrbanSimScenarioLoader(mutableScenario, beamScenario, scenarioSource, geoUtils)

      val (houseHolds, h2ps, people2Score) = generateData(1, 1, 1, 1)

      val res = urbanSimScenario.assignVehicles(houseHolds, h2ps, people2Score)

      assertCarNumbers(res, "1" -> 2, "2" -> 2, "3" -> 2, "4" -> 2)
    }

    "assign vehicles properly in case 2" in {
      when(beamScenario.beamConfig).thenReturn(getConfig(5.0))
      val urbanSimScenario = new UrbanSimScenarioLoader(mutableScenario, beamScenario, scenarioSource, geoUtils)

      val (houseHolds, h2ps, people2Score) = generateData(1, 0)

      val res = urbanSimScenario.assignVehicles(houseHolds, h2ps, people2Score)

      assertCarNumbers(res, "1" -> 2, "2" -> 2)
    }

    "assign vehicles properly in case 3" in {
      when(beamScenario.beamConfig).thenReturn(getConfig(5.0))
      val urbanSimScenario = new UrbanSimScenarioLoader(mutableScenario, beamScenario, scenarioSource, geoUtils)

      val (houseHolds, h2ps, people2Score) = generateData(0, 1, 2)

      val res = urbanSimScenario.assignVehicles(houseHolds, h2ps, people2Score)

      assertCarNumbers(res, "1" -> 2, "2" -> 2, "3" -> 2)
    }

  }

  "UrbanSimScenarioLoader with vehicleAdjustmentMethod = STATIC_FROM_FILE "should {
    "assign vehicles properly in case1" in {

      val matsimConfig = new MatSimBeamConfigBuilder(staticVehiclesConfig).buildMatSimConf()
      val emptyScenario = ScenarioBuilder(matsimConfig, beamScenario.network).build
      val staticVehicleBeamConfig = BeamConfig(staticVehiclesConfig)
      val staticGeoUtils = new GeoUtilsImpl(staticVehicleBeamConfig);
      when(beamScenario.beamConfig).thenReturn(staticVehicleBeamConfig);
      lazy val staticBeamScenario: BeamScenario = loadScenario(staticVehicleBeamConfig);
      val staticScenarioSource = buildUrbansimV2ScenarioSource(staticGeoUtils, staticVehicleBeamConfig);
      val urbanSimScenario = new UrbanSimScenarioLoader(emptyScenario, staticBeamScenario, staticScenarioSource, geoUtils)
      urbanSimScenario.loadScenario()

      val vehicleIds = emptyScenario.getHouseholds.getHouseholds.get(Id.create(2, classOf[Household])).getVehicleIds
      val vehicleMap = staticBeamScenario.privateVehicles.toMap
      val vehiclesBelongToSpecificHousehold = vehicleIds.map(x => Id.create(x, classOf[BeamVehicle])).flatMap(x => vehicleMap get x);
      vehiclesBelongToSpecificHousehold.count(x => x.beamVehicleType.id.compareTo(
        Id.create("Bicycle", classOf[BeamVehicleType])
      ) == 0) shouldBe 7
    }

  }

  override protected def beforeEach(): Unit =
    idIter = Iterator.from(1)

  def staticVehiclesConfig: com.typesafe.config.Config =
    ConfigFactory
      .parseString(s"""
                      |beam.agentsim.simulationName = "beamville-urbansimv2_static"
                      |beam.agentsim.agents.vehicles.vehiclesFilePath = $${beam.inputDirectory}"/vehicles-test.csv"
                      |beam.agentsim.agents.vehicles.vehicleAdjustmentMethod = "STATIC_FROM_FILE"
                    """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam-urbansimv2.conf"))
      .resolve()

  private def getConfig(fractionOfInitialVehicleFleet: Double = 1.0) = beamConfigBase.copy(
    matsim = beamConfigBase.matsim
      .copy(
        modules = beamConfigBase.matsim.modules.copy(
          global = beamConfigBase.matsim.modules.global.copy(
            randomSeed = 1
          )
        )
      ),
    beam = beamConfigBase.beam.copy(
      agentsim = beamConfigBase.beam.agentsim.copy(
        agents = beamConfigBase.beam.agentsim.agents.copy(
          vehicles = beamConfigBase.beam.agentsim.agents.vehicles.copy(
            fractionOfInitialVehicleFleet = fractionOfInitialVehicleFleet
          )
        )
      )
    )
  )

  private def generateData(householdCarNumbers: Int*) = {
    val houseHolds = householdCarNumbers.map(household)

    val h2ps = houseHolds.map { h =>
      h.householdId -> List(person(h.householdId), person(h.householdId))
    }.toMap

    val people2Score = h2ps.values.flatten.map(_.personId -> idIter.next() * 10.0).toMap

    Tuple3(houseHolds, h2ps, people2Score)
  }

  private def household(cars: Int) = HouseholdInfo(HouseholdId(idIter.next().toString), cars, 123.0, 1.0, 1.0)

  private def person(householdId: HouseholdId) =
    PersonInfo(
      personId = PersonId(idIter.next().toString),
      householdId = householdId,
      rank = 123,
      age = 30,
      isFemale = false,
      excludedModes = Seq.empty,
      valueOfTime = 0.0,
      industry = None
    )

  private def assertCarNumbers(
    result: Iterable[(HouseholdInfo, Int)],
    householdId2ExpectedCarNumbers: (String, Int)*
  ) = {
    result.size shouldBe householdId2ExpectedCarNumbers.size

    result
      .map { case (hh, cars) => hh.householdId.id -> cars }
      .toSeq
      .sorted
      .toMap shouldBe householdId2ExpectedCarNumbers.toMap
  }
}
