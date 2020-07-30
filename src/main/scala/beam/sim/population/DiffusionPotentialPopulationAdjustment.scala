package beam.sim.population

import java.util.Random

import beam.sim.{BeamScenario, BeamServices}
import beam.sim.common.GeoUtils
import beam.sim.population.DiffusionPotentialPopulationAdjustment._
import org.joda.time.DateTime
import org.matsim.api.core.v01.population.{Activity, Person, Plan, Population}
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.households.Household

import scala.collection.JavaConverters._

case class DiffusionPotentialPopulationAdjustment(beamServices: BeamServices) extends PopulationAdjustment {

  override lazy val scenario: Scenario = beamServices.matsimServices.getScenario
  override lazy val beamScenario: BeamScenario = beamServices.beamScenario

  val rand: Random = new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)
  val geo: GeoUtils = beamServices.geo

  override def updatePopulation(scenario: Scenario): Population = {
    val population = scenario.getPopulation

    removeModeAll(population, RIDE_HAIL, RIDE_HAIL_TRANSIT)

    adjustPopulationByDiffusionPotential(scenario, RIDE_HAIL, RIDE_HAIL_TRANSIT)

    population
  }

  def adjustPopulationByDiffusionPotential(scenario: Scenario, modes: String*): Unit = {
    val population = scenario.getPopulation

    scenario.getPopulation.getPersons.forEach {
      case (_, person: Person) =>
        val personId = person.getId.toString

        val diffPotential = limitToZeroOne(computeRideHailDiffusionPotential(scenario, person))
        //        computeAutomatedVehicleDiffusionPotential(scenario, person)

        if (diffPotential > rand.nextDouble()) {
          modes.foreach(mode => addMode(population, personId, mode))
        }
    }
  }

  def limitToZeroOne(d: Double): Double = math.max(math.min(d, 1.0), 0.0)

  def computeRideHailDiffusionPotential(scenario: Scenario, person: Person): Double = {

    val age = person.getAttributes.getAttribute(PERSON_AGE).asInstanceOf[Int]

    if (age > 18) { // if above 18

      lazy val household = findHousehold(scenario, person.getId)
      val income = household.fold(0)(_.getIncome.getIncome.toInt)
      val distanceToPD = getDistanceToPD(person.getPlans.get(0))

      (if (isBornIn80s(age)) 0.2654 else if (isBornIn90s(age)) 0.2706 else 0) +
      (if (household.nonEmpty && hasChildUnder8(household.get, scenario.getPopulation)) -0.1230 else 0) +
      (if (isIncomeAbove200K(income)) 0.1252 else 0) +
      (if (distanceToPD > 10 && distanceToPD <= 20) 0.0997
       else if (distanceToPD > 20 && distanceToPD <= 50) 0.0687
       else 0) +
      0.1947 // Constant
    } else {
      0
    }
  }

  def getDistanceToPD(plan: Plan): Double = {
    val activities = plan.getPlanElements.asScala.filter(_.isInstanceOf[Activity]).map(_.asInstanceOf[Activity])

    val home = activities.find(isHome).head

    val maxDurationActivity = activities.toList.sliding(2).maxBy(activityDuration).lastOption

    val pd = activities.find(isWork).getOrElse(activities.find(isSchool).getOrElse(maxDurationActivity.getOrElse(home)))

    activityDistanceInMiles(home, pd) * 1.4
  }

  def activityDistanceInMiles(orig: Activity, dest: Activity): Double = {
    geo.distUTMInMeters(orig.getCoord, dest.getCoord) * 0.000621371
  }

  def computeAutomatedVehicleDiffusionPotential(scenario: Scenario, person: Person): Double = {
    lazy val household = findHousehold(scenario, person.getId)
    val age = person.getAttributes.getAttribute(PERSON_AGE).asInstanceOf[Int]
    val sex = person.getAttributes.getAttribute(PERSON_SEX).toString
    val income = household.fold(0)(_.getIncome.getIncome.toInt)

    (if (isBornIn40s(age)) 0.1296 else if (isBornIn90s(age)) 0.2278 else 0) +
    (if (isIncome75to150K(income)) 0.0892
     else if (isIncome150to200K(income)) 0.1410
     else if (isIncomeAbove200K(income)) 0.1925
     else 0) +
    (if (isFemale(sex)) -0.2513 else 0) +
    0.4558 // Constant
  }
}

object DiffusionPotentialPopulationAdjustment {
  val PERSON_AGE = "age"
  val PERSON_SEX = "sex"
  val RIDE_HAIL = "ride_hail"
  val RIDE_HAIL_TRANSIT = "ride_hail_transit"

  lazy val currentYear: Int = 2018 // Year of Whole Traveler SF Bay Survey // DateTime.now().year().get()

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

  def activityDuration(activities: List[Activity]): Double = {
    if (activities.size < 2) 0 else activities(1).getEndTime - activities.head.getEndTime
  }

  def hasChildUnder8(household: Household, population: Population): Boolean = {
    household.getMemberIds.asScala
      .exists(m => findPerson(population, m).forall(_.getAttributes.getAttribute(PERSON_AGE).asInstanceOf[Int] < 8))
  }

  def findPerson(population: Population, personId: Id[Person]): Option[Person] = {
    val persons = population.getPersons.values().asScala
    persons.collectFirst { case person if person.getId.equals(personId) => person }
  }

  def findHousehold(scenario: Scenario, personId: Id[Person]): Option[Household] = {
    val households = scenario.getHouseholds.getHouseholds.values().asScala
    households.collectFirst { case household if household.getMemberIds.contains(personId) => household }
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
