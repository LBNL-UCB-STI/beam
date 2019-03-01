package beam.utils

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleCategory}
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.sim.vehicles.VehiclesAdjustment
import beam.utils.plan.sampling.AvailableModeUtils
import beam.utils.scenario._
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population.{Person, Population}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.population.PopulationUtils
import org.matsim.core.scenario.MutableScenario
import org.matsim.households._
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}

import scala.collection.JavaConverters._
import scala.collection.parallel.immutable.ParMap

class ScenarioLoader(
  var scenario: MutableScenario,
  var beamServices: BeamServices,
  val scenarioReader: ScenarioReader
) extends LazyLogging {

  val population: Population = scenario.getPopulation
  val scenarioFolder: String = beamServices.beamConfig.beam.agentsim.agents.population.beamPopulationDirectory
  val fileExt: String = scenarioReader.inputType.toFileExt
  val buildingFilePath: String = s"$scenarioFolder/buildings.$fileExt"
  val personFilePath: String = s"$scenarioFolder/persons.$fileExt"
  val householdFilePath: String = s"$scenarioFolder/households.$fileExt"

  val planFilePath: String = s"$scenarioFolder/plans.$fileExt"
  val unitFilePath: String = s"$scenarioFolder/units.$fileExt"
  val parcelAttrFilePath: String = s"$scenarioFolder/parcel_attr.$fileExt"

  val availableModes: String = BeamMode.allModes.map(_.value).mkString(",")

  def getHouseholdIdToCoord(householdsWithMembers: Array[HouseholdInfo]): Map[String, Coord] = {
    val units = scenarioReader.readUnitsFile(unitFilePath)
    val parcelAttrs = scenarioReader.readParcelAttrFile(parcelAttrFilePath)
    val buildings = scenarioReader.readBuildingsFile(buildingFilePath)
    val unitIdToCoord = ProfilingUtils.timed("getUnitIdToCoord", x => logger.info(x)) {
      getUnitIdToCoord(units, parcelAttrs, buildings)
    }
    householdsWithMembers.map { hh =>
      val coord = unitIdToCoord.getOrElse(hh.unitId, {
        logger.warn(s"Could not find coordinate for `household` ${hh.householdId} and `unitId`'${hh.unitId}'")
        new Coord(0, 0)
      })
      hh.householdId -> coord
    }.toMap
  }

  def loadScenario(): Unit = {
    clear()

    val plans = scenarioReader.readPlansFile(planFilePath)
    logger.info(s"Read ${plans.size} plans")

    val personsWithPlans = {
      val persons = scenarioReader.readPersonsFile(personFilePath)
      logger.info(s"Read ${persons.size} persons")
      getPersonsWithPlan(persons, plans)
    }
    logger.info(s"There are ${personsWithPlans.size} persons with plans")

    val householdIdToPersons: Map[String, Array[PersonInfo]] = personsWithPlans.groupBy(_.householdId)

    val householdsWithMembers = {
      val households: Array[HouseholdInfo] = scenarioReader.readHouseholdsFile(householdFilePath)
      logger.info(s"Read ${households.size} households")
      households.filter(household => householdIdToPersons.contains(household.householdId))
    }
    logger.info(s"There are ${householdsWithMembers.size} non-empty households")

    val householdIdToCoord = getHouseholdIdToCoord(householdsWithMembers)

    logger.info("Applying households...")
    applyHousehold(householdsWithMembers, householdIdToCoord, householdIdToPersons)
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

  private[utils] def getUnitIdToCoord(
    units: Array[UnitInfo],
    parcelAttrs: Array[ParcelAttribute],
    buildings: Array[BuildingInfo]
  ): Map[String, Coord] = {
    val parcelIdToCoord: ParMap[String, Coord] = parcelAttrs.par.groupBy(_.primaryId).map {
      case (k, v) =>
        val pa = v.head
        val coord = if (beamServices.beamConfig.beam.agentsim.agents.population.convertWgs2Utm) {
          beamServices.geo.wgs2Utm(new Coord(pa.x, pa.y))
        } else {
          new Coord(pa.x, pa.y)
        }
        k -> coord
    }
    val buildingId2ToParcelId: ParMap[String, String] =
      buildings.par.groupBy(x => x.buildingId).map { case (k, v) => k -> v.head.parcelId }
    val unitIdToBuildingId: ParMap[String, String] =
      units.par.groupBy(_.unitId).map { case (k, v) => k -> v.head.buildingId }

    unitIdToBuildingId.map {
      case (unitId, buildingId) =>
        val coord = buildingId2ToParcelId.get(buildingId) match {
          case Some(parcelId) =>
            parcelIdToCoord.getOrElse(parcelId, {
              logger.warn(s"Could not find coordinate for `parcelId` '${parcelId}'")
              new Coord(0, 0)
            })
          case None =>
            logger.warn(s"Could not find `parcelId` for `building_id` '${buildingId}'")
            new Coord(0, 0)
        }
        unitId -> coord
    }.seq
  }

  private[utils] def getPersonsWithPlan(persons: Array[PersonInfo], plans: Array[PlanInfo]): Array[PersonInfo] = {
    val personIdsWithPlan = plans.map(_.personId).toSet
    persons.filter(person => personIdsWithPlan.contains(person.personId))
  }

  private[utils] def applyHousehold(
    households: Array[HouseholdInfo],
    householdIdToCoord: Map[String, Coord],
    householdIdToPersons: Map[String, Array[PersonInfo]]
  ): Unit = {
    val scenarioHouseholdAttributes = scenario.getHouseholds.getHouseholdAttributes
    val scenarioHouseholds = scenario.getHouseholds.getHouseholds

    var vehicleCounter: Int = 0

    households.foreach { householdInfo =>
      val id = Id.create(householdInfo.householdId, classOf[Household])
      val household = new HouseholdsFactoryImpl().createHousehold(id)
      val coord = householdIdToCoord.getOrElse(householdInfo.householdId, {
        logger.warn(s"Could not find coordinate for `householdId` '${householdInfo.householdId}'")
        new Coord(0, 0)
      })
      household.setIncome(new IncomeImpl(householdInfo.income, Income.IncomePeriod.year))

      householdIdToPersons.get(householdInfo.householdId) match {
        case Some(persons) =>
          val personIds = persons.map(x => Id.createPersonId(x.personId)).toList.asJava
          household.setMemberIds(personIds)
        case None =>
          logger.warn(s"Could not find persons for the `houshold_id` '${householdInfo.householdId}'")
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

  private[utils] def applyPersons(persons: Array[PersonInfo]): Unit = {
    persons.foreach { personInfo =>
      val person: Person = population.getFactory.createPerson(Id.createPersonId(personInfo.personId))
      val personId = person.getId.toString
      val personAttrib = population.getPersonAttributes
      personAttrib.putAttribute(personId, "householdId", personInfo.householdId)
      personAttrib.putAttribute(personId, "rank", personInfo.rank)
      personAttrib.putAttribute(personId, "age", personInfo.age)
      AvailableModeUtils.setAvailableModesForPerson_v2(beamServices, person, population, availableModes.split(","))
      population.addPerson(person)
    }
  }

  private[utils] def applyPlans(plans: Array[PlanInfo]): Unit = {
    plans.foreach { planInfo =>
      val person = population.getPersons.get(Id.createPersonId(planInfo.personId))
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
          val coord = if (beamServices.beamConfig.beam.agentsim.agents.population.convertWgs2Utm) {
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
