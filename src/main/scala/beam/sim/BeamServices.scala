package beam.sim

import java.io.FileNotFoundException
import java.time.ZonedDateTime
import java.util
import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import akka.util.Timeout
import beam.agentsim.agents.choice.mode.ModeSubsidy
import beam.agentsim.agents.choice.mode.ModeSubsidy._
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator.ModeChoiceCalculatorFactory
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.agents.vehicles._
import beam.agentsim.infrastructure.TAZTreeMap
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.sim.BeamServices.{getTazTreeMap, readBeamVehicleTypeFile, readFuelTypeFile, readVehiclesFile}
import beam.sim.akkaguice.ActorInject
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.metrics.Metrics
import beam.sim.vehicles.VehiclesAdjustment
import beam.utils.{BeamVehicleUtils, DateUtils, FileUtils}
import com.google.inject.{ImplementedBy, Inject, Injector}
import org.matsim.api.core.v01.population.{Activity, Person, Plan, Population}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler._
import org.matsim.core.population.PopulationUtils
import org.matsim.core.scenario.MutableScenario
import org.matsim.core.utils.collections.QuadTree
import org.matsim.households._
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils}
import org.slf4j.LoggerFactory
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._
import scala.collection.Iterator
import scala.collection.concurrent.TrieMap
import scala.collection.immutable.List
import scala.collection.mutable.ListBuffer
import scala.collection.parallel.mutable.ParTrieMap
import scala.concurrent.duration.FiniteDuration

/**
  */

@ImplementedBy(classOf[BeamServicesImpl])
trait BeamServices extends ActorInject {
  val controler: ControlerI
  val beamConfig: BeamConfig

  val geo: GeoUtils
  var modeChoiceCalculatorFactory: ModeChoiceCalculatorFactory
  val dates: DateUtils

  var beamRouter: ActorRef
  var rideHailIterationHistoryActor: ActorRef
  val personRefs: TrieMap[Id[Person], ActorRef]
  val vehicles: TrieMap[Id[BeamVehicle], BeamVehicle]
  var personHouseholds: Map[Id[Person], Household]

  val privateVehicles: TrieMap[Id[BeamVehicle], BeamVehicle]
  val vehicleTypes: TrieMap[Id[BeamVehicleType], BeamVehicleType]
  val fuelTypePrices: TrieMap[FuelType, Double]

  var matsimServices: MatsimServices
  val tazTreeMap: TAZTreeMap
  val modeSubsidies: ModeSubsidy
  var iterationNumber: Int = -1

  def startNewIteration()
}

class BeamServicesImpl @Inject()(val injector: Injector) extends BeamServices {

  val controler: ControlerI = injector.getInstance(classOf[ControlerI])
  val beamConfig: BeamConfig = injector.getInstance(classOf[BeamConfig])

  val geo: GeoUtils = injector.getInstance(classOf[GeoUtils])

  val dates: DateUtils = DateUtils(
    ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,
    ZonedDateTime.parse(beamConfig.beam.routing.baseDate)
  )

  var modeChoiceCalculatorFactory: ModeChoiceCalculatorFactory = _
  var beamRouter: ActorRef = _
  var rideHailIterationHistoryActor: ActorRef = _
  val personRefs: TrieMap[Id[Person], ActorRef] = TrieMap()

  val vehicles: TrieMap[Id[BeamVehicle], BeamVehicle] = TrieMap()
  var personHouseholds: Map[Id[Person], Household] = Map()

  val fuelTypePrices: TrieMap[FuelType, Double] =
    readFuelTypeFile(beamConfig.beam.agentsim.agents.vehicles.beamFuelTypesFile)

  val vehicleTypes: TrieMap[Id[BeamVehicleType], BeamVehicleType] =
    maybeScaleTransit(
      readBeamVehicleTypeFile(beamConfig.beam.agentsim.agents.vehicles.beamVehicleTypesFile, fuelTypePrices)
    )

