package beam.utils.scenario

import beam.agentsim.agents.household.HouseholdFleetManager

import java.util

import scala.util.Random
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleManager}
import beam.router.Modes.BeamMode
import beam.sim.BeamScenario
import beam.sim.common.GeoUtils
import beam.utils.logging.ExponentialLazyLogging
import beam.utils.plan.sampling.AvailableModeUtils
import com.google.common.annotations.VisibleForTesting
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.{Activity, Leg, Person, Plan, Population}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.population.PopulationUtils
import org.matsim.core.population.routes.{NetworkRoute, RouteUtils}
import org.matsim.core.scenario.{MutableScenario, ScenarioBuilder}
import org.matsim.households.{Household, _}
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}

import scala.collection.JavaConverters._

class BeamScenarioLoader(
  val scenarioBuilder: ScenarioBuilder,
  var beamScenario: BeamScenario,
  val scenarioSource: ScenarioSource,
  val geo: GeoUtils
) extends ExponentialLazyLogging {

  import BeamScenarioLoader._

  type IdToAttributes = Map[String, Seq[(String, Double)]]

  private val availableModes: Seq[String] = BeamMode.allModes.map(_.value)

  private val rand: Random = new Random(beamScenario.beamConfig.matsim.modules.global.randomSeed)

  private lazy val plans: Iterable[PlanElement] = {
    val r = scenarioSource.getPlans
    logger.info(s"Read ${r.size} plans")
    r
  }

  private val scenario: MutableScenario = scenarioBuilder.build

  private def replaceHouseholdsAttributes(
    households: Households,
    loadedAttributes: IdToAttributes
  ): Unit = {
    val attributes = households.getHouseholdAttributes
    attributes.clear()
    loadedAttributes.foreach {
      case (id, listOfAttributes) =>
        listOfAttributes.foreach {
          case (name, value) =>
            attributes.putAttribute(id, name, value)
        }
    }
  }

  def loadScenario(): Scenario = {
    logger.info("The scenario loading started...")

    val personsWithPlans = {
      val persons: Iterable[PersonInfo] = scenarioSource.getPersons
      val personIdsWithPlanTmp = plans.map(_.personId).toSet
      val result = persons.filter(person => personIdsWithPlanTmp.contains(person.personId))
      logger.info(s"There are ${persons.size} people. ${result.size} have plans")
      result
    }

    val vehicles = scenarioSource.getVehicles

    val loadedHouseholds = scenarioSource.getHousehold

    val newHouseholds: Iterable[Household] =
      buildMatsimHouseholds(loadedHouseholds, personsWithPlans, vehicles)

    val households: Households = replaceHouseholds(scenario.getHouseholds, newHouseholds)

    beamScenario.privateVehicles.clear()
    vehicles
      .map(c => buildBeamVehicle(beamScenario.vehicleTypes, c, rand.nextInt))
      .foreach(v => beamScenario.privateVehicles.put(v.id, v))

    val scenarioPopulation: Population = buildPopulation(personsWithPlans)
    scenario.setPopulation(scenarioPopulation)
    updateAvailableModesForPopulation(scenario)

    replacePlansFromPopulation(scenarioPopulation, plans)

    val loadedAttributes = buildAttributesCoordinates(loadedHouseholds)
    replaceHouseholdsAttributes(households, loadedAttributes)

    logger.info("The scenario loading is completed.")
    scenario
  }

  private def replaceHouseholds(households: Households, newHouseholds: Iterable[Household]): Households = {
    logger.info("Applying households...")

    val matsimHouseholds = newHouseholds
      .map { hh =>
        (hh.getId, hh)
      }
      .toMap
      .asJava

    households.getHouseholds.clear()
    households.getHouseholds.putAll(matsimHouseholds)
    households
  }

  private[utils] def buildAttributesCoordinates(
    households: Iterable[HouseholdInfo]
  ): IdToAttributes = {
    households.map { householdInfo =>
      val newId = householdInfo.householdId.id

      val coord = buildCoordinates(householdInfo)

      newId -> Seq(("homecoordx", coord.getX), ("homecoordy", coord.getY))
    }.toMap
  }

  private def buildCoordinates(householdInfo: HouseholdInfo) = {
    if (beamScenario.beamConfig.beam.exchange.scenario.convertWgs2Utm) {
      geo.wgs2Utm(new Coord(householdInfo.locationX, householdInfo.locationY))
    } else {
      new Coord(householdInfo.locationX, householdInfo.locationY)
    }
  }

  @VisibleForTesting
  private[utils] def buildPopulation(persons: Iterable[PersonInfo]): Population = {
    logger.info("Applying persons...")
    val result = scenarioBuilder.buildPopulation

    persons.foreach { personInfo =>
      val person = result.getFactory.createPerson(Id.createPersonId(personInfo.personId.id))
      val personId = person.getId.toString

      val sexChar = if (personInfo.isFemale) "F" else "M"

      val personAttributes = result.getPersonAttributes
      personAttributes.putAttribute(personId, "householdId", personInfo.householdId)
      personAttributes.putAttribute(personId, "rank", personInfo.rank)
      personAttributes.putAttribute(personId, "age", personInfo.age)
      personAttributes.putAttribute(personId, "valueOfTime", personInfo.valueOfTime)
      personAttributes.putAttribute(personId, "sex", sexChar)
      personAttributes.putAttribute(personId, "excluded-modes", personInfo.excludedModes.mkString(","))
      person.getAttributes.putAttribute("sex", sexChar)
      person.getAttributes.putAttribute("age", personInfo.age)

      result.addPerson(person)
    }

    result
  }

  def updateAvailableModesForPopulation(scenarioToUpdate: MutableScenario): Unit = {
    val personHouseholds = scenarioToUpdate.getHouseholds.getHouseholds
      .values()
      .asScala
      .flatMap(h => h.getMemberIds.asScala.map(_ -> h))
      .toMap

    val population = scenarioToUpdate.getPopulation
    population.getPersons.asScala.values.foreach { person: Person =>
      // TODO: setAvailableModesForPerson_v2 - probable need to improve:
      // - build AttributesOfIndividual with many fields already filled at BuildPopulation method
      // - get the property attributesOfInidivual or create abd update person.customAttribute with this property
      // - update the property availableModes and update the person customProperty (possible twice) with attributesOfIndividual
      AvailableModeUtils.setAvailableModesForPerson_v2(
        beamScenario,
        person,
        personHouseholds(person.getId),
        population,
        availableModes
      )
    }

  }

  private[utils] def replacePlansFromPopulation(
    population: Population,
    plansElements: Iterable[PlanElement]
  ): Population = {
    logger.info("Applying plans...")

    plansElements.groupBy(_.personId).foreach {
      case (personId: PersonId, listOfElementsGroupedByPerson) =>
        listOfElementsGroupedByPerson.groupBy(_.planIndex).foreach {
          case (_, listOfElementsGroupedByPlan) if listOfElementsGroupedByPlan.nonEmpty =>
            val person = population.getPersons.get(Id.createPersonId(personId.id))

            val currentPlan = PopulationUtils.createPlan(person)
            currentPlan.setScore(listOfElementsGroupedByPlan.head.planScore)
            person.addPlan(currentPlan)

            val personWithoutSelectedPlan = person.getSelectedPlan == null
            val isCurrentPlanIndexSelected = listOfElementsGroupedByPlan.head.planSelected
            val isLastPlanIteration = person.getPlans.size() == listOfElementsGroupedByPerson.size
            if (personWithoutSelectedPlan && (isCurrentPlanIndexSelected || isLastPlanIteration)) {
              person.setSelectedPlan(currentPlan)
            }

            listOfElementsGroupedByPlan.foreach { planElement =>
              if (planElement.planElementType.equalsIgnoreCase("leg")) {
                buildAndAddLegToPlan(currentPlan, planElement)
              } else if (planElement.planElementType.equalsIgnoreCase("activity")) {
                buildAndAddActivityToPlan(currentPlan, planElement)
              }
            }
        }
    }
    population
  }

  private def buildAndAddActivityToPlan(currentPlan: Plan, planElement: PlanElement): Activity = {
    assertActivityHasLocation(planElement)
    val coord = if (beamScenario.beamConfig.beam.exchange.scenario.convertWgs2Utm) {
      geo.wgs2Utm(new Coord(planElement.activityLocationX.get, planElement.activityLocationY.get))
    } else {
      new Coord(planElement.activityLocationX.get, planElement.activityLocationY.get)
    }
    val activityType = planElement.activityType.getOrElse(
      throw new IllegalStateException(
        s"planElement is `activity`, but `activityType` is None. planInfo: $planElement"
      )
    )
    val act = PopulationUtils.createAndAddActivityFromCoord(currentPlan, activityType, coord)
    planElement.activityEndTime.foreach { endTime =>
      act.setEndTime(endTime)
    }
    act
  }

  private def buildAndAddLegToPlan(currentPlan: Plan, planElement: PlanElement): Leg = {
    val leg = PopulationUtils.createAndAddLeg(currentPlan, planElement.legMode.getOrElse(""))
    planElement.legDepartureTime.foreach(v => leg.setDepartureTime(v.toDouble))
    planElement.legTravelTime.foreach(v => leg.setTravelTime(v.toDouble))
    planElement.legMode.foreach(v => leg.setMode(v))

    val legRoute: NetworkRoute = {
      val links = planElement.legRouteLinks.map(v => Id.create(v, classOf[Link])).asJava
      if (links.isEmpty) {
        null
      } else {
        RouteUtils.createNetworkRoute(links, beamScenario.network)
      }
    }
    if (legRoute != null) {
      leg.setRoute(legRoute)
      planElement.legRouteDistance.foreach(legRoute.setDistance)
      planElement.legRouteStartLink.foreach(v => legRoute.setStartLinkId(Id.create(v, classOf[Link])))
      planElement.legRouteEndLink.foreach(v => legRoute.setEndLinkId(Id.create(v, classOf[Link])))
      planElement.legRouteTravelTime.foreach(v => legRoute.setTravelTime(v))
    }
    leg
  }

  private def assertActivityHasLocation(planInfo: PlanElement): Unit = {
    assert(
      planInfo.activityLocationX.isDefined,
      s"planElement is `activity`, but `x` is None! planInfo: $planInfo"
    )
    assert(
      planInfo.activityLocationY.isDefined,
      s"planElement is `activity`, but `y` is None! planInfo: $planInfo"
    )
  }
}

