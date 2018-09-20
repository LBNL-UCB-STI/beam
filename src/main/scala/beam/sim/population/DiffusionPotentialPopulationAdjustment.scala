package beam.sim.population

import java.util.Random

import beam.sim.config.BeamConfig
import beam.sim.population.DiffusionPotentialPopulationAdjustment._
import org.joda.time.DateTime
import org.matsim.api.core.v01.population.{Person, Population}
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.households.Household

class DiffusionPotentialPopulationAdjustment(beamConfig: BeamConfig) extends PopulationAdjustment {
  val rand: Random = new Random(beamConfig.matsim.modules.global.randomSeed)

  override def updatePopulation(scenario: Scenario): Population = {
    val population = scenario.getPopulation

    removeModeAll(population, "ride_hail", "ride_hail_transit")

    adjustPopulationByDiffusionPotential(scenario, "ride_hail", "ride_hail_transit")

    population
  }

  def adjustPopulationByDiffusionPotential(scenario: Scenario, modes: String*): Unit = {
    val population = scenario.getPopulation

    scenario.getPopulation.getPersons.forEach { case (_, person: Person) =>
      val personId = person.getId.toString

      val diffPotential =
      // if (mode.toLowerCase.contains("ride_hail"))
      //        computeRideHailDiffusionPotential(scenario, personId)
      //      else
        computeAutomatedVehicleDiffusionPotential(scenario, person)

      if (diffPotential > rand.nextDouble()) {
        modes.foreach(mode =>
          addMode(population, personId, mode)
        )
      }
    }
  }

  def computeRideHailDiffusionPotential(scenario: Scenario, person: Person): Double = {
    val household = findHousehold(scenario, person.getId)
    val age = person.getAttributes.getAttribute("age").asInstanceOf[Int]
    val income = household.fold(0)(_.getIncome.getIncome.toInt)

    //TODO: Distance to PD
    (if (isBornIn80s(age)) 0.2654 else if (isBornIn90s(age)) 0.2706 else 0) +
      (if (isIncomeAbove200K(income)) 0.1252 else 0) +
      (if (household.nonEmpty && hasChildUnder8(household.get, scenario.getPopulation)) -0.1230 else 0) +
      0.1947 // Constant
  }

  def computeAutomatedVehicleDiffusionPotential(scenario: Scenario, person: Person): Double = {
    val household = findHousehold(scenario, person.getId)
    val age = person.getAttributes.getAttribute("age").asInstanceOf[Int]
    val sex = person.getAttributes.getAttribute("sex").toString
    val income = household.fold(0)(_.getIncome.getIncome.toInt)

    (if (isBornIn40s(age)) 0.1296 else if (isBornIn90s(age)) 0.2278 else 0) +
      (if (isIncome75to150K(income)) 0.0892 else if (isIncome150to200K(income)) 0.1410 else if (isIncomeAbove200K(income)) 0.1925 else 0) +
      (if (isFemale(sex)) -0.2513 else 0) +
      0.4558 // Constant
  }
}

object DiffusionPotentialPopulationAdjustment {
  lazy val currentYear: Int = DateTime.now().year().get()

  def isBornIn40s(age: Int): Boolean = {
    currentYear - age < 1950
  }

  def isBornIn80s(age: Int): Boolean = {
    currentYear - age < 1990
  }

  def isBornIn90s(age: Int): Boolean = {
    currentYear - age < 2000
  }

  def isIncome75to150K(income: Int): Boolean = {
    income >= 75000 && income < 150000
  }

  def isIncome150to200K(income: Int): Boolean = {
    income >= 150000 && income < 200000
  }

  def isIncomeAbove200K(income: Int): Boolean = {
    income >= 200000
  }

  def isFemale(sex: String): Boolean = {
    sex.equalsIgnoreCase("F")
  }

  def hasChildUnder8(household: Household, population: Population): Boolean = {
    import scala.collection.JavaConverters._
    household.getMemberIds.asScala.exists(m =>
      findPerson(population, m).forall(_.getAttributes.getAttribute("age").asInstanceOf[Int] < 8))
  }

  def findHousehold(scenario: Scenario, personId: Id[Person]): Option[Household] = {
    val itrHouseholds = scenario.getHouseholds.getHouseholds.values().iterator()
    while (itrHouseholds.hasNext) {
      val household = itrHouseholds.next()
      if (household.getMemberIds.contains(personId)) return Some(household)
    }
    None
  }

  def findPerson(population: Population, personId: Id[Person]): Option[Person] = {
    val itrPerson = population.getPersons.values().iterator()
    while (itrPerson.hasNext) {
      val person = itrPerson.next()
      if (person.getId.equals(personId)) return Some(person)
    }
    None
  }

  /*val dependentVariables = Map(
    "RIDE_HAIL_SINGLE" -> Map(
      "1980" -> 0.2654,
      "1990" -> 0.2706,
      "child<8" -> -0.1230,
      "income>200K" -> 0.1252,
      "walk-score" -> 0.0006,
      "constant" -> 0.1947),
    "RIDE_HAIL_CARPOOL" -> Map(
      "1980" -> 0.2196,
      "1990" -> 0.2396,
      "child<8" -> -0.1383,
      "income>200K" -> 0.0159,
      "walk-score" -> 0.0014,
      "constant" -> 0.2160),
    "AUTOMATED_VEHICLE" -> Map(
      "1940" -> 0.1296,
      "1990" -> 0.2278,
      "income[75K-150K)" -> 0.0892,
      "income[150K-200K)" -> 0.1410,
      "income>200K" -> 0.1925,
      "female" -> -0.2513,
      "disability" -> -0.3061,
      "constant" -> 0.4558
    )
  )*/
}