  val privateVehicles: TrieMap[Id[BeamVehicle], BeamVehicle] =
    readVehiclesFile(beamConfig.beam.agentsim.agents.vehicles.beamVehiclesFile, vehicleTypes)

  var matsimServices: MatsimServices = _

  val tazTreeMap: TAZTreeMap = getTazTreeMap(beamConfig.beam.agentsim.taz.file)

  val modeSubsidies = ModeSubsidy(loadSubsidies(beamConfig.beam.agentsim.agents.modeSubsidy.file))

  def clearAll(): Unit = {
    personRefs.clear
    vehicles.clear()
  }

  def startNewIteration(): Unit = {
    clearAll()
    iterationNumber += 1
    Metrics.iterationNumber = iterationNumber
  }

  // Note that this assumes standing room is only available on transit vehicles. Not sure of any counterexamples modulo
  // say, a yacht or personal bus, but I think this will be fine for now.
  def maybeScaleTransit(
    vehicleTypes: TrieMap[Id[BeamVehicleType], BeamVehicleType]
  ): TrieMap[Id[BeamVehicleType], BeamVehicleType] = {
    beamConfig.beam.agentsim.tuning.transitCapacity match {
      case Some(scalingFactor) =>
        vehicleTypes.map {
          case (id, bvt) =>
            id -> (if (bvt.standingRoomCapacity > 0)
                     bvt.copy(
                       seatingCapacity = Math.ceil(bvt.seatingCapacity.toDouble * scalingFactor).toInt,
                       standingRoomCapacity = Math.ceil(bvt.standingRoomCapacity.toDouble * scalingFactor).toInt
                     )
                   else
                     bvt)
        }
      case None => vehicleTypes
    }
  }
}

object BeamServices {
  private val logger = LoggerFactory.getLogger(this.getClass)
  implicit val askTimeout: Timeout = Timeout(FiniteDuration(5L, TimeUnit.SECONDS))

  var vehicleCounter = 1;

  val defaultTazTreeMap: TAZTreeMap = {
    val tazQuadTree: QuadTree[TAZ] = new QuadTree(-1, -1, 1, 1)
    val taz = new TAZ("0", new Coord(0.0, 0.0), 0.0)
    tazQuadTree.put(taz.coord.getX, taz.coord.getY, taz)
    new TAZTreeMap(tazQuadTree)
  }

  def getTazTreeMap(filePath: String): TAZTreeMap = {
    try {
      TAZTreeMap.fromCsv(filePath)
    } catch {
      case fe: FileNotFoundException =>
        logger.error("No TAZ file found at given file path (using defaultTazTreeMap): %s" format filePath, fe)
        defaultTazTreeMap
      case e: Exception =>
        logger.error(
          "Exception occurred while reading from CSV file from path (using defaultTazTreeMap): %s" format e.getMessage,
          e
        )
        defaultTazTreeMap
    }
  }

  def readVehiclesFile(
    filePath: String,
    vehiclesTypeMap: TrieMap[Id[BeamVehicleType], BeamVehicleType]
  ): TrieMap[Id[BeamVehicle], BeamVehicle] = {
    readCsvFileByLine(filePath, TrieMap[Id[BeamVehicle], BeamVehicle]()) {
      case (line, acc) =>
        val vehicleIdString = line.get("vehicleId")
        val vehicleId = Id.create(vehicleIdString, classOf[BeamVehicle])

        val vehicleTypeIdString = line.get("vehicleTypeId")
        val vehicleType = vehiclesTypeMap(Id.create(vehicleTypeIdString, classOf[BeamVehicleType]))

        val houseHoldIdString = line.get("houseHoldId")
        val houseHoldId: Id[Household] = Id.create(houseHoldIdString, classOf[Household])

        val powerTrain = new Powertrain(vehicleType.primaryFuelConsumptionInJoulePerMeter)

        val beamVehicle = new BeamVehicle(vehicleId, powerTrain, None, vehicleType, Some(houseHoldId))
        acc += ((vehicleId, beamVehicle))
    }
  }

