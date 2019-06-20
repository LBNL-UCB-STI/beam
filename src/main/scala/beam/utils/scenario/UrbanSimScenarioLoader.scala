package beam.utils.scenario

import java.util.Random

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleCategory}
import beam.router.Modes.BeamMode
import beam.sim.BeamScenario
import beam.sim.common.GeoUtils
import beam.sim.vehicles.VehiclesAdjustment
import beam.utils.RandomUtils
import beam.utils.plan.sampling.AvailableModeUtils
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.math3.distribution.UniformRealDistribution
import org.matsim.api.core.v01.population.Population
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.population.PopulationUtils
import org.matsim.core.scenario.MutableScenario
import org.matsim.households._
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

class UrbanSimScenarioLoader(
  var scenario: MutableScenario,
  val beamScenario: BeamScenario,
  val scenarioSource: ScenarioSource,
  val geo: GeoUtils
) extends LazyLogging {

  val population: Population = scenario.getPopulation

  val availableModes: String = BeamMode.allModes.map(_.value).mkString(",")

  def loadScenario(): Scenario = {
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

    // beamServices.personHouseholds is used later on in PopulationAdjustment.createAttributesOfIndividual when we
    logger.info("Applying persons...")
    applyPersons(personsWithPlans)

    logger.info("Applying plans...")
    applyPlans(plans)

    logger.info("The scenario loading is completed..")
    scenario
  }

  private def clear(): Unit = {
    scenario.getPopulation.getPersons.clear()
    scenario.getPopulation.getPersonAttributes.clear()
    scenario.getHouseholds.getHouseholds.clear()
    scenario.getHouseholds.getHouseholdAttributes.clear()

    beamScenario.privateVehicles.clear()
  }

  private[utils] def getPersonsWithPlan(
    persons: Iterable[PersonInfo],
    plans: Iterable[PlanElement]
  ): Iterable[PersonInfo] = {
    val personIdsWithPlan = plans.map(_.personId).toSet
    persons.filter(person => personIdsWithPlan.contains(person.personId))
  }

  private def drawFromBinomial(randomSeed: java.util.Random, nTrials: Int, p: Double): Int = {
    Seq.fill(nTrials)(randomSeed.nextDouble).count(_ < p)
  }

  private[utils] def applyHousehold(
    households: Iterable[HouseholdInfo],
    householdIdToPersons: Map[HouseholdId, Iterable[PersonInfo]]
  ): Unit = {
    val scenarioHouseholdAttributes = scenario.getHouseholds.getHouseholdAttributes
    val scenarioHouseholds = scenario.getHouseholds.getHouseholds

    var vehicleCounter: Int = 0
    var initialVehicleCounter: Int = 0
    var totalCarCount: Int = 0

    val scaleFactor = beamScenario.beamConfig.beam.agentsim.agents.vehicles.fractionOfInitialVehicleFleet

    val vehiclesAdjustment = VehiclesAdjustment.getVehicleAdjustment(beamScenario)
    val realDistribution: UniformRealDistribution = new UniformRealDistribution()
    realDistribution.reseedRandomGenerator(beamScenario.beamConfig.matsim.modules.global.randomSeed)

    assignVehicles(households).foreach {
      case (householdInfo, nVehicles) =>
        val id = Id.create(householdInfo.householdId.id, classOf[org.matsim.households.Household])
        val household = new HouseholdsFactoryImpl().createHousehold(id)
        val coord = if (beamScenario.beamConfig.beam.exchange.scenario.convertWgs2Utm) {
          geo.wgs2Utm(new Coord(householdInfo.locationX, householdInfo.locationY))
        } else {
          new Coord(householdInfo.locationX, householdInfo.locationY)
        }

        household.setIncome(new IncomeImpl(householdInfo.income, Income.IncomePeriod.year))

        householdIdToPersons.get(householdInfo.householdId) match {
          case Some(persons) =>
            val personIds = persons.map(x => Id.createPersonId(x.personId.id)).toList.asJava
            household.setMemberIds(personIds)
          case None =>
            logger.warn(s"Could not find persons for the `household_id` '${householdInfo.householdId}'")
        }

        val vehicleTypes = vehiclesAdjustment
          .sampleVehicleTypesForHousehold(
            numVehicles = nVehicles,
            vehicleCategory = VehicleCategory.Car,
            householdIncome = household.getIncome.getIncome,
            householdSize = household.getMemberIds.size,
            householdPopulation = null,
            householdLocation = coord,
            realDistribution
          )
          .toBuffer

        beamScenario.vehicleTypes.values
          .find(_.vehicleCategory == VehicleCategory.Bike) match {
          case Some(vehType) =>
            vehicleTypes.append(vehType)
          case None =>
            throw new RuntimeException("Bike not found in vehicle types.")
        }
        initialVehicleCounter += householdInfo.cars
        totalCarCount += vehicleTypes.count(_.vehicleCategory.toString == "Car")

        val vehicleIds = new java.util.ArrayList[Id[Vehicle]]
        vehicleTypes.foreach { beamVehicleType =>
          val vt = VehicleUtils.getFactory.createVehicleType(Id.create(beamVehicleType.id, classOf[VehicleType]))
          val vehicle = VehicleUtils.getFactory.createVehicle(Id.createVehicleId(vehicleCounter), vt)
          vehicleIds.add(vehicle.getId)
          val bvId = Id.create(vehicle.getId, classOf[BeamVehicle])
          val powerTrain = new Powertrain(beamVehicleType.primaryFuelConsumptionInJoulePerMeter)
          val beamVehicle = new BeamVehicle(bvId, powerTrain, beamVehicleType)
          beamScenario.privateVehicles.put(beamVehicle.id, beamVehicle)
          vehicleCounter = vehicleCounter + 1
        }
        household.setVehicleIds(vehicleIds)
        scenarioHouseholds.put(household.getId, household)
        scenarioHouseholdAttributes.putAttribute(household.getId.toString, "homecoordx", coord.getX)
        scenarioHouseholdAttributes.putAttribute(household.getId.toString, "homecoordy", coord.getY)

    }
    logger.info(
      s"Created $totalCarCount vehicles, scaling initial value of $initialVehicleCounter by a factor of $scaleFactor"
    )
  }

  // Iterable[(HouseholdInfo, List[BeamVehicleType])]

  private def assignVehicles(households: Iterable[HouseholdInfo]): Iterable[(HouseholdInfo, Int)] = {
    beamScenario.beamConfig.beam.agentsim.agents.vehicles.downsamplingMethod match {
      case "SECONDARY_VEHICLES_FIRST" =>
        val rand = new Random(beamScenario.beamConfig.matsim.modules.global.randomSeed)
        val hh_car_count = collection.mutable.Map(households.groupBy(_.cars).toSeq: _*)
        val totalCars = households.foldLeft(0)(_ + _.cars)
        val goalCarTotal = math
          .round(beamScenario.beamConfig.beam.agentsim.agents.vehicles.fractionOfInitialVehicleFleet * totalCars)
          .toInt
        var currentTotalCars = totalCars
        hh_car_count.keys.toSeq.sorted.reverse.foreach { key =>
          if (currentTotalCars > goalCarTotal) {
            if (currentTotalCars - hh_car_count(key).size > goalCarTotal) {
              currentTotalCars -= hh_car_count(key).size
              hh_car_count(key - 1) ++= hh_car_count(key)
              hh_car_count -= key
            } else {
              val householdsInGroup = hh_car_count(key).size
              val numberToRemain = householdsInGroup - (currentTotalCars - goalCarTotal)
              val shuffled = RandomUtils.shuffle(hh_car_count(key), rand)
              hh_car_count(key) = shuffled.take(numberToRemain)
              hh_car_count(key - 1) ++= shuffled.takeRight(householdsInGroup - numberToRemain)
              currentTotalCars -= (currentTotalCars - goalCarTotal)
            }
          }
        }
        val householdsOut = ArrayBuffer[HouseholdInfo]()
        val nVehiclesOut = ArrayBuffer[Int]()
        hh_car_count.toSeq.foreach { hhGroup =>
          householdsOut ++= hhGroup._2
          nVehiclesOut ++= ArrayBuffer.fill(hhGroup._2.size)(hhGroup._1)
        }
        householdsOut.zip(nVehiclesOut)
      case "RANDOM" =>
        val rand = new Random(beamScenario.beamConfig.matsim.modules.global.randomSeed)
        val nVehiclesOut = ArrayBuffer[Int]()
        households.foreach { household =>
          nVehiclesOut += drawFromBinomial(
            rand,
            household.cars,
            beamScenario.beamConfig.beam.agentsim.agents.vehicles.fractionOfInitialVehicleFleet
          )
        }
        households.zip(nVehiclesOut)
    }
  }

  private[utils] def applyPersons(persons: Iterable[PersonInfo]): Unit = {
    val personHouseholds = scenario.getHouseholds.getHouseholds
      .values()
      .asScala
      .flatMap(h => h.getMemberIds.asScala.map(_ -> h))
      .toMap

    persons.foreach { personInfo =>
      val person = population.getFactory.createPerson(Id.createPersonId(personInfo.personId.id))
      val personId = person.getId.toString
      val personAttrib = population.getPersonAttributes
      // FIXME Search for "householdId" in the code does not show any place where it used
      personAttrib.putAttribute(personId, "householdId", personInfo.householdId)
      // FIXME Search for "householdId" in the code does not show any place where it used
      personAttrib.putAttribute(personId, "rank", personInfo.rank)
      personAttrib.putAttribute(personId, "age", personInfo.age)

      val sexChar = if (personInfo.isFemale) "F" else "M"
      personAttrib.putAttribute(personId, "sex", sexChar)
      person.getAttributes.putAttribute("sex", sexChar)

      AvailableModeUtils.setAvailableModesForPerson_v2(
        beamScenario,
        person,
        personHouseholds(person.getId),
        population,
        availableModes.split(",")
      )
      population.addPerson(person)
    }
  }

  private[utils] def applyPlans(plans: Iterable[PlanElement]): Unit = {
    plans.foreach { planInfo =>
      val person = population.getPersons.get(Id.createPersonId(planInfo.personId.id))
      if (person != null) {
        var plan = person.getSelectedPlan
        if (plan == null) {
          plan = PopulationUtils.createPlan(person)
          person.addPlan(plan)
          person.setSelectedPlan(plan)
        }
        val planElement = planInfo.planElementType
        if (planElement.equalsIgnoreCase("leg")) {
          planInfo.legMode match {
            case Some(mode) =>
              PopulationUtils.createAndAddLeg(plan, mode)
            case None =>
              PopulationUtils.createAndAddLeg(plan, "")
          }
        } else if (planElement.equalsIgnoreCase("activity")) {
          assert(
            planInfo.activityLocationX.isDefined,
            s"planElement is `activity`, but `x` is None! planInfo: $planInfo"
          )
          assert(
            planInfo.activityLocationY.isDefined,
            s"planElement is `activity`, but `y` is None! planInfo: $planInfo"
          )
          val coord = if (beamScenario.beamConfig.beam.exchange.scenario.convertWgs2Utm) {
            geo.wgs2Utm(new Coord(planInfo.activityLocationX.get, planInfo.activityLocationY.get))
          } else {
            new Coord(planInfo.activityLocationX.get, planInfo.activityLocationY.get)
          }
          val activityType = planInfo.activityType.getOrElse(
            throw new IllegalStateException(
              s"planElement is `activity`, but `activityType` is None. planInfo: $planInfo"
            )
          )
          val act = PopulationUtils.createAndAddActivityFromCoord(plan, activityType, coord)
          planInfo.activityEndTime.foreach { endTime =>
            act.setEndTime(endTime * 60 * 60)
          }
        }
      }
    }
  }
}
