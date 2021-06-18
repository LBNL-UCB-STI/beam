package beam.utils.scenario

import beam.agentsim.agents.household.HouseholdFleetManager
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleCategory, VehicleManager}
import beam.router.Modes.BeamMode
import beam.sim.BeamScenario
import beam.sim.common.GeoUtils
import beam.sim.vehicles.VehiclesAdjustment
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
import scala.collection.{mutable, Iterable}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.math.{max, min, round}
import scala.util.Random

import beam.utils.SequenceUtils

class UrbanSimScenarioLoader(
  var scenario: MutableScenario,
  val beamScenario: BeamScenario,
  val scenarioSource: ScenarioSource,
  val geo: GeoUtils,
  val previousRunPlanMerger: Option[PreviousRunPlanMerger] = None,
) extends LazyLogging {

  private implicit val ex: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  private val population: Population = scenario.getPopulation

  private val availableModes: String = BeamMode.allModes.map(_.value).mkString(",")

  private val rand: Random = new Random(beamScenario.beamConfig.matsim.modules.global.randomSeed)

  def loadScenario(): (Scenario, Boolean) = {
    clear()

    val wereCoordinatesInWGS = beamScenario.beamConfig.beam.exchange.scenario.convertWgs2Utm

    val plansF = Future {
      val plans = scenarioSource.getPlans
      logger.info(s"Read ${plans.size} plans")
      val activities = plans.view.filter { p =>
        p.activityType.exists(actType => actType.toLowerCase == "home")
      }
      val personIdsWithinRange =
        activities
          .filter { act =>
            val actCoord = new Coord(act.activityLocationX.get, act.activityLocationY.get)
            val wgsCoord = if (wereCoordinatesInWGS) geo.utm2Wgs(actCoord) else actCoord
            beamScenario.transportNetwork.streetLayer.envelope.contains(wgsCoord.getX, wgsCoord.getY)
          }
          .map { act =>
            act.personId
          }
          .toSet
      val planWithinRange = plans.filter(p => personIdsWithinRange.contains(p.personId))
      val filteredCnt = plans.size - planWithinRange.size
      if (filteredCnt > 0) {
        logger.info(s"Filtered out $filteredCnt plans. Total number of plans: ${planWithinRange.size}")
      }
      planWithinRange
    }
    val personsF = Future {
      val persons: Iterable[PersonInfo] = scenarioSource.getPersons
      logger.info(s"Read ${persons.size} persons")
      persons
    }
    val householdsF = Future {
      val households = scenarioSource.getHousehold
      logger.info(s"Read ${households.size} households")
      val householdIdsWithinBoundingBox = households.view
        .filter { hh =>
          val coord = new Coord(hh.locationX, hh.locationY)
          val wgsCoord = if (wereCoordinatesInWGS) geo.utm2Wgs(coord) else coord
          beamScenario.transportNetwork.streetLayer.envelope.contains(wgsCoord.getX, wgsCoord.getY)
        }
        .map { hh =>
          hh.householdId
        }
        .toSet

      val householdsInsideBoundingBox =
        households.filter(household => householdIdsWithinBoundingBox.contains(household.householdId))
      val filteredCnt = households.size - householdsInsideBoundingBox.size
      if (filteredCnt > 0) {
        logger.info(
          s"Filtered out $filteredCnt households. Total number of households: ${householdsInsideBoundingBox.size}"
        )
      }
      householdsInsideBoundingBox
    }
    val inputPlans = Await.result(plansF, 500.seconds)
    val persons = Await.result(personsF, 500.seconds)
    val households = Await.result(householdsF, 500.seconds)

    val (plans, plansMerged) = previousRunPlanMerger.map(_.merge(inputPlans)).getOrElse(inputPlans -> false)

    val householdIds = households.map(_.householdId.id).toSet

    val personsWithPlans = getPersonsWithPlan(persons, plans)
      .filter(p => householdIds.contains(p.householdId.id))
    logger.info(s"There are ${personsWithPlans.size} persons with plans")

    val householdIdToPersons: Map[HouseholdId, Iterable[PersonInfo]] = personsWithPlans.groupBy(_.householdId)

    val householdsWithMembers = households.filter(household => householdIdToPersons.contains(household.householdId))
    logger.info(s"There are ${householdsWithMembers.size} non-empty households")

    logger.info("Applying households...")
    applyHousehold(householdsWithMembers, householdIdToPersons, plans)
    // beamServices.privateVehicles is properly populated here, after `applyHousehold` call

    // beamServices.personHouseholds is used later on in PopulationAdjustment.createAttributesOfIndividual when we
    logger.info("Applying persons...")
    applyPersons(personsWithPlans)

    logger.info("Applying plans...")
    applyPlans(plans)

    logger.info("The scenario loading is completed..")
    scenario -> plansMerged
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

  private[utils] def applyHousehold(
    households: Iterable[HouseholdInfo],
    householdIdToPersons: Map[HouseholdId, Iterable[PersonInfo]],
    plans: Iterable[PlanElement]
  ): Unit = {
    val scenarioHouseholdAttributes = scenario.getHouseholds.getHouseholdAttributes
    val scenarioHouseholds = scenario.getHouseholds.getHouseholds

    var vehicleCounter: Int = 0
    var initialVehicleCounter: Int = 0
    var totalCarCount: Int = 0
    val personIdToTravelStats: Map[PersonId, PersonTravelStats] =
      plans
        .groupBy(_.personId)
        .map(x => (x._1, plansToTravelStats(x._2)))

    val personId2Score: Map[PersonId, Double] =
      householdIdToPersons.flatMap {
        case (_, persons) =>
          persons.map(x => x.personId -> getPersonScore(x, personIdToTravelStats(x.personId)))
      }

    val scaleFactor = beamScenario.beamConfig.beam.agentsim.agents.vehicles.fractionOfInitialVehicleFleet

    val vehiclesAdjustment = VehiclesAdjustment.getVehicleAdjustment(beamScenario)
    val realDistribution: UniformRealDistribution = new UniformRealDistribution()
    realDistribution.reseedRandomGenerator(beamScenario.beamConfig.matsim.modules.global.randomSeed)

    val bikeVehicleType = beamScenario.vehicleTypes.values
      .find(_.vehicleCategory == VehicleCategory.Bike)
      .getOrElse(throw new RuntimeException("Bike not found in vehicle types."))

    assignVehicles(households, householdIdToPersons, personId2Score).foreach {
      case (householdInfo, nVehicles) =>
        val id = Id.create(householdInfo.householdId.id, classOf[Household])
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

        if (rand.nextDouble() <= beamScenario.beamConfig.beam.agentsim.agents.vehicles.fractionOfPeopleWithBicycle) {
          vehicleTypes.append(bikeVehicleType)
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
          val beamVehicle = new BeamVehicle(
            bvId,
            powerTrain,
            beamVehicleType,
            randomSeed = rand.nextInt
          )
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

  private def getPersonScore(personInfo: PersonInfo, personTravelStats: PersonTravelStats): Double = {
    val distanceExcludingLastTrip =
      personTravelStats.tripStats.dropRight(1).map(x => geo.distUTMInMeters(x.origin, x.destination)).sum
    val tripTimePenalty = personTravelStats.tripStats
      .map(
        x =>
          if (x.departureTime < 6.0) {
            5000.0
          } else if (x.departureTime > 23.5) {
            5000.0
          } else {
            0.0
        }
      )
      .sum
    distanceExcludingLastTrip + tripTimePenalty
  }

  private def plansToTravelStats(planElements: Iterable[PlanElement]): PersonTravelStats = {
    val homeCoord = planElements.find(_.activityType.getOrElse("") == "Home") match {
      case Some(homeElement) =>
        Some(geo.wgs2Utm(new Coord(homeElement.activityLocationX.get, homeElement.activityLocationY.get)))
      case None =>
        None
    }
    val planTripStats = planElements.toSeq
      .filter(_.planElementType == "activity")
      .sliding(2)
      .flatMap {
        case Seq(firstElement, secondElement, _*) =>
          Some(
            PlanTripStats(
              firstElement.activityEndTime.getOrElse(0.0),
              geo.wgs2Utm(
                new Coord(firstElement.activityLocationX.getOrElse(0.0), firstElement.activityLocationY.getOrElse(0.0))
              ),
              geo.wgs2Utm(
                new Coord(
                  secondElement.activityLocationX.getOrElse(0.0),
                  secondElement.activityLocationY.getOrElse(0.0)
                )
              )
            )
          )
        case _ =>
          None
      }
      .toSeq
    PersonTravelStats(homeCoord, planTripStats)
  }

  /**
    *
    * @param households list of household ids
    * @param householdIdToPersons map of household id into list of person info
    * @param personId2Score map personId -> commute distance
    * @return sequence of household info -> new number of vehicles to assign
    */
  private[scenario] def assignVehicles(
    households: Iterable[HouseholdInfo],
    householdIdToPersons: Map[HouseholdId, Iterable[PersonInfo]],
    personId2Score: Map[PersonId, Double]
  ): Iterable[(HouseholdInfo, Int)] = {
    val fractionOfInitialVehicleFleet =
      beamScenario.beamConfig.beam.agentsim.agents.vehicles.fractionOfInitialVehicleFleet

    beamScenario.beamConfig.beam.agentsim.agents.vehicles.downsamplingMethod match {
      case "SECONDARY_VEHICLES_FIRST" =>
        val numberOfWorkers = households.map(x => householdIdToPersons(x.householdId).size).sum
        val numberOfWorkersWithVehicles =
          households.map(x => min(x.cars, householdIdToPersons(x.householdId).size)).sum

        val totalCars = households.map(_.cars).sum

        val goalCarTotal = round(fractionOfInitialVehicleFleet * totalCars).toInt
        val resultNumberOfCars2HouseHoldIds = if (fractionOfInitialVehicleFleet < 1.0) {
          downsampleCars(
            numberOfWorkersWithVehicles = numberOfWorkersWithVehicles,
            goalCarTotal = goalCarTotal,
            households = households,
            householdIdToPersons = householdIdToPersons,
            totalCars = totalCars,
            personId2Score = personId2Score,
          )
        } else {
          upsampleCars(
            numberOfWorkersWithVehicles = numberOfWorkersWithVehicles,
            goalCarTotal = goalCarTotal,
            households = households,
            householdIdToPersons = householdIdToPersons,
            totalCars = totalCars,
            numberOfWorkers = numberOfWorkers
          )
        }

        val result = resultNumberOfCars2HouseHoldIds.flatMap {
          case (nVehicles, householdIds) =>
            householdIds.map(_ -> nVehicles)
        }
        val totalVehiclesOut = result.values.sum
        logger.info(
          s"Ended up with $totalVehiclesOut vehicles"
        )
        result
      case "RANDOM" =>
        households.map { household =>
          household -> drawFromBinomial(
            household.cars,
            fractionOfInitialVehicleFleet
          )
        }
    }
  }

  private def upsampleCars(
    numberOfWorkersWithVehicles: Int,
    goalCarTotal: Int,
    households: Iterable[HouseholdInfo],
    householdIdToPersons: Map[HouseholdId, Iterable[PersonInfo]],
    totalCars: Int,
    numberOfWorkers: Int,
  ): mutable.Map[Int, ArrayBuffer[HouseholdInfo]] = {
    val numberOfCars2HouseholdIds =
      mutable.Map(ArrayBuffer(households.toSeq: _*).groupBy(_.cars).toSeq: _*)

    val numberOfWorkVehiclesToCreate =
      min(numberOfWorkers - numberOfWorkersWithVehicles, goalCarTotal - totalCars)
    val likelihoodToCreateVehicle = numberOfWorkVehiclesToCreate.toDouble / (numberOfWorkers - numberOfWorkersWithVehicles).toDouble
    var currentTotalCars = totalCars
    numberOfCars2HouseholdIds.keys.toSeq.sorted(Ordering[Int].reverse).foreach { numberOfCars =>
      val newHouseHolds = new mutable.ArrayBuffer[HouseholdInfo]()

      numberOfCars2HouseholdIds(numberOfCars).foreach { hh =>
        val nWorkers = householdIdToPersons(hh.householdId).size
        val numToCreate = drawFromBinomial(nWorkers - numberOfCars, likelihoodToCreateVehicle)
        if (nWorkers <= numberOfCars || numToCreate == 0) {
          newHouseHolds += hh
        } else {
          numberOfCars2HouseholdIds.getOrElseUpdate(numberOfCars + numToCreate, ArrayBuffer()) += hh
          currentTotalCars += numToCreate
        }
      }

      numberOfCars2HouseholdIds(numberOfCars) = newHouseHolds
    }
    logger.info(
      s"Originally had $numberOfWorkersWithVehicles work vehicles and now have $currentTotalCars of them, with a goal of making $numberOfWorkVehiclesToCreate"
    )
    numberOfCars2HouseholdIds
  }

  private def drawFromBinomial(nTrials: Int, p: Double): Int = {
    var res = 0
    for (_ <- 0 until nTrials) {
      if (rand.nextDouble() < p) res += 1
    }
    res
  }

  private def downsampleCars(
    numberOfWorkersWithVehicles: Int,
    goalCarTotal: Int,
    households: Iterable[HouseholdInfo],
    householdIdToPersons: Map[HouseholdId, Iterable[PersonInfo]],
    totalCars: Int,
    personId2Score: Map[PersonId, Double]
  ): mutable.Map[Int, ArrayBuffer[HouseholdInfo]] = {
    val numberOfCars2HouseholdIds =
      mutable.Map(ArrayBuffer(households.toSeq: _*).groupBy(_.cars).toSeq: _*)

    val numberOfWorkVehiclesToBeRemoved = max(numberOfWorkersWithVehicles - goalCarTotal, 0)
    val numberOfExcessVehiclesToBeRemoved = totalCars - goalCarTotal - numberOfWorkVehiclesToBeRemoved
    val personsToGetCarsRemoved = households
      .flatMap { household =>
        householdIdToPersons(household.householdId)
          .map(p => p.personId -> personId2Score(p.personId))
          .toSeq
          .sortBy(_._2)
          .takeRight(household.cars) // for each household, assign vehicles to the people with the highest commute distances
      }
      .toSeq
      .sortBy(_._2) // sort all people with assigned cars by commute distance
      .map(_._1)
      .take(numberOfWorkVehiclesToBeRemoved) // Take the people with shortest commutes and remove their cars
      .toSet
    logger.info(
      s"Identified $numberOfWorkVehiclesToBeRemoved household vehicles with short commutes and $numberOfExcessVehiclesToBeRemoved excess vehicles to be removed"
    )
    val householdIdToPersonToHaveVehicleRemoved = householdIdToPersons
      .map { case (householdId, persons) => persons.map(person => householdId -> person) }
      .flatten
      .filter { case (_, personId) => personsToGetCarsRemoved.contains(personId.personId) }
      .groupBy { case (householdId, _) => householdId }

    var currentTotalCars = totalCars

    var currentNumberOfCars = SequenceUtils.maxOpt(numberOfCars2HouseholdIds.keys).getOrElse(0)
    while ((currentTotalCars > (goalCarTotal + numberOfWorkVehiclesToBeRemoved)) & currentNumberOfCars > 0) {
      val numberOfHouseholdsWithThisManyVehicles = numberOfCars2HouseholdIds(currentNumberOfCars).size

      val (householdsWithExcessVehicles, householdsWithCorrectNumberOfVehicles) =
        numberOfCars2HouseholdIds(currentNumberOfCars).partition(
          x => currentNumberOfCars > householdIdToPersons(x.householdId).size
        )
      val numberOfExcessVehicles = householdsWithExcessVehicles.size
      logger.info(
        s"Identified $numberOfExcessVehicles excess vehicles from the $numberOfHouseholdsWithThisManyVehicles households with $currentNumberOfCars vehicles"
      )
      if (currentTotalCars - numberOfExcessVehicles > goalCarTotal) {
        logger.info(
          s"Removing all $numberOfExcessVehicles excess vehicles"
        )
        currentTotalCars -= numberOfExcessVehicles
        numberOfCars2HouseholdIds.getOrElseUpdate(currentNumberOfCars - 1, ArrayBuffer()) ++= householdsWithExcessVehicles
        numberOfCars2HouseholdIds(currentNumberOfCars) = householdsWithCorrectNumberOfVehicles
      } else {
        val householdsInGroup = householdsWithExcessVehicles.size
        val numberToRemain = householdsInGroup - (currentTotalCars - goalCarTotal)
        logger.info(
          s"Removing all but $numberToRemain of the $numberOfExcessVehicles excess vehicles"
        )
        val shuffled = rand.shuffle(householdsWithExcessVehicles)
        numberOfCars2HouseholdIds(currentNumberOfCars) = shuffled.take(numberToRemain) ++ householdsWithCorrectNumberOfVehicles
        numberOfCars2HouseholdIds.getOrElseUpdate(currentNumberOfCars - 1, ArrayBuffer()) ++= shuffled.takeRight(
          householdsInGroup - numberToRemain
        )
        currentTotalCars -= (householdsInGroup - numberToRemain)
      }
      currentNumberOfCars = currentNumberOfCars - 1
    }
    logger.info(
      s"Currently $currentTotalCars are left, $numberOfWorkVehiclesToBeRemoved work vehicles are yet to be removed"
    )

    numberOfCars2HouseholdIds.keys.toStream
      .sorted(Ordering[Int].reverse)
      .takeWhile(currentNumberOfCars => currentNumberOfCars > 0 && currentTotalCars > goalCarTotal)
      .filter(numberOfCars2HouseholdIds.contains)
      .foreach { currentNumberOfCars =>
        val initialNumberOfHouseholds = numberOfCars2HouseholdIds(currentNumberOfCars).size
        if (initialNumberOfHouseholds != 0) {
          val newHouseHolds = new mutable.ArrayBuffer[HouseholdInfo]()

          numberOfCars2HouseholdIds(currentNumberOfCars).foreach { hh =>
            val personIdsToRemove = householdIdToPersonToHaveVehicleRemoved.getOrElse(hh.householdId, Nil)
            val carsToRemove = min(personIdsToRemove.size, currentTotalCars - goalCarTotal)
            if (carsToRemove > 0) {
              numberOfCars2HouseholdIds.getOrElseUpdate(currentNumberOfCars - carsToRemove, ArrayBuffer()) += hh
              currentTotalCars -= carsToRemove
            } else {
              newHouseHolds += hh
            }

            numberOfCars2HouseholdIds(currentNumberOfCars) = newHouseHolds
          }

          val nRemoved = initialNumberOfHouseholds - newHouseHolds.size
          logger.info(
            s"Originally had $initialNumberOfHouseholds work vehicles from households with $currentNumberOfCars workers, removed vehicles from $nRemoved of them"
          )
        }
      }
    numberOfCars2HouseholdIds
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
      val hh = personHouseholds(person.getId)
      val sexChar = if (personInfo.isFemale) "F" else "M"

      // FIXME Search for "householdId" in the code does not show any place where it used
      personAttrib.putAttribute(personId, "householdId", personInfo.householdId)
      // FIXME Search for "householdId" in the code does not show any place where it used
      personAttrib.putAttribute(personId, "rank", personInfo.rank)
      personAttrib.putAttribute(personId, "age", personInfo.age)
      personAttrib.putAttribute(personId, "income", hh.getIncome.getIncome)
      personAttrib.putAttribute(personId, "sex", sexChar)

      person.getAttributes.putAttribute("sex", sexChar)
      person.getAttributes.putAttribute("age", personInfo.age)
      person.getAttributes.putAttribute("income", hh.getIncome.getIncome)

      AvailableModeUtils.setAvailableModesForPerson_v2(
        beamScenario,
        person,
        hh,
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

  case class PlanTripStats(
    departureTime: Double,
    origin: Coord,
    destination: Coord
  )

  case class PersonTravelStats(
    homeLocation: Option[Coord],
    tripStats: Seq[PlanTripStats]
  )
}