object BeamScenarioLoader extends ExponentialLazyLogging {

  private[utils] def buildMatsimHouseholds(
    households: Iterable[HouseholdInfo],
    people: Iterable[PersonInfo],
    vehicles: Iterable[VehicleInfo]
  ): Iterable[Household] = {
    val householdIdToVehicles = vehicles
      .groupBy(_.householdId)
      .map {
        case (id, vehicleInfo) => HouseholdId(id) -> vehicleInfo
      }

    val householdIdToPersons = people.groupBy(_.householdId)

    households.map { householdInfo =>
      val householdResult = new HouseholdsFactoryImpl().createHousehold(buildHouseholdId(householdInfo))

      householdResult.setIncome(buildIncome(householdInfo))
      householdResult.setMemberIds(buildMemberIdsAsJavaList(householdIdToPersons, householdInfo))
      val list = buildVehicleIdsAsJavaList(householdIdToVehicles, householdInfo)
      householdResult.setVehicleIds(list)

      householdResult
    }
  }

  private def buildHouseholdId(householdInfo: HouseholdInfo): Id[Household] = {
    Id.create(householdInfo.householdId.id, classOf[Household])
  }

  private def buildIncome(householdInfo: HouseholdInfo): Income = {
    new IncomeImpl(householdInfo.income, Income.IncomePeriod.year)
  }