  def readFuelTypeFile(filePath: String): TrieMap[FuelType, Double] = {
    readCsvFileByLine(filePath, TrieMap[FuelType, Double]()) {
      case (line, z) =>
        val fuelType = FuelType.fromString(line.get("fuelTypeId"))
        val priceInDollarsPerMJoule = line.get("priceInDollarsPerMJoule").toDouble
        z += ((fuelType, priceInDollarsPerMJoule))
    }
  }

  def readBeamVehicleTypeFile(
    filePath: String,
    fuelTypePrices: TrieMap[FuelType, Double]
  ): TrieMap[Id[BeamVehicleType], BeamVehicleType] = {
    readCsvFileByLine(filePath, TrieMap[Id[BeamVehicleType], BeamVehicleType]()) {
      case (line, z) =>
        val vIdString = line.get("vehicleTypeId")
        val vehicleTypeId = Id.create(vIdString, classOf[BeamVehicleType])
        val seatingCapacity = line.get("seatingCapacity").toInt
        val standingRoomCapacity = line.get("standingRoomCapacity").toInt
        val lengthInMeter = line.get("lengthInMeter").toDouble
        val primaryFuelTypeId = line.get("primaryFuelType")
        val primaryFuelType = FuelType.fromString(primaryFuelTypeId)
        val primaryFuelConsumptionInJoulePerMeter = line.get("primaryFuelConsumptionInJoulePerMeter").toDouble
        val primaryFuelCapacityInJoule = line.get("primaryFuelCapacityInJoule").toDouble
        val secondaryFuelTypeId = Option(line.get("secondaryFuelType"))
        val secondaryFuelType = secondaryFuelTypeId.map(FuelType.fromString(_))
        val secondaryFuelConsumptionInJoule =
          Option(line.get("secondaryFuelConsumptionInJoulePerMeter")).map(_.toDouble)
        val secondaryFuelCapacityInJoule = Option(line.get("secondaryFuelCapacityInJoule")).map(_.toDouble)
        val automationLevel = Option(line.get("automationLevel"))
        val maxVelocity = Option(line.get("maxVelocity")).map(_.toDouble)
        val passengerCarUnit = Option(line.get("passengerCarUnit")).map(_.toDouble).getOrElse(1d)
        val rechargeLevel2RateLimitInWatts = Option(line.get("rechargeLevel2RateLimitInWatts")).map(_.toDouble)
        val rechargeLevel3RateLimitInWatts = Option(line.get("rechargeLevel3RateLimitInWatts")).map(_.toDouble)
        val vehicleCategory = VehicleCategory.fromString(line.get("vehicleCategory"))

        val bvt = BeamVehicleType(
          vIdString,
          seatingCapacity,
          standingRoomCapacity,
          lengthInMeter,
          primaryFuelType,
          primaryFuelConsumptionInJoulePerMeter,
          primaryFuelCapacityInJoule,
          secondaryFuelType,
          secondaryFuelConsumptionInJoule,
          secondaryFuelCapacityInJoule,
          automationLevel,
          maxVelocity,
          passengerCarUnit,
          rechargeLevel2RateLimitInWatts,
          rechargeLevel3RateLimitInWatts,
          vehicleCategory
        )
        z += ((vehicleTypeId, bvt))
    }
  }

