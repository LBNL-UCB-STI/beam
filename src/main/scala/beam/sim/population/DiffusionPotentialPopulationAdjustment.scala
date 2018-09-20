package beam.sim.population

import java.util.Random

import beam.sim.BeamServices
import beam.sim.common.GeoUtils
import beam.sim.population.DiffusionPotentialPopulationAdjustment._
import org.joda.time.DateTime
import org.matsim.api.core.v01.population.{Activity, Person, Plan, Population}
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.households.Household

import scala.collection.JavaConverters._

class DiffusionPotentialPopulationAdjustment(beamServices: BeamServices) extends PopulationAdjustment {
  val rand: Random = new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)
  val geo: GeoUtils = beamServices.geo

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

      val diffPotential = // computeRideHailDiffusionPotential(scenario, person)
        computeAutomatedVehicleDiffusionPotential(scenario, person)

      if (diffPotential > rand.nextDouble()) {
        modes.foreach(mode =>
          addMode(population, personId, mode)
        )
      }
    }
  }

  def computeRideHailDiffusionPotential(scenario: Scenario, person: Person): Double = {

    val age = person.getAttributes.getAttribute("age").asInstanceOf[Int]

    if (isBornIn90s(age)) {
      // if above 18
      lazy val household = findHousehold(scenario, person.getId)
      val income = household.fold(0)(_.getIncome.getIncome.toInt)
      val distanceToPD = getDistanceToPD(person.getPlans.get(0))

      (if (isBornIn80s(age)) 0.2654 else if (isBornIn90s(age)) 0.2706 else 0) +
        (if (isIncomeAbove200K(income)) 0.1252 else 0) +
        (if (household.nonEmpty && hasChildUnder8(household.get, scenario.getPopulation)) -0.1230 else 0) +
        (if(distanceToPD > 10 && distanceToPD <= 20) 0.0997 else if(distanceToPD > 20 && distanceToPD <=50) 0.0687 else 0) +
        0.1947 // Constant
    } else {
      0
    }
  }

  def computeAutomatedVehicleDiffusionPotential(scenario: Scenario, person: Person): Double = {
    lazy val household = findHousehold(scenario, person.getId)
    val age = person.getAttributes.getAttribute("age").asInstanceOf[Int]
    val sex = person.getAttributes.getAttribute("sex").toString
    val income = household.fold(0)(_.getIncome.getIncome.toInt)

    (if (isBornIn40s(age)) 0.1296 else if (isBornIn90s(age)) 0.2278 else 0) +
      (if (isIncome75to150K(income)) 0.0892 else if (isIncome150to200K(income)) 0.1410 else if (isIncomeAbove200K(income)) 0.1925 else 0) +
      (if (isFemale(sex)) -0.2513 else 0) +
      0.4558 // Constant
  }

  def getDistanceToPD(plan: Plan): Double = {
    lazy val activities = plan.getPlanElements.asScala.map(_.asInstanceOf[Activity])

    lazy val home = activities.find(isHome)

    lazy val maxDistance = activities.filterNot(a => isHome(a) || isWork(a) || isSchool(a)).
      map(findDistance).max

    def findDistance(p: Activity) = {
      geo.distInMeters(home.get.getCoord, p.getCoord)
    }

    activities.find(isWork).fold(activities.find(isSchool).fold(maxDistance)(findDistance))(findDistance)
  }
}

object DiffusionPotentialPopulationAdjustment {
  lazy val currentYear: Int = DateTime.now().year().get()

  def isBornIn40s(age: Int): Boolean = {
    currentYear - age >= 1940 && currentYear - age < 1950
  }

  def isBornIn80s(age: Int): Boolean = {
    currentYear - age >= 1980 && currentYear - age < 1990
  }

  def isBornIn90s(age: Int): Boolean = {
    currentYear - age >= 1990 && currentYear - age < 2000
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

  def isHome(activity: Activity): Boolean = {
    activity.getType.equalsIgnoreCase("Home")
  }

  def isWork(activity: Activity): Boolean = {
    activity.getType.equalsIgnoreCase("Work")
  }

  def isSchool(activity: Activity): Boolean = {
    activity.getType.equalsIgnoreCase("School")
  }

  def hasChildUnder8(household: Household, population: Population): Boolean = {
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
