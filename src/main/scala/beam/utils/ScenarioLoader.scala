package beam.utils

import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleCategory}
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.sim.vehicles.VehiclesAdjustment
import beam.utils.BeamVehicleUtils.readCsvFileByLine
import beam.utils.plan.sampling.AvailableModeUtils
import beam.utils.scenario._
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population.{Person, Population}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.population.PopulationUtils
import org.matsim.core.scenario.MutableScenario
import org.matsim.households._
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.collection.JavaConverters._
import scala.collection.immutable.Iterable

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

  val availableModes: String = BeamMode.allTripModes.map(_.value).mkString(",")

  def getUnitIdToCoord(
    units: Array[UnitInfo],
    parcelAttrs: Array[ParcelAttribute],
    buildings: Array[BuildingInfo]
  ): Map[String, Coord] = {
    val parcelIdToCoord: Map[String, Coord] = parcelAttrs.groupBy(_.primaryId).map {
      case (k, v) =>
        val pa = v.head
        val coord = if (beamServices.beamConfig.beam.agentsim.agents.population.convertWgs2Utm) {
          beamServices.geo.wgs2Utm(new Coord(pa.x, pa.y))
        } else {
          new Coord(pa.x, pa.y)
        }
        k -> coord
    }
    val buildingId2ToParcelId: Map[String, String] =
      buildings.groupBy(x => x.buildingId).map { case (k, v)           => k -> v.head.parcelId }
    val unitIdToBuildingId = units.groupBy(_.unitId).map { case (k, v) => k -> v.head.buildingId }

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
    }
  }

  def loadScenario(): Unit = {
    clear()
    logger.info("Reading units...")
    val units = scenarioReader.readUnitsFile(unitFilePath)

    logger.info("Reading parcel attrs")
    val parcelAttrs = scenarioReader.readParcelAttrFile(parcelAttrFilePath)

    logger.info("Reading Buildings...")
    val buildings = scenarioReader.readBuildingsFile(buildingFilePath)

    val unitIdToCoord: Map[String, Coord] = ProfilingUtils.timed("getUnitIdToCoord", x => logger.info(x)) {
      getUnitIdToCoord(units, parcelAttrs, buildings)
    }

    logger.info("Reading Persons...")
    val persons = scenarioReader.readPersonsFile(personFilePath)
    logger.info("Applying Persons...")
    applyPersons(persons)

    logger.info("Reading plans...")
    val plans = scenarioReader.readPlansFile(planFilePath)
    logger.info("Applying plans...")
    applyPlans(plans)
    logger.info("Total persons loaded {}", scenario.getPopulation.getPersons.size())

    logger.info("Checking persons without selected plan...")
    val noPlanPersons = getPersonIdsWithoutPlan(population)
    logger.info("Removing persons without plan {}", noPlanPersons.size)
    removePersonsFromScenario(noPlanPersons)

    val withPlans = persons.filter(p => noPlanPersons.contains(p.personId))
    val householdIdToPersons: Map[String, Array[PersonInfo]] = withPlans.groupBy(_.householdId)

    logger.info("Reading Households...")
    val households: Array[HouseholdInfo] = scenarioReader.readHouseholdsFile(householdFilePath)
    logger.info("Applying Households...")
    applyHousehold(households, unitIdToCoord, householdIdToPersons)
    logger.info("Total households loaded {}", scenario.getHouseholds.getHouseholds.size())

    logger.info("Checking households without members...")
    val householdsNoMembers = getHouseholdsWithoutMembers(scenario.getHouseholds)
    logger.info("Removing households without members {}", householdsNoMembers.size)
    householdsNoMembers.foreach { h =>
      removeHouseholdVehicles(h)
      scenario.getHouseholds.getHouseholdAttributes.removeAllAttributes(h.getId.toString)
      scenario.getHouseholds.getHouseholds.remove(h.getId)
    }

    logger.info("The scenario loading is completed..")
  }

  private def getHouseholdsWithoutMembers(households: Households): scala.collection.Iterable[Household] = {
    households.getHouseholds.asScala
      .collect {
        case (hId, h) if h.getMemberIds.isEmpty =>
          h
      }
  }

  private def getPersonIdsWithoutPlan(population: Population): Set[String] = {
    population.getPersons.asScala.collect {
      case (k, v) if v.getSelectedPlan == null =>
        k.toString
    }.toSet
  }

  def removePersonsFromScenario(persons: Iterable[String]): Unit = {
    persons.foreach { personId =>
      val householdId =
        scenario.getPopulation.getPersonAttributes.getAttribute(personId, "householdId").asInstanceOf[String]
      scenario.getPopulation.getPersonAttributes.removeAllAttributes(personId)
      scenario.getPopulation.removePerson(Id.createPersonId(personId))
    }
  }

  private def clear(): Unit = {
    scenario.getPopulation.getPersons.clear()
    scenario.getPopulation.getPersonAttributes.clear()
    scenario.getHouseholds.getHouseholds.clear()
    scenario.getHouseholds.getHouseholdAttributes.clear()

    beamServices.privateVehicles.clear()
  }

  def applyHousehold(
    households: Array[HouseholdInfo],
    unitIdToCoord: Map[String, Coord],
    householdIdToPersons: Map[String, Array[PersonInfo]]
  ): Unit = {
    val scenarioHouseholdAttributes = scenario.getHouseholds.getHouseholdAttributes
    val scenarioHouseholds = scenario.getHouseholds.getHouseholds

    households.foreach { householdInfo =>
      val household =
        new HouseholdsFactoryImpl().createHousehold(Id.create(householdInfo.householdId, classOf[Household]))
      val coord = unitIdToCoord.getOrElse(householdInfo.unitId, {
        logger.warn(s"Could not find coordinate for `unitId` '${householdInfo.unitId}'")
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
      vehicleTypes.zipWithIndex.foreach {
        case (bvt, idx) =>
          val vehicleCounter = idx + 1
          val vt = VehicleUtils.getFactory.createVehicleType(Id.create(bvt.id, classOf[VehicleType]))
          val v = VehicleUtils.getFactory.createVehicle(Id.createVehicleId(vehicleCounter), vt)
          vehicleIds.add(v.getId)
          val bv = BeamVehicleUtils.getBeamVehicle(v, household, bvt)
          beamServices.privateVehicles.put(bv.id, bv)
      }

      household.setVehicleIds(vehicleIds)
      scenarioHouseholds.put(household.getId, household)
      scenarioHouseholdAttributes.putAttribute(household.getId.toString, "homecoordx", coord.getX)
      scenarioHouseholdAttributes.putAttribute(household.getId.toString, "homecoordy", coord.getY)
    }
  }

  def applyPersons(householdPersons: Array[PersonInfo]): Unit = {
    householdPersons.foreach { personInfo =>
      val person: Person = population.getFactory.createPerson(Id.createPersonId(personInfo.personId))

      population.getPersonAttributes.putAttribute(person.getId.toString, "householdId", personInfo.householdId)
      population.getPersonAttributes.putAttribute(person.getId.toString, "rank", personInfo.rank)
      population.getPersonAttributes.putAttribute(person.getId.toString, "age", personInfo.age)
      AvailableModeUtils.setAvailableModesForPerson(person, population, availableModes.split(","))
    }
  }

  def applyPlans(plans: Array[PlanInfo]): Unit = {
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
              logger.warn(s"$planInfo has planElement leg, but mode is not set!")
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

  def removeHouseholdVehicles(household: Household): Unit = {
    household.getVehicleIds.forEach(
      vehicleId => beamServices.privateVehicles.remove(Id.create(vehicleId.toString, classOf[BeamVehicle]))
    )
  }
}

object ScenarioLoader {
  private val logger = LoggerFactory.getLogger(this.getClass)

  var vehicleCounter = 1

  def readPersonsFile(
    filePath: String,
    population: Population,
    availableModes: String
  ): Map[Id[Household], ListBuffer[Id[Person]]] = {
    val map = readCsvFileByLine(filePath, mutable.HashMap[Id[Household], ListBuffer[Id[Person]]]()) {
      case (line, acc) =>
        val _personId: Id[Person] = Id.createPersonId(line.get("person_id"))
        val person: Person = population.getFactory.createPerson(_personId)

        val member_id: String = line.get("member_id")
        val age: String = line.get("age")
        val primary_commute_mode: String = line.get("primary_commute_mode")
        val relate: String = line.get("relate")
        val edu: String = line.get("edu")
        val sex: String = line.get("sex")
        val hours: String = line.get("hours")
        val hispanic: String = line.get("hispanic")
        val earning: String = line.get("earning")
        val race_id: String = line.get("race_id")
        val student: String = line.get("student")
        val work_at_home: String = line.get("work_at_home")
        val worker: String = line.get("worker")
        val household_id: String = line.get("household_id")
        val node_id_small: String = line.get("node_id_small")
        val node_id_walk: String = line.get("node_id_walk")
        val job_id: String = line.get("job_id")

        population.getPersonAttributes.putAttribute(person.getId.toString, "householdId", household_id)
        population.getPersonAttributes.putAttribute(person.getId.toString, "rank", 0)
        population.getPersonAttributes.putAttribute(person.getId.toString, "age", age.toInt)
        AvailableModeUtils.setAvailableModesForPerson(person, population, availableModes.split(","))

        val householdId: Id[Household] = Id.create(household_id, classOf[Household])

        val list: ListBuffer[Id[Person]] = acc.get(householdId) match {
          case Some(personList: ListBuffer[Id[Person]]) =>
            personList += _personId
            personList
          case None =>
            ListBuffer[Id[Person]](_personId)
        }
        acc += ((householdId, list))
    }
    map.toMap
  }

  def readUnitsFile(filePath: String): Map[String, java.util.Map[String, String]] = {
    val map = readCsvFileByLine(filePath, mutable.HashMap[String, java.util.Map[String, String]]()) {
      case (line, acc) =>
        val _line = new java.util.TreeMap[String, String]()
        _line.put("building_id", line.get("building_id"))
        //if(acc.size % 500000 == 0) logger.info(acc.size.toString)
        acc += ((line.get("unit_id"), _line))
    }
    map.toMap
  }

  def readParcelAttrFile(filePath: String): Map[String, java.util.Map[String, String]] = {
    val map = readCsvFileByLine(filePath, mutable.HashMap[String, java.util.Map[String, String]]()) {
      case (line, acc) =>
        val _line = new java.util.TreeMap[String, String]()
        _line.put("x", line.get("x"))
        _line.put("y", line.get("y"))
        acc += ((line.get("primary_id"), _line))
    }
    map.toMap
  }

  def readPlansFile(filePath: String, population: Population, beamServices: BeamServices): Unit = {
    readCsvFileByLine(filePath, Unit) {
      case (line, acc) =>
        val personId = line.get("personId")
        val planElement = line.get("planElement")
        val planElementId = line.get("planElementId")
        val activityType = line.get("activityType")
        val lng = line.get("x")
        val lat = line.get("y")
        val endTime = line.get("endTime")
        val mode = line.get("mode")

        val _personId = Id.createPersonId(personId)
        val person = population.getPersons.get(_personId)

        if (person != null) {

          var plan = person.getSelectedPlan
          if (plan == null) {
            plan = PopulationUtils.createPlan(person)
            person.addPlan(plan)
            person.setSelectedPlan(plan)
          }

          if (planElement.equalsIgnoreCase("leg")) PopulationUtils.createAndAddLeg(plan, mode)
          else if (planElement.equalsIgnoreCase("activity")) {
            val coord =
              beamServices.beamConfig.beam.agentsim.agents.population.convertWgs2Utm match {
                case true  => beamServices.geo.wgs2Utm(new Coord(lng.toDouble, lat.toDouble))
                case false => new Coord(lng.toDouble, lat.toDouble)
              }

            val act = PopulationUtils.createAndAddActivityFromCoord(plan, activityType, coord)
            if (endTime != null) {
              act.setEndTime(endTime.toDouble * 60 * 60)
            }
          }
        }
        Unit
    }
  }

  def readHouseholdsFile(
    filePath: String,
    scenario: MutableScenario,
    beamServices: BeamServices,
    householdPersons: Map[Id[Household], ListBuffer[Id[Person]]],
    units: Map[String, java.util.Map[String, String]],
    buildings: Map[String, java.util.Map[String, String]],
    parcelAttrs: Map[String, java.util.Map[String, String]]
  ): Unit = {

    val scenarioHouseholdAttributes = scenario.getHouseholds.getHouseholdAttributes
    val scenarioHouseholds = scenario.getHouseholds.getHouseholds
    val scenarioVehicles = beamServices.privateVehicles

    readCsvFileByLine(filePath, Unit) {

      case (line, acc) => {

        val _householdId = line.get("household_id")
        val householdId = Id.create(_householdId, classOf[Household])

        val numberOfVehicles = java.lang.Double.parseDouble(line.get("cars")).intValue()

        val objHousehold = new HouseholdsFactoryImpl().createHousehold(householdId)

        // Setting the coordinates
        val householdCoord: Coord = {

          if (line.keySet().contains("homecoordx") && line.keySet().contains("homecoordy")) {
            val x = line.get("homecoordx")
            val y = line.get("homecoordy")
            new Coord(java.lang.Double.parseDouble(x), java.lang.Double.parseDouble(y))
          } else {
            val householdUnitId = line.get("unit_id")
            if (householdUnitId != null) {
              units.get(householdUnitId) match {
                case Some(unit) =>
                  buildings.get(unit.get("building_id")) match {
                    case Some(building) => {
                      parcelAttrs.get(building.get("parcel_id")) match {
                        case Some(parcelAttr) =>
                          val lng = parcelAttr.get("x")
                          val lat = parcelAttr.get("y")

                          beamServices.beamConfig.beam.agentsim.agents.population.convertWgs2Utm match {
                            case true  => beamServices.geo.wgs2Utm(new Coord(lng.toDouble, lat.toDouble))
                            case false => new Coord(lng.toDouble, lat.toDouble)
                          }
                        case None => new Coord(0, 0)
                      }
                    }
                    case None => new Coord(0, 0)
                  }
                case None => new Coord(0, 0)
              }

            } else {
              new Coord(0, 0)
            }
          }
        }

        val incomeStr = line.get("income")

        if (incomeStr != null && !incomeStr.isEmpty) try {
          val _income = java.lang.Double.parseDouble(incomeStr)
          val income = new IncomeImpl(_income, Income.IncomePeriod.year)
          income.setCurrency("usd")
          objHousehold.setIncome(income)
        } catch {
          case e: Exception =>
            e.printStackTrace()
        }

        val p: Option[ListBuffer[Id[Person]]] = householdPersons.get(householdId)

        p match {
          case Some(p) => {
            objHousehold.setMemberIds(p.asJava)
          }

          case None =>
          //logger.info("no persons for household")
        }

        val vehicleTypes = VehiclesAdjustment
          .getVehicleAdjustment(beamServices)
          .sampleVehicleTypesForHousehold(
            numberOfVehicles,
            VehicleCategory.Car,
            objHousehold.getIncome.getIncome,
            objHousehold.getMemberIds.size,
            null,
            householdCoord
          )

        val vehicleIds = new java.util.ArrayList[Id[Vehicle]]
        vehicleTypes.foreach { bvt =>
          val vt = VehicleUtils.getFactory.createVehicleType(Id.create(bvt.id, classOf[VehicleType]))
          val v = VehicleUtils.getFactory.createVehicle(Id.createVehicleId(vehicleCounter), vt)
          vehicleIds.add(v.getId)
          val bv = BeamVehicleUtils.getBeamVehicle(v, objHousehold, bvt)
          scenarioVehicles.put(bv.id, bv)

          vehicleCounter = vehicleCounter + 1
        }

        objHousehold.setVehicleIds(vehicleIds)

        scenarioHouseholds.put(objHousehold.getId, objHousehold)
        scenarioHouseholdAttributes
          .putAttribute(objHousehold.getId.toString, "homecoordx", householdCoord.getX)
        scenarioHouseholdAttributes
          .putAttribute(objHousehold.getId.toString, "homecoordy", householdCoord.getY)
      }

      Unit
    }
  }

  def readBuildingsFile(
    filePath: String
  ): Map[String, java.util.Map[String, String]] = {

    val map = readCsvFileByLine(filePath, mutable.HashMap[String, java.util.Map[String, String]]()) {
      case (line, acc) =>
        val _bid = line.get("building_id")
        val _pid = line.get("parcel_id")

        val parcelId: String = if (_pid.indexOf(".") < 0) _pid else _pid.replaceAll("0*$", "").replaceAll("\\.$", "")

        val buildingId: String = if (_bid.indexOf(".") < 0) _bid else _bid.replaceAll("0*$", "").replaceAll("\\.$", "")

        val _line = new java.util.TreeMap[String, String]()
        _line.put("parcel_id", parcelId)

        acc += ((buildingId, _line))
    }
    map.toMap
  }

}
