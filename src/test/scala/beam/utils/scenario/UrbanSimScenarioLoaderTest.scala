package beam.utils.scenario

import beam.sim.BeamScenario
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import org.matsim.core.scenario.MutableScenario
import org.mockito.Mockito.when
import org.scalatest.{AsyncWordSpec, BeforeAndAfterEach, Matchers}
import org.scalatestplus.mockito.MockitoSugar

class UrbanSimScenarioLoaderTest extends AsyncWordSpec with Matchers with MockitoSugar with BeforeAndAfterEach {
  private val mutableScenario = mock[MutableScenario]
  private val beamScenario = mock[BeamScenario]

  private val beamConfigBase = BeamConfig(testConfig("test/input/beamville/beam.conf").resolve())

  private val scenarioSource = mock[ScenarioSource]

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

  override protected def beforeEach(): Unit =
    idIter = Iterator.from(1)

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
      valueOfTime = 0.0
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
