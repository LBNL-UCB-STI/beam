package beam.utils.scenario

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleManager}
import beam.router.Modes.BeamMode
import beam.sim.BeamScenario
import beam.sim.common.GeoUtils
import beam.sim.population.PopulationAdjustment.RIDEHAIL_SERVICE_SUBSCRIPTION
import beam.utils.logging.ExponentialLazyLogging
import beam.utils.plan.sampling.AvailableModeUtils
import com.google.common.annotations.VisibleForTesting
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population._
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.population.{PersonUtils, PopulationUtils}
import org.matsim.core.population.routes.{NetworkRoute, RouteUtils}
import org.matsim.core.scenario.{MutableScenario, ScenarioBuilder}
import org.matsim.core.utils.misc.OptionalTime
import org.matsim.households._
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}

import java.util
import java.util.concurrent.atomic.AtomicReference
import scala.collection.JavaConverters._
import scala.util.Random

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
    households.getHouseholds.values.asScala.map(_.getAttributes.clear())
    val householdIdStrToHousehold = households.getHouseholds.asScala.map { case (key, value) =>
      (key.toString, value)
    }.toMap
    loadedAttributes.foreach { case (id, listOfAttributes) =>
      listOfAttributes.foreach { case (name, value) =>
        HouseholdUtils.putHouseholdAttribute(householdIdStrToHousehold(id), name, value)
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
    beamScenario.privateVehicleInitialSoc.clear()
    for {
      vehicleInfo <- vehicles
      vehicle = buildBeamVehicle(beamScenario.vehicleTypes, vehicleInfo, rand.nextInt)
    } {
      beamScenario.privateVehicles.put(vehicle.id, vehicle)
      vehicleInfo.initialSoc.foreach(beamScenario.privateVehicleInitialSoc.put(vehicle.id, _))
    }

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

      val sexChar = if (personInfo.isFemale) "F" else "M"

      PopulationUtils.putPersonAttribute(person, "householdId", personInfo.householdId)
      PopulationUtils.putPersonAttribute(person, "householdId", personInfo.householdId)
      PopulationUtils.putPersonAttribute(person, "rank", personInfo.rank)
      PopulationUtils.putPersonAttribute(person, "age", personInfo.age)
      PopulationUtils.putPersonAttribute(person, "valueOfTime", personInfo.valueOfTime)
      PopulationUtils.putPersonAttribute(person, "sex", sexChar)
      PopulationUtils.putPersonAttribute(person, "excluded-modes", personInfo.excludedModes.mkString(","))
      PopulationUtils.putPersonAttribute(
        person,
        RIDEHAIL_SERVICE_SUBSCRIPTION,
        personInfo.rideHailServiceSubscription.mkString(",")
      )
      PopulationUtils.putPersonAttribute(person, "sex", sexChar)
      PopulationUtils.putPersonAttribute(person, "age", personInfo.age)
      PopulationUtils.putPersonAttribute(person, "industry", personInfo.industry.getOrElse(""))

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
        availableModes
      )
    }

  }

  private[utils] def replacePlansFromPopulation(
    population: Population,
    plansElements: Iterable[PlanElement]
  ): Population = {
    logger.info("Applying plans...")

    plansElements.groupBy(_.personId).foreach { case (personId: PersonId, listOfElementsGroupedByPerson) =>
      listOfElementsGroupedByPerson.groupBy(_.planIndex).foreach {
        case (_, listOfElementsGroupedByPlan) if listOfElementsGroupedByPlan.nonEmpty =>
          val person = population.getPersons.get(Id.createPersonId(personId.id))

          if (person == null) {
            logger.warn(
              "Could not find person {} while adding plans (maybe it doesn't belong to any household!?)",
              personId.id
            )
          } else {
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
              if (planElement.planElementType == PlanElement.Leg) {
                buildAndAddLegToPlan(currentPlan, planElement)
              } else if (planElement.planElementType == PlanElement.Activity) {
                buildAndAddActivityToPlan(currentPlan, planElement)
              }
            }
          }
      }
    }
    population
  }

  def buildAndAddActivityToPlan(currentPlan: Plan, planElement: PlanElement): Activity = {
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
      if (endTime == beam.UNDEFINED_TIME) act.setEndTimeUndefined()
      else act.setEndTime(endTime)
    }
    act
  }

  private def buildAndAddLegToPlan(currentPlan: Plan, planElement: PlanElement): Leg = {
    val leg = PopulationUtils.createAndAddLeg(currentPlan, planElement.legMode.getOrElse(""))
    planElement.legDepartureTime.foreach(departureTimeStr => {
      val departureTime = departureTimeStr.toDouble
      if (departureTime == beam.UNDEFINED_TIME) leg.setDepartureTimeUndefined()
      else leg.setDepartureTime(departureTime)
    })
    planElement.legTravelTime.foreach(travelTimeStr => {
      val travelTime = travelTimeStr.toDouble
      if (travelTime == beam.UNDEFINED_TIME) leg.setTravelTimeUndefined()
      else leg.setTravelTime(travelTime)
    })
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
      planElement.legRouteTravelTime.foreach(v => {
        if (v == beam.UNDEFINED_TIME) legRoute.setTravelTimeUndefined()
        else legRoute.setTravelTime(v)
      })
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
      .map { case (id, vehicleInfo) =>
        HouseholdId(id) -> vehicleInfo
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

    val vehicleManagerId =
      VehicleManager.createOrGetReservedFor(info.householdId, VehicleManager.TypeEnum.Household).managerId
    val powerTrain = new Powertrain(beamVehicleType.primaryFuelConsumptionInJoulePerMeter)
    new BeamVehicle(
      beamVehicleId,
      powerTrain,
      beamVehicleType,
      new AtomicReference(vehicleManagerId),
      randomSeed = randomSeed
    )
  }

}
