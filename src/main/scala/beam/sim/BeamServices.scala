package beam.sim

import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef}
import akka.util.Timeout
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator.ModeChoiceCalculatorFactory
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.ridehail.RideHailSurgePricingManager
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, FuelType}
import beam.agentsim.infrastructure.TAZTreeMap
import beam.agentsim.infrastructure.TAZTreeMap.{readerFromFile, TAZ}
import beam.sim.akkaguice.ActorInject
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.metrics.Metrics
import beam.utils.{DateUtils, FileUtils}
import beam.utils.matsim_conversion.ShapeUtils.CsvTaz
import com.google.inject.{ImplementedBy, Inject, Injector}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.population.Person
import org.matsim.core.controler._
import org.matsim.core.utils.collections.QuadTree
import org.matsim.vehicles.Vehicle
import org.supercsv.io.{CsvMapReader, ICsvMapReader}
import org.supercsv.prefs.CsvPreference

import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
  */

@ImplementedBy(classOf[BeamServicesImpl])
trait BeamServices extends ActorInject {
  val controler: ControlerI
  var beamConfig: BeamConfig

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
  var beamConfig: BeamConfig = injector.getInstance(classOf[BeamConfig])

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
        val vehicleType = vehiclesTypeMap.get(Id.create(vehicleTypeIdString, classOf[BeamVehicleType])).get

        val powerTrain = new Powertrain(vehicleType.primaryFuelConsumptionInJoule)

        val beamVehicle = new BeamVehicle(vehicleId, powerTrain, None, vehicleType, None, None)
        acc += ((vehicleId, beamVehicle))
    }
  }

  def readFuelTypeFile(filePath: String): TrieMap[Id[FuelType], FuelType] = {
    readCsvFileByLine(filePath, TrieMap[Id[FuelType], FuelType]()) {
      case (line, z) =>
        val fuelIdString = line.get("fuelTypeId")
        val fuelTypeId = Id.create(fuelIdString, classOf[FuelType])
        val priceInDollarsPerMJoule = line.get("priceInDollarsPerMJoule").toDouble
        val fuelType = FuelType(fuelIdString, priceInDollarsPerMJoule)
        z += ((fuelTypeId, fuelType))
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
        val secondaryFuelTypeId = line.get("secondaryFuelType")
        val secondaryFuelType = fuelTypeMap.get(Id.create(secondaryFuelTypeId, classOf[FuelType])).get
        val secondaryFuelConsumptionInJoule = line.get("secondaryFuelConsumptionInJoulePerMeter").toDouble
        val secondaryFuelCapacityInJoule = line.get("secondaryFuelCapacityInJoule").toDouble
        val automationLevel = line.get("automationLevel")
        val maxVelocity = line.get("maxVelocity").toDouble
        val passengerCarUnit = line.get("passengerCarUnit")
        val rechargeLevel2RateLimitInWatts = line.get("rechargeLevel2RateLimitInWatts").toDouble
        val rechargeLevel3RateLimitInWatts = line.get("rechargeLevel3RateLimitInWatts").toDouble
        val vehicleCategory = line.get("vehicleCategory")

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