  def readPersonsFile(
    filePath: String,
    population: Population,
    modes: String
  ): TrieMap[Id[Household], ListBuffer[Id[Person]]] = {
    readCsvFileByLine(filePath, TrieMap[Id[Household], ListBuffer[Id[Person]]]()) {
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

        population.getPersonAttributes.putAttribute(person.getId.toString, "houseHoldId", household_id)
        population.getPersonAttributes.putAttribute(person.getId.toString, "rank", 0)
        population.getPersonAttributes.putAttribute(person.getId.toString, "age", age.toInt)
        population.getPersonAttributes.putAttribute(person.getId.toString, "available-modes", modes)
        population.addPerson(person)

        val houseHoldId: Id[Household] = Id.create(household_id, classOf[Household])

        val list: ListBuffer[Id[Person]] = acc.get(houseHoldId) match {
          case Some(personList: ListBuffer[Id[Person]]) =>
            personList += _personId
            personList
          case None =>
            ListBuffer[Id[Person]](_personId)
        }
        acc += ((houseHoldId, list))
    }
  }

  def readUnitsFile(filePath: String): TrieMap[String, java.util.Map[String, String]] = {
    readCsvFileByLine(filePath, TrieMap[String, java.util.Map[String, String]]()) {
      case (line, acc) =>
        val _line = new java.util.TreeMap[String, String]()
        _line.put("building_id", line.get("building_id"))
        //if(acc.size % 500000 == 0) logger.info(acc.size.toString)
        acc += ((line.get("unit_id"), _line))
    }
  }

  def readParcelAttrFile(filePath: String): TrieMap[String, java.util.Map[String, String]] = {
    readCsvFileByLine(filePath, TrieMap[String, java.util.Map[String, String]]()) {
      case (line, acc) =>
        val _line = new java.util.TreeMap[String, String]()
        _line.put("x", line.get("x"))
        _line.put("y", line.get("y"))
        //if(acc.size % 500000 == 0) logger.info(acc.size.toString)
        acc += ((line.get("primary_id"), _line))
    }
  }

