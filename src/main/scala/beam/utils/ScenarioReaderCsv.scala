package beam.utils

import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleCategory}
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.sim.vehicles.VehiclesAdjustment
import beam.utils.BeamVehicleUtils.readCsvFileByLine
import beam.utils.plan.sampling.AvailableModeUtils
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population.{Person, Population}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.population.PopulationUtils
import org.matsim.core.scenario.MutableScenario
import org.matsim.households._
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class ScenarioReaderCsv(var scenario: MutableScenario, var beamServices: BeamServices, val delimiter: String = ",")
  extends LazyLogging {

  val scenarioFolder: String = beamServices.beamConfig.beam.agentsim.agents.population.beamPopulationDirectory

  val buildingFilePath: String = scenarioFolder + "/buildings.csv"
  val personFilePath: String = scenarioFolder + "/persons.csv"
  val householdFilePath: String = scenarioFolder + "/households.csv"

  val planFilePath: String = scenarioFolder + "/plans.csv"
  val unitFilePath: String = scenarioFolder + "/units.csv"
  val parcelAttrFilePath: String = scenarioFolder + "/parcel_attr.csv"

  def loadScenario(): Unit = {

    scenario.getPopulation.getPersons.clear()
    scenario.getPopulation.getPersonAttributes.clear()
    scenario.getHouseholds.getHouseholds.clear()
    scenario.getHouseholds.getHouseholdAttributes.clear()

    beamServices.privateVehicles.clear()
    /////

    logger.info("Reading units...")
    val units = ScenarioReaderCsv.readUnitsFile(unitFilePath)

    logger.info("Reading parcel attrs")
    val parcelAttrs = ScenarioReaderCsv.readParcelAttrFile(parcelAttrFilePath)

    logger.info("Reading Buildings...")
    val buildings = ScenarioReaderCsv.readBuildingsFile(buildingFilePath)

    logger.info("Reading Persons...")
    val householdPersons = ScenarioReaderCsv.readPersonsFile(
      personFilePath,
      scenario.getPopulation,
      BeamMode.allTripModes.map(_.value).mkString(",")
    )

    logger.info("Reading plans...")
    ScenarioReaderCsv.readPlansFile(planFilePath, scenario.getPopulation, beamServices)

    logger.info("Total persons loaded {}", scenario.getPopulation.getPersons.size())
    logger.info("Checking persons without selected plan...")

    val listOfPersonsWithoutPlan: ListBuffer[Id[Person]] = ListBuffer()
    scenario.getPopulation.getPersons.forEach {
      case (pk: Id[Person], pv: Person) if (pv.getSelectedPlan == null) => listOfPersonsWithoutPlan += pk
      case _                                                            =>
    }

    logger.info("Removing persons without plan {}", listOfPersonsWithoutPlan.size)
    listOfPersonsWithoutPlan.foreach { p =>
      val _hId = scenario.getPopulation.getPersonAttributes.getAttribute(p.toString, "householdId")

      scenario.getPopulation.getPersonAttributes.removeAllAttributes(p.toString)

      scenario.getPopulation.removePerson(p)

      val hId = Id.create(_hId.toString, classOf[Household])
      householdPersons.get(hId) match {
        case Some(persons: ListBuffer[Id[Person]]) =>
          persons -= p
        case None =>
      }
    }

    logger.info("Reading Households...")
    ScenarioReaderCsv.readHouseholdsFile(
      householdFilePath,
      scenario,
      beamServices,
      householdPersons,
      units,
      buildings,
      parcelAttrs
    )

    logger.info("Total households loaded {}", scenario.getHouseholds.getHouseholds.size())
    logger.info("Checking households without members...")
    val listOfHouseholdsWithoutMembers: ListBuffer[Household] = ListBuffer()
    scenario.getHouseholds.getHouseholds.forEach {
      case (hId: Id[Household], h: Household) if (h.getMemberIds.size() == 0) => listOfHouseholdsWithoutMembers += h
      case _                                                                  =>
    }

    logger.info("Removing households without members {}", listOfHouseholdsWithoutMembers.size)
    listOfHouseholdsWithoutMembers.foreach { h =>
      removeHouseholdVehicles(h)
      scenario.getHouseholds.getHouseholdAttributes.removeAllAttributes(h.getId.toString)
      scenario.getHouseholds.getHouseholds.remove(h.getId)
    }

    logger.info("The scenario loading is completed..")
  }

  def removeHouseholdVehicles(household: Household): Unit = {
    household.getVehicleIds.forEach(
      vehicleId => beamServices.privateVehicles.remove(Id.create(vehicleId.toString, classOf[BeamVehicle]))
    )
  }
}

object ScenarioReaderCsv {
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