  private def buildMemberIdsAsJavaList(
    householdIdToPersons: Map[HouseholdId, Iterable[PersonInfo]],
    householdInfo: HouseholdInfo
  ): util.List[Id[Person]] = {
    householdIdToPersons.get(householdInfo.householdId) match {
      case Some(persons) =>
        persons.map(x => Id.createPersonId(x.personId.id)).toList.asJava
      case None =>
        logger.warn(s"Could not find persons for the `household_id` '${householdInfo.householdId}'.")
        util.Collections.emptyList()
    }
  }

  def buildVehicleIdsAsJavaList(
    householdIdToVehicles: Map[HouseholdId, Iterable[VehicleInfo]],
    householdInfo: HouseholdInfo
  ): util.List[Id[Vehicle]] = {
    householdIdToVehicles.get(householdInfo.householdId) match {
      case Some(vehicles) =>
        vehicles.map(x => Id.createVehicleId(x.vehicleId)).toList.asJava
      case None =>
        logger.warn(s"Could not find vehicles for the `household_id` '${householdInfo.householdId}'")
        util.Collections.emptyList()
    }
  }

  def buildBeamVehicle(
    map: Map[Id[BeamVehicleType], BeamVehicleType],
    info: VehicleInfo,
    randomSeed: Int
  ): BeamVehicle = {
    val matsimVehicleType: VehicleType =
      VehicleUtils.getFactory.createVehicleType(Id.create(info.vehicleTypeId, classOf[VehicleType]))
    val matsimVehicle: Vehicle =
      VehicleUtils.getFactory.createVehicle(Id.createVehicleId(info.vehicleId), matsimVehicleType)

    val beamVehicleId = Id.create(matsimVehicle.getId, classOf[BeamVehicle])
    val beamVehicleTypeId = Id.create(info.vehicleTypeId, classOf[BeamVehicleType])

    val beamVehicleType = map(beamVehicleTypeId)

    val powerTrain = new Powertrain(beamVehicleType.primaryFuelConsumptionInJoulePerMeter)
    new BeamVehicle(
      beamVehicleId,
      powerTrain,
      beamVehicleType,
      randomSeed = randomSeed
    )
  }

}
