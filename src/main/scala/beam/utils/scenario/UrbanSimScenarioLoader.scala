package beam.utils.scenario

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleCategory}
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

import scala.collection.Iterable
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Random

class UrbanSimScenarioLoader(
  var scenario: MutableScenario,
  val beamScenario: BeamScenario,
  val scenarioSource: ScenarioSource,
  val geo: GeoUtils
) extends LazyLogging {

  implicit val ex: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  val population: Population = scenario.getPopulation

  val availableModes: String = BeamMode.allModes.map(_.value).mkString(",")

  val rand: Random = new Random(beamScenario.beamConfig.matsim.modules.global.randomSeed)

  def loadScenario(): Scenario = {
    clear()

    val plansF = Future {
      val plans = scenarioSource.getPlans
      logger.info(s"Read ${plans.size} plans")
      plans
    }
    val personsF = Future {
      val persons: Iterable[PersonInfo] = scenarioSource.getPersons
      logger.info(s"Read ${persons.size} persons")
      persons
    }
    val householdsF = Future {
      val households = scenarioSource.getHousehold
      logger.info(s"Read ${households.size} households")
      households
    }
    val plans = Await.result(plansF, 500.seconds)
    val persons = Await.result(personsF, 500.seconds)

    val personsWithPlans = getPersonsWithPlan(persons, plans)
    logger.info(s"There are ${personsWithPlans.size} persons with plans")

    val householdIdToPersons: Map[HouseholdId, Iterable[PersonInfo]] = personsWithPlans.groupBy(_.householdId)

    val households = Await.result(householdsF, 500.seconds)
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

  private def drawFromBinomial(randomSeed: Random, nTrials: Int, p: Double): Int = {
    Seq.fill(nTrials)(randomSeed.nextDouble).count(_ < p)
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

  case class PlanTripStats(
    departureTime: Double,
    origin: Coord,
    destination: Coord
  )

  case class PersonTravelStats(
    homeLocation: Option[Coord],
    tripStats: Seq[PlanTripStats]
  )

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

    val householdIdToPersonScore: Map[HouseholdId, Iterable[(PersonId, Double)]] =
      householdIdToPersons.map {
        case (hhId, persons) =>
          (hhId, persons.map(x => (x.personId, getPersonScore(x, personIdToTravelStats(x.personId)))))
      }

    val scaleFactor = beamScenario.beamConfig.beam.agentsim.agents.vehicles.fractionOfInitialVehicleFleet

    val vehiclesAdjustment = VehiclesAdjustment.getVehicleAdjustment(beamScenario)
    val realDistribution: UniformRealDistribution = new UniformRealDistribution()
    realDistribution.reseedRandomGenerator(beamScenario.beamConfig.matsim.modules.global.randomSeed)

    assignVehicles(households, householdIdToPersons, householdIdToPersonScore).foreach {
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
          val beamVehicle = new BeamVehicle(bvId, powerTrain, beamVehicleType, rand.nextInt)
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

  private def assignVehicles(
    households: Iterable[HouseholdInfo],
    householdIdToPersons: Map[HouseholdId, Iterable[PersonInfo]],
    householdIdToPersonScore: Map[HouseholdId, Iterable[(PersonId, Double)]]
  ): Iterable[(HouseholdInfo, Int)] = {
    beamScenario.beamConfig.beam.agentsim.agents.vehicles.downsamplingMethod match {
      case "SECONDARY_VEHICLES_FIRST" =>
        val numberOfWorkers = households.map(x => householdIdToPersons(x.householdId).size).sum
        val numberOfWorkersWithVehicles =
          households.map(x => math.min(x.cars, householdIdToPersons(x.householdId).size)).sum
        val rand = new Random(beamScenario.beamConfig.matsim.modules.global.randomSeed)
        val hh_car_count =
          collection.mutable.Map(collection.mutable.ArrayBuffer(households.toSeq: _*).groupBy(_.cars).toSeq: _*)
        val totalCars = households.foldLeft(0)(_ + _.cars)

        val goalCarTotal = math
          .round(beamScenario.beamConfig.beam.agentsim.agents.vehicles.fractionOfInitialVehicleFleet * totalCars)
          .toInt
        if (beamScenario.beamConfig.beam.agentsim.agents.vehicles.fractionOfInitialVehicleFleet < 1.0) {
          val numberOfWorkVehiclesToBeRemoved = math.max(numberOfWorkersWithVehicles - goalCarTotal, 0)
          val numberOfExcessVehiclesToBeRemoved = totalCars - goalCarTotal - numberOfWorkVehiclesToBeRemoved
          val personsToGetCarsRemoved = households
            .flatMap(
              x =>
                householdIdToPersonScore(x.householdId).toSeq
                  .sortBy(_._2)
                  .takeRight(x.cars) // for each household, assign vehicles to the people with the highest commute distances
            )
            .toSeq
            .sortBy(_._2) // sort all people with assigned cars by commute distance
            .map(_._1)
            .take(numberOfWorkVehiclesToBeRemoved) // Take the people with shortest commutes and remove their cars
            .toSet
          logger.info(
            s"Identified $numberOfWorkVehiclesToBeRemoved household vehicles with short commutes and $numberOfExcessVehiclesToBeRemoved excess vehicles to be removed"
          )
          val householdIdToPersonToHaveVehicleRemoved = householdIdToPersons
            .map(x => x._2.map(y => (x._1, y)))
            .flatten
            .filter(x => personsToGetCarsRemoved.contains(x._2.personId))
            .groupBy(_._1)

          var currentTotalCars = totalCars
          hh_car_count.keys.toSeq.sorted.reverse.foreach { key => // start with households with the most vehicles
            if ((currentTotalCars > (goalCarTotal + numberOfWorkVehiclesToBeRemoved)) & key > 0) {
              val numberOfHouseholdsWithThisManyVehicles = hh_car_count(key).size

              val (householdsWithExcessVehicles, householdsWithCorrectNumberOfVehicles) =
                hh_car_count(key).partition(x => key > householdIdToPersons(x.householdId).size)
              val numberOfExcessVehicles = householdsWithExcessVehicles.size
              logger.info(
                s"Identified $numberOfExcessVehicles excess vehicles from the $numberOfHouseholdsWithThisManyVehicles households with $key vehicles"
              )
              if (currentTotalCars - numberOfExcessVehicles > goalCarTotal) {
                logger.info(
                  s"Removing all $numberOfExcessVehicles excess vehicles"
                )
                currentTotalCars -= numberOfExcessVehicles
                hh_car_count(key - 1) ++= householdsWithExcessVehicles
                hh_car_count(key) = householdsWithCorrectNumberOfVehicles
              } else {
                val householdsInGroup = householdsWithExcessVehicles.size
                val numberToRemain = householdsInGroup - (currentTotalCars - goalCarTotal)
                logger.info(
                  s"Removing all but $numberToRemain of the $numberOfExcessVehicles excess vehicles"
                )
                val shuffled = rand.shuffle(householdsWithExcessVehicles)
                hh_car_count(key) = shuffled.take(numberToRemain) ++ householdsWithCorrectNumberOfVehicles
                hh_car_count(key - 1) ++= shuffled.takeRight(householdsInGroup - numberToRemain)
                currentTotalCars -= (householdsInGroup - numberToRemain)
              }
            }
          }
          logger.info(
            s"Currently $currentTotalCars are left, $numberOfWorkVehiclesToBeRemoved work vehicles are yet to be removed"
          )
          hh_car_count.keys.toSeq.sorted.foreach { key =>
            if (key > 0) {
              val initialNumberOfHouseholds = hh_car_count(key).size
              hh_car_count(key) = hh_car_count(key).filter(
                hh =>
                  householdIdToPersonToHaveVehicleRemoved.get(hh.householdId) match {
                    case Some(personIdsToRemove) =>
                      hh_car_count(key - personIdsToRemove.size) ++= Iterable(hh)
                      currentTotalCars -= personIdsToRemove.size
                      false
                    case None =>
                      true
                }
              )
              val nRemoved = initialNumberOfHouseholds - hh_car_count(key).size
              logger.info(
                s"Originally had $initialNumberOfHouseholds work vehicles from households with $key workers, removed vehicles from $nRemoved of them"
              )
            }
          }
        } else {
          val numberOfWorkVehiclesToCreate =
            math.min(numberOfWorkers - numberOfWorkersWithVehicles, goalCarTotal - totalCars)
          val likelihoodToCreateVehicle = numberOfWorkVehiclesToCreate.toFloat / (numberOfWorkers - numberOfWorkersWithVehicles).toFloat
          var currentTotalCars = totalCars
          hh_car_count.keys.toSeq.sorted.reverse.foreach { key =>
            if (key > 0) {
              hh_car_count(key) = hh_car_count(key).filter { hh =>
                val nWorkers = householdIdToPersons(hh.householdId).size
                val nCars = key
                if (nWorkers > nCars) {
                  val numToCreate = drawFromBinomial(rand, nWorkers - nCars, likelihoodToCreateVehicle)
                  if (numToCreate == 0) {
                    true
                  } else {
                    if (hh_car_count.contains(key + numToCreate)) {
                      hh_car_count(key + numToCreate) ++= Iterable(hh)
                      currentTotalCars += numToCreate
                    } else {
                      hh_car_count(key + numToCreate) = ArrayBuffer(hh)
                      currentTotalCars += numToCreate
                    }
                    false
                  }
                } else { true }
              }
            }
          }
          logger.info(
            s"Originally had $numberOfWorkersWithVehicles work vehicles and now have $currentTotalCars of them, with a goal of making $numberOfWorkVehiclesToCreate"
          )
        }

        val householdsOut = ArrayBuffer[HouseholdInfo]()
        val nVehiclesOut = ArrayBuffer[Int]()
        hh_car_count.toSeq.foreach {
          case (nVehicles, householdIds) =>
            householdsOut ++= householdIds
            nVehiclesOut ++= ArrayBuffer.fill(householdIds.size)(nVehicles)
        }
        val totalVehiclesOut = nVehiclesOut.sum
        logger.info(
          s"Ended up with $totalVehiclesOut vehicles"
        )
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
