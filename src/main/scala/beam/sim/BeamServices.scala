package beam.sim

import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import akka.util.Timeout
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator.ModeChoiceCalculatorFactory
import beam.agentsim.agents.vehicles.BeamVehicleType.{FuelTypeId, VehicleCategory}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, FuelType}
import beam.agentsim.infrastructure.TAZTreeMap
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.sim.akkaguice.ActorInject
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.metrics.Metrics
import beam.utils.{DateUtils, FileUtils}
import com.google.inject.{ImplementedBy, Inject, Injector}
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler._
import org.matsim.core.utils.collections.QuadTree
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

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

  val privateVehicles: TrieMap[Id[BeamVehicle], BeamVehicle]
  val vehicleTypes: TrieMap[Id[BeamVehicleType], BeamVehicleType]

  var matsimServices: MatsimServices
  val tazTreeMap: TAZTreeMap

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
  val personRefs: TrieMap[Id[Person], ActorRef] = TrieMap[Id[Person], ActorRef]()

  val vehicles: TrieMap[Id[BeamVehicle], BeamVehicle] = TrieMap[Id[BeamVehicle], BeamVehicle]()

  val fuelTypes: TrieMap[Id[FuelType], FuelType] =
    BeamServices.readFuelTypeFile(beamConfig.beam.agentsim.agents.vehicles.beamFuelTypesFile)

  val vehicleTypes: TrieMap[Id[BeamVehicleType], BeamVehicleType] =
    BeamServices.readBeamVehicleTypeFile(beamConfig.beam.agentsim.agents.vehicles.beamVehicleTypesFile, fuelTypes)

  val privateVehicles: TrieMap[Id[BeamVehicle], BeamVehicle] =
    BeamServices.readVehiclesFile(beamConfig.beam.agentsim.agents.vehicles.beamVehiclesFile, vehicleTypes)

  var matsimServices: MatsimServices = _

  val tazTreeMap: TAZTreeMap = BeamServices.getTazTreeMap(beamConfig.beam.agentsim.taz.file)

  def clearAll(): Unit = {
    personRefs.clear
    vehicles.clear()
  }

  def startNewIteration(): Unit = {
    clearAll()
    iterationNumber += 1
    Metrics.iterationNumber = iterationNumber
  }
}

object BeamServices {
  implicit val askTimeout: Timeout = Timeout(FiniteDuration(5L, TimeUnit.SECONDS))

  val defaultTazTreeMap: TAZTreeMap = {
    val tazQuadTree: QuadTree[TAZ] = new QuadTree[TAZ](-1, -1, 1, 1)
    val taz = new TAZ("0", new Coord(0.0, 0.0), 0.0)
    tazQuadTree.put(taz.coord.getX, taz.coord.getY, taz)
    new TAZTreeMap(tazQuadTree)
  }

  def getTazTreeMap(file: String): TAZTreeMap = {
    Try(TAZTreeMap.fromCsv(file)).getOrElse {
      BeamServices.defaultTazTreeMap
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

        val powerTrain = new Powertrain(vehicleType.primaryFuelConsumptionInJoulePerMeter)

        val beamVehicle = new BeamVehicle(vehicleId, powerTrain, None, vehicleType)
        acc += ((vehicleId, beamVehicle))
    }
  }

  def readFuelTypeFile(filePath: String): TrieMap[Id[FuelType], FuelType] = {
    readCsvFileByLine(filePath, TrieMap[Id[FuelType], FuelType]()) {
      case (line, z) =>
        val fuelIdString = line.get("fuelTypeId")
        val fuelTypeId = Id.create(fuelIdString, classOf[FuelType])
        val priceInDollarsPerMJoule = line.get("priceInDollarsPerMJoule").toDouble

        val fuelType = FuelType(getFuelTypeId(fuelIdString), priceInDollarsPerMJoule)
        z += ((fuelTypeId, fuelType))
    }
  }

  private def getFuelTypeId(fuelType: String): FuelTypeId = {
    fuelType match {
      case "gasoline" => BeamVehicleType.Gasoline
      case "diesel" => BeamVehicleType.Diesel
      case "electricity" => BeamVehicleType.Electricity
      case "biodiesel" => BeamVehicleType.Biodiesel
      case _ => throw new RuntimeException("Invalid fuel type id")
    }
  }

  def readBeamVehicleTypeFile(
    filePath: String,
    fuelTypeMap: TrieMap[Id[FuelType], FuelType]
  ): TrieMap[Id[BeamVehicleType], BeamVehicleType] = {
    readCsvFileByLine(filePath, TrieMap[Id[BeamVehicleType], BeamVehicleType]()) {
      case (line, z) =>
        val vIdString = line.get("vehicleTypeId")
        val vehicleTypeId = Id.create(vIdString, classOf[BeamVehicleType])
        val seatingCapacity = line.get("seatingCapacity").toDouble
        val standingRoomCapacity = line.get("standingRoomCapacity").toDouble
        val lengthInMeter = line.get("lengthInMeter").toDouble
        val primaryFuelTypeId = line.get("primaryFuelType")
        val primaryFuelType = fuelTypeMap.get(Id.create(primaryFuelTypeId, classOf[FuelType])).get
        val primaryFuelConsumptionInJoulePerMeter = line.get("primaryFuelConsumptionInJoulePerMeter").toDouble
        val primaryFuelCapacityInJoule = line.get("primaryFuelCapacityInJoule").toDouble
        val secondaryFuelTypeId = Option(line.get("secondaryFuelType"))
        val secondaryFuelType = secondaryFuelTypeId.flatMap(sid => fuelTypeMap.get(Id.create(sid, classOf[FuelType])))
        val secondaryFuelConsumptionInJoule = Option(line.get("secondaryFuelConsumptionInJoulePerMeter")).map(_.toDouble)
        val secondaryFuelCapacityInJoule = Option(line.get("secondaryFuelCapacityInJoule")).map(_.toDouble)
        val automationLevel = Option(line.get("automationLevel"))
        val maxVelocity = Option(line.get("maxVelocity")).map(_.toDouble)
        val passengerCarUnit = Option(line.get("passengerCarUnit")).map(_.toDouble).getOrElse(1d)
        val rechargeLevel2RateLimitInWatts = Option(line.get("rechargeLevel2RateLimitInWatts")).map(_.toDouble)
        val rechargeLevel3RateLimitInWatts = Option(line.get("rechargeLevel3RateLimitInWatts")).map(_.toDouble)
        val vehicleCategoryString = Option(line.get("vehicleCategory"))
        val vehicleCategory = vehicleCategoryString.map(getVehicleCategory)

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

  private def getVehicleCategory(vehicleCategory: String): VehicleCategory = {
    vehicleCategory match {
      case "car" => BeamVehicleType.Car
      case "bike" => BeamVehicleType.Bike
      case "ridehail" => BeamVehicleType.RideHail
      case _ => throw new RuntimeException("Invalid vehicleCategory")
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
