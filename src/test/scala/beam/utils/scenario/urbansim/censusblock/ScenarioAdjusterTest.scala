package beam.utils.scenario.urbansim.censusblock

import java.util.UUID

import beam.router.Modes.BeamMode
import beam.sim.config.BeamConfig.Beam.Urbansim
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Leg, Person, Population, PopulationFactory}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.scenario.ScenarioUtils
import org.scalatest.{FunSuite, Matchers}

import scala.collection.JavaConverters._

class ScenarioAdjusterTest extends FunSuite with Matchers {

  test("adjust should work properly when allModes = 0") {
    val peoplePerNode: Int = 1000
    val cfg = Urbansim(
      Urbansim.FractionOfModesToClear(
        allModes = 0.0,
        bike = 0.2,
        car = 0.3,
        drive_transit = 0.4,
        walk = 0.5,
        walk_transit = 0.6
      )
    )
    val population = createPopulation(
      peoplePerNode,
      Set(BeamMode.BIKE, BeamMode.CAR, BeamMode.DRIVE_TRANSIT, BeamMode.WALK, BeamMode.WALK_TRANSIT)
    )
    val adjuster = new ScenarioAdjuster(cfg, population, 42)
    adjuster.adjust()

    val legs = getLegs(population)

    val nBikes = legs.count(x => x.getMode.equalsIgnoreCase(BeamMode.BIKE.value))
    nBikes shouldBe compute(peoplePerNode, cfg.fractionOfModesToClear.bike)

    val nCars = legs.count(x => x.getMode.equalsIgnoreCase(BeamMode.CAR.value))
    nCars shouldBe compute(peoplePerNode, cfg.fractionOfModesToClear.car)

    val nDriveTransit = legs.count(x => x.getMode.equalsIgnoreCase(BeamMode.DRIVE_TRANSIT.value))
    nDriveTransit shouldBe compute(peoplePerNode, cfg.fractionOfModesToClear.drive_transit)

    val nWalk = legs.count(x => x.getMode.equalsIgnoreCase(BeamMode.WALK.value))
    nWalk shouldBe compute(peoplePerNode, cfg.fractionOfModesToClear.walk)

    val nWalkTransit = legs.count(x => x.getMode.equalsIgnoreCase(BeamMode.WALK_TRANSIT.value))
    nWalkTransit shouldBe compute(peoplePerNode, cfg.fractionOfModesToClear.walk_transit)
  }

  test("adjust should work properly when allModes > 0, but all other modes are set to 0.0") {
    val peoplePerNode: Int = 1000
    val cfg = Urbansim(
      Urbansim.FractionOfModesToClear(
        allModes = 0.5,
        bike = 0.0,
        car = 0.0,
        drive_transit = 0.0,
        walk = 0.0,
        walk_transit = 0.0
      )
    )
    val population = createPopulation(
      peoplePerNode,
      Set(BeamMode.BIKE, BeamMode.CAR, BeamMode.DRIVE_TRANSIT, BeamMode.WALK, BeamMode.WALK_TRANSIT)
    )
    val adjuster = new ScenarioAdjuster(cfg, population, 42)
    adjuster.adjust()

    val legs = getLegs(population)
    val emptyModes = legs.count(leg => leg.getMode == "")
    emptyModes shouldBe compute(legs.size, cfg.fractionOfModesToClear.allModes)
  }

  test("clearModes should work properly") {
    val population = createPopulation(100, Set(BeamMode.CAR))
    ScenarioAdjuster.clearModes(population.getPersons.values().asScala, BeamMode.CAR.value, _ => true, 0.5, 42)
    val legs = getLegs(population)
    val cars = legs.count(x => x.getMode.equalsIgnoreCase(BeamMode.CAR.value))
    cars shouldBe 50
  }

  private def createPopulation(peoplePerMode: Int, modes: Set[BeamMode]): Population = {
    val population = ScenarioUtils.createScenario(ConfigUtils.createConfig).getPopulation
    val personIdWithMode = for {
      mode <- modes
      id   <- (1 to peoplePerMode).map(_ => UUID.randomUUID.toString)
    } yield (id, mode)

    personIdWithMode.foreach {
      case (id, mode) =>
        val person = createPerson(population.getFactory, Id.createPersonId(id), mode)
        population.addPerson(person)
    }
    population
  }

  private def createPerson(populationFactory: PopulationFactory, id: Id[Person], mode: BeamMode): Person = {
    val person = populationFactory.createPerson(id)
    val plan = populationFactory.createPlan()
    plan.setPerson(person)
    person.addPlan(plan)
    person.setSelectedPlan(plan)

    val act = populationFactory.createActivityFromLinkId("Dummy", Id.createLinkId(1))
    val leg = populationFactory.createLeg(mode.value)
    plan.addActivity(act)
    plan.addLeg(leg)
    person
  }

  def compute(count: Int, modeFraction: Double): Int = {
    val temp = count - count * modeFraction
    temp.toInt
  }

  private def getLegs(population: Population): Iterable[Leg] = {
    population.getPersons.values().asScala.flatMap { p =>
      p.getSelectedPlan.getPlanElements.asScala.collect { case l: Leg => l }
    }
  }
}
