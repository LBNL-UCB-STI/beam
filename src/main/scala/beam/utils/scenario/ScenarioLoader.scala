package beam.utils.scenario

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleCategory}
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.sim.vehicles.VehiclesAdjustment
import beam.utils.plan.sampling.AvailableModeUtils
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population.Population
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.population.PopulationUtils
import org.matsim.core.scenario.MutableScenario
import org.matsim.households._
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}

import scala.collection.JavaConverters._

class ScenarioLoader(
  var scenario: MutableScenario,
  var beamServices: BeamServices,
  val scenarioSource: ScenarioSource
) extends LazyLogging {

  val population: Population = scenario.getPopulation

  val availableModes: String = BeamMode.allModes.map(_.value).mkString(",")

  def loadScenario(): Unit = {
    clear()

    val plans = scenarioSource.getPlans
    logger.info(s"Read ${plans.size} plans")

    val personsWithPlans = {
      val persons: Iterable[PersonInfo] = scenarioSource.getPersons
      logger.info(s"Read ${persons.size} persons")
      getPersonsWithPlan(persons, plans)
    }
    logger.info(s"There are ${personsWithPlans.size} persons with plans")

    val householdIdToPersons: Map[HouseholdId, Iterable[PersonInfo]] = personsWithPlans.groupBy(_.householdId)

    val householdsWithMembers = {
      val households = scenarioSource.getHousehold
      logger.info(s"Read ${households.size} households")
      households.filter(household => householdIdToPersons.contains(household.householdId))
    }
    logger.info(s"There are ${householdsWithMembers.size} non-empty households")

    logger.info("Applying households...")
    applyHousehold(householdsWithMembers, householdIdToPersons)
    // beamServices.privateVehicles is properly populated here, after `applyHousehold` call

    // We have to override personHouseholds because we just loaded new households
    beamServices.personHouseholds = scenario.getHouseholds.getHouseholds
      .values()
      .asScala
      .flatMap(h => h.getMemberIds.asScala.map(_ -> h))
      .toMap
    // beamServices.personHouseholds is used later on in PopulationAdjustment.createAttributesOfIndividual when we
    logger.info("Applying persons...")
    applyPersons(personsWithPlans)

    logger.info("Applying plans...")
    applyPlans(plans)

    logger.info("The scenario loading is completed..")
  }

  private def clear(): Unit = {
    scenario.getPopulation.getPersons.clear()
    scenario.getPopulation.getPersonAttributes.clear()
    scenario.getHouseholds.getHouseholds.clear()
    scenario.getHouseholds.getHouseholdAttributes.clear()

    beamServices.privateVehicles.clear()
  }

  private[utils] def getPersonsWithPlan(
    persons: Iterable[PersonInfo],
    plans: Iterable[PlanInfo]
  ): Iterable[PersonInfo] = {
    val personIdsWithPlan = plans.map(_.personId).toSet
    persons.filter(person => personIdsWithPlan.contains(person.personId))
  }

  private[utils] def applyHousehold(
    households: Iterable[HouseholdInfo],
    householdIdToPersons: Map[HouseholdId, Iterable[PersonInfo]]
  ): Unit = {
    val scenarioHouseholdAttributes = scenario.getHouseholds.getHouseholdAttributes
    val scenarioHouseholds = scenario.getHouseholds.getHouseholds

    var vehicleCounter: Int = 0

    households.foreach { householdInfo =>
      val id = Id.create(householdInfo.householdId.id, classOf[org.matsim.households.Household])
      val household = new HouseholdsFactoryImpl().createHousehold(id)
      val coord = if (beamServices.beamConfig.beam.exchange.scenario.convertWgs2Utm) {
        beamServices.geo.wgs2Utm(new Coord(householdInfo.x, householdInfo.y))
      } else {
        new Coord(householdInfo.x, householdInfo.y)
      }

      household.setIncome(new IncomeImpl(householdInfo.income, Income.IncomePeriod.year))

      householdIdToPersons.get(householdInfo.householdId) match {
        case Some(persons) =>
          val personIds = persons.map(x => Id.createPersonId(x.personId.id)).toList.asJava
          household.setMemberIds(personIds)
        case None =>
          logger.warn(s"Could not find persons for the `household_id` '${householdInfo.householdId}'")
      }
      val vehicleTypes = VehiclesAdjustment
        .getVehicleAdjustment(beamServices)
        .sampleVehicleTypesForHousehold(
          numVehicles = householdInfo.cars.toInt,
          vehicleCategory = VehicleCategory.Car,
          householdIncome = household.getIncome.getIncome,
          householdSize = household.getMemberIds.size,
          householdPopulation = null,
          householdLocation = coord
        )

      val vehicleIds = new java.util.ArrayList[Id[Vehicle]]
      vehicleTypes.foreach { beamVehicleType =>
        val vt = VehicleUtils.getFactory.createVehicleType(Id.create(beamVehicleType.id, classOf[VehicleType]))
        val vehicle = VehicleUtils.getFactory.createVehicle(Id.createVehicleId(vehicleCounter), vt)
        vehicleIds.add(vehicle.getId)
        val bvId = Id.create(vehicle.getId, classOf[BeamVehicle])
        val powerTrain = new Powertrain(beamVehicleType.primaryFuelConsumptionInJoulePerMeter)
        val beamVehicle = new BeamVehicle(bvId, powerTrain, beamVehicleType)
        beamServices.privateVehicles.put(beamVehicle.id, beamVehicle)
        vehicleCounter = vehicleCounter + 1
      }
      household.setVehicleIds(vehicleIds)
      scenarioHouseholds.put(household.getId, household)
      scenarioHouseholdAttributes.putAttribute(household.getId.toString, "homecoordx", coord.getX)
      scenarioHouseholdAttributes.putAttribute(household.getId.toString, "homecoordy", coord.getY)

    }
  }

  private[utils] def applyPersons(persons: Iterable[PersonInfo]): Unit = {
    persons.foreach { personInfo =>
      val person = population.getFactory.createPerson(Id.createPersonId(personInfo.personId.id))
      val personId = person.getId.toString
      val personAttrib = population.getPersonAttributes
      // FIXME Search for "householdId" in the code does not show any place where it used
      personAttrib.putAttribute(personId, "householdId", personInfo.householdId)
      // FIXME Search for "householdId" in the code does not show any place where it used
      personAttrib.putAttribute(personId, "rank", personInfo.rank)
      personAttrib.putAttribute(personId, "age", personInfo.age)
      AvailableModeUtils.setAvailableModesForPerson_v2(beamServices, person, population, availableModes.split(","))
      population.addPerson(person)
    }
  }

  private[utils] def applyPlans(plans: Iterable[PlanInfo]): Unit = {
    plans.foreach { planInfo =>
      val person = population.getPersons.get(Id.createPersonId(planInfo.personId.id))
      if (person != null) {
        var plan = person.getSelectedPlan
        if (plan == null) {
          plan = PopulationUtils.createPlan(person)
          person.addPlan(plan)
          person.setSelectedPlan(plan)
        }
        val planElement = planInfo.planElement
        if (planElement.equalsIgnoreCase("leg")) {
          planInfo.mode match {
            case Some(mode) =>
              PopulationUtils.createAndAddLeg(plan, mode)
            case None =>
              PopulationUtils.createAndAddLeg(plan, "")
          }
        } else if (planElement.equalsIgnoreCase("activity")) {
          assert(planInfo.x.isDefined, s"planElement is `activity`, but `x` is None! planInfo: $planInfo")
          assert(planInfo.y.isDefined, s"planElement is `activity`, but `y` is None! planInfo: $planInfo")
          val coord = if (beamServices.beamConfig.beam.exchange.scenario.convertWgs2Utm) {
            beamServices.geo.wgs2Utm(new Coord(planInfo.x.get, planInfo.y.get))
          } else {
            new Coord(planInfo.x.get, planInfo.y.get)
          }
          val activityType = planInfo.activityType.getOrElse(
            throw new IllegalStateException(
              s"planElement is `activity`, but `activityType` is None. planInfo: $planInfo"
            )
          )
          val act = PopulationUtils.createAndAddActivityFromCoord(plan, activityType, coord)
          planInfo.endTime.foreach { endTime =>
            act.setEndTime(endTime * 60 * 60)
          }
        }
      }
    }
  }
}