  def readPlansFile(filePath: String, population: Population): Unit = {
    readCsvFileByLine(filePath, Unit) {
      case (line, acc) =>
        val personId = line.get("personId")
        val planElement = line.get("planElement")
        val planElementId = line.get("planElementId")
        val activityType = line.get("activityType")
        val x = line.get("x")
        val y = line.get("y")
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
            val coord = new Coord(x.toDouble, y.toDouble)
            val act = PopulationUtils.createAndAddActivityFromCoord(plan, activityType, coord)
            if (endTime != null) act.setEndTime(endTime.toDouble)
          }

          /*val list = acc.get(_personId) match {
            case Some(planList: ListBuffer[java.util.Map[String, String]]) =>
              planList += line
              planList
            case None =>
              ListBuffer[java.util.Map[String, String]](line)
          }*/

          //if(acc.size % 500000 == 0) logger.info(acc.size.toString)

          //acc += (( _personId, list ))

        }
        Unit
    }
  }

  def readHouseHoldsFile(
    filePath: String,
    scenario: MutableScenario,
    beamServices: BeamServices,
    houseHoldPersons: ParTrieMap[Id[Household], ListBuffer[Id[Person]]],
    units: ParTrieMap[String, java.util.Map[String, String]],
    buildings: ParTrieMap[String, java.util.Map[String, String]],
    parcelAttrs: ParTrieMap[String, java.util.Map[String, String]]
  ): Unit = {

    val scenarioHouseholdAttributes = scenario.getHouseholds.getHouseholdAttributes
    val scenarioHouseholds = scenario.getHouseholds.getHouseholds
    val scenarioVehicles = beamServices.privateVehicles
    var counter = 0

    readCsvFileByLine(filePath, Unit) {

      case (line, acc) => {

        val _houseHoldId = line.get("household_id")
        val houseHoldId = Id.create(_houseHoldId, classOf[Household])

        val numberOfVehicles = java.lang.Double.parseDouble(line.get("cars")).intValue()
        //val numberOfPersons = java.lang.Double.parseDouble(line.get("persons")).intValue()

        val objHouseHold = new HouseholdsFactoryImpl().createHousehold(houseHoldId)

        // Setting the coordinates
        val houseHoldCoord: Coord = {

          if (line.keySet().contains("homecoordx") && line.keySet().contains("homecoordy")) {
            val x = line.get("homecoordx")
            val y = line.get("homecoordy")
            new Coord(java.lang.Double.parseDouble(x), java.lang.Double.parseDouble(y))
          } else {
            val houseHoldUnitId = line.get("unit_id")
            if (houseHoldUnitId != null) {
              units.get(houseHoldUnitId) match {
                case Some(unit) =>
                  buildings.get(unit.get("building_id")) match {
                    case Some(building) => {
                      parcelAttrs.get(building.get("parcel_id")) match {
                        case Some(parcelAttr) =>
                          val x = parcelAttr.get("x")
                          val y = parcelAttr.get("y")
                          new Coord(java.lang.Double.parseDouble(x), java.lang.Double.parseDouble(y))
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
          objHouseHold.setIncome(income)
        } catch {
          case e: Exception =>
            e.printStackTrace()
        }

        val p: Option[ListBuffer[Id[Person]]] = houseHoldPersons.get(houseHoldId)

        p match {
          case Some(p) => {
            objHouseHold.setMemberIds(p.asJava)
          }

          case None =>
          //logger.info("no persons for household")
        }

        val vehicleTypes = VehiclesAdjustment
          .getVehicleAdjustment(beamServices)
          .sampleVehicleTypesForHousehold(
            numberOfVehicles,
            VehicleCategory.Car,
            objHouseHold.getIncome.getIncome,
            objHouseHold.getMemberIds.size,
            null,
            houseHoldCoord
          )

        val vehicleIds = new util.ArrayList[Id[Vehicle]]
        vehicleTypes.foreach { bvt =>
          val vt = VehicleUtils.getFactory.createVehicleType(Id.create(bvt.vehicleTypeId, classOf[VehicleType]))
          val v = VehicleUtils.getFactory.createVehicle(Id.createVehicleId(vehicleCounter), vt)
          vehicleIds.add(v.getId)
          val bv = BeamVehicleUtils.getBeamVehicle(v, objHouseHold, bvt)
          scenarioVehicles.put(bv.getId, bv)

          vehicleCounter = vehicleCounter + 1
        }

        objHouseHold.setVehicleIds(vehicleIds)

        scenarioHouseholds.put(objHouseHold.getId, objHouseHold)
        scenarioHouseholdAttributes
          .putAttribute(objHouseHold.getId.toString, "homecoordx", houseHoldCoord.getX)
        scenarioHouseholdAttributes
          .putAttribute(objHouseHold.getId.toString, "homecoordy", houseHoldCoord.getY)

        counter = counter + 1
        if (counter % 50000 == 0) logger.info(counter.toString)

        //if(acc.size % 500000 == 0) logger.info(acc.size.toString)

        //acc += (( houseHoldId, houseHold ))
      }

      Unit
    }
  }

  def readBuildingsFile(
    filePath: String
  ): TrieMap[String, java.util.Map[String, String]] = {

    readCsvFileByLine(filePath, TrieMap[String, java.util.Map[String, String]]()) {
      case (line, acc) =>
        val _line = new java.util.TreeMap[String, String]()
        _line.put("parcel_id", line.get("parcel_id"))
        //if(acc.size % 500000 == 0) logger.info(acc.size.toString)
        acc += ((line.get("building_id"), _line))
    }
  }

  private def readCsvFileByLine[A](filePath: String, z: A)(readLine: (java.util.Map[String, String], A) => A): A = {
    FileUtils.using(new CsvMapReader(FileUtils.readerFromFile(filePath), CsvPreference.STANDARD_PREFERENCE)) {
      mapReader =>
        var res: A = z
        val header = mapReader.getHeader(true)
        var line: java.util.Map[String, String] = mapReader.read(header: _*)
        while (null != line) {
          res = readLine(line, res)
          line = mapReader.read(header: _*)
        }
        res
    }
  }

}
