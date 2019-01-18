package beam.sim

import java.io.FileNotFoundException
import java.time.ZonedDateTime
import java.util
import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import akka.util.Timeout
import beam.agentsim.agents.choice.mode.{ModeIncentive, PtFares}
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator.ModeChoiceCalculatorFactory
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.agents.vehicles.VehicleCategory.Undefined
import beam.agentsim.agents.vehicles._
import beam.agentsim.infrastructure.TAZTreeMap
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.router.Modes.BeamMode
import beam.sim.BeamServices.{getTazTreeMap, readBeamVehicleTypeFile, readFuelTypeFile, readVehiclesFile}
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.metrics.Metrics
import beam.utils.{DateUtils, FileUtils, NetworkHelper}
import com.google.inject.{ImplementedBy, Inject, Injector}
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler._
import org.matsim.core.utils.collections.QuadTree
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle
import org.slf4j.LoggerFactory
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration

/**
  */

@ImplementedBy(classOf[BeamServicesImpl])
trait BeamServices {
  val controler: ControlerI
  val beamConfig: BeamConfig

  val geo: GeoUtils
  var modeChoiceCalculatorFactory: ModeChoiceCalculatorFactory
  val dates: DateUtils

  var beamRouter: ActorRef
  val rideHailTransitModes: Seq[BeamMode]
  val personRefs: TrieMap[Id[Person], ActorRef]
  val agencyAndRouteByVehicleIds: TrieMap[Id[Vehicle], (String, String)]
  var personHouseholds: Map[Id[Person], Household]

  val privateVehicles: TrieMap[Id[BeamVehicle], BeamVehicle]
  val vehicleTypes: TrieMap[Id[BeamVehicleType], BeamVehicleType]
  val fuelTypePrices: Map[FuelType, Double]

  var matsimServices: MatsimServices
  val tazTreeMap: TAZTreeMap
  val modeIncentives: ModeIncentive
  val ptFares: PtFares
  var iterationNumber: Int = -1

  def startNewIteration()

  def networkHelper: NetworkHelper
}

class BeamServicesImpl @Inject()(val injector: Injector) extends BeamServices {

  val controler: ControlerI = injector.getInstance(classOf[ControlerI])
  val beamConfig: BeamConfig = injector.getInstance(classOf[BeamConfig])

  val geo: GeoUtils = injector.getInstance(classOf[GeoUtils])

  val dates: DateUtils = DateUtils(
    ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,
    ZonedDateTime.parse(beamConfig.beam.routing.baseDate)
  )

  val rideHailTransitModes: Seq[BeamMode] =
    if (beamConfig.beam.agentsim.agents.rideHailTransit.modesToConsider.equalsIgnoreCase("all")) BeamMode.transitModes
    else if (beamConfig.beam.agentsim.agents.rideHailTransit.modesToConsider.equalsIgnoreCase("mass"))
      BeamMode.massTransitModes
    else {
      beamConfig.beam.agentsim.agents.rideHailTransit.modesToConsider.toUpperCase
        .split(",")
        .map(BeamMode.fromString)
        .toSeq
    }

  var modeChoiceCalculatorFactory: ModeChoiceCalculatorFactory = _
  var beamRouter: ActorRef = _
  var rideHailIterationHistoryActor: ActorRef = _
  val personRefs: TrieMap[Id[Person], ActorRef] = TrieMap()

  val agencyAndRouteByVehicleIds: TrieMap[
    Id[Vehicle],
    (String, String)
  ] = TrieMap()
  var personHouseholds: Map[Id[Person], Household] = Map()

  val fuelTypePrices: Map[FuelType, Double] =
    readFuelTypeFile(beamConfig.beam.agentsim.agents.vehicles.beamFuelTypesFile).toMap

  // TODO Fix me once `TrieMap` is removed
  val vehicleTypes: TrieMap[Id[BeamVehicleType], BeamVehicleType] =
    maybeScaleTransit(
      TrieMap(
        readBeamVehicleTypeFile(beamConfig.beam.agentsim.agents.vehicles.beamVehicleTypesFile, fuelTypePrices).toSeq: _*
      )
    )

  // TODO Fix me once `TrieMap` is removed
  val privateVehicles: TrieMap[Id[BeamVehicle], BeamVehicle] =
    beamConfig.beam.agentsim.agents.population.useVehicleSampling match {
      case true =>
        TrieMap[Id[BeamVehicle], BeamVehicle]()
      case false =>
        TrieMap(readVehiclesFile(beamConfig.beam.agentsim.agents.vehicles.beamVehiclesFile, vehicleTypes).toSeq: _*)
    }

  var matsimServices: MatsimServices = _

  val tazTreeMap: TAZTreeMap = getTazTreeMap(beamConfig.beam.agentsim.taz.file)

  val modeIncentives = ModeIncentive(beamConfig.beam.agentsim.agents.modeIncentive.file)
  val ptFares = PtFares(beamConfig.beam.agentsim.agents.ptFare.file)

  def clearAll(): Unit = {
    personRefs.clear
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

  private val _networkHelper: NetworkHelper = injector.getInstance(classOf[NetworkHelper])

  def networkHelper: NetworkHelper = _networkHelper
}

object BeamServices {
  private val logger = LoggerFactory.getLogger(this.getClass)
  implicit val askTimeout: Timeout = Timeout(FiniteDuration(5L, TimeUnit.SECONDS))

  var vehicleCounter = 1

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
    vehiclesTypeMap: scala.collection.Map[Id[BeamVehicleType], BeamVehicleType]
  ): scala.collection.Map[Id[BeamVehicle], BeamVehicle] = {

    readCsvFileByLine(filePath, scala.collection.mutable.HashMap[Id[BeamVehicle], BeamVehicle]()) {
      case (line, acc) =>
        val vehicleIdString = line.get("vehicleId")
        val vehicleId = Id.create(vehicleIdString, classOf[BeamVehicle])

        val vehicleTypeIdString = line.get("vehicleTypeId")
        val vehicleType = vehiclesTypeMap(Id.create(vehicleTypeIdString, classOf[BeamVehicleType]))

        val householdIdString = line.get("householdId")

        val householdId: Option[Id[Household]] = if (householdIdString == null) {
          None
        } else {
          Some(Id.create(householdIdString, classOf[Household]))
        }

        val powerTrain = new Powertrain(vehicleType.primaryFuelConsumptionInJoulePerMeter)

        val beamVehicle = new BeamVehicle(vehicleId, powerTrain, None, vehicleType, householdId)
        acc += ((vehicleId, beamVehicle))
        acc
    }
  }

  def readFuelTypeFile(filePath: String): scala.collection.Map[FuelType, Double] = {
    readCsvFileByLine(filePath, scala.collection.mutable.HashMap[FuelType, Double]()) {
      case (line, z) =>
        val fuelType = FuelType.fromString(line.get("fuelTypeId"))
        val priceInDollarsPerMJoule = line.get("priceInDollarsPerMJoule").toDouble
        z += ((fuelType, priceInDollarsPerMJoule))
    }
  }

  def readBeamVehicleTypeFile(
    filePath: String,
    fuelTypePrices: scala.collection.Map[FuelType, Double]
  ): scala.collection.Map[Id[BeamVehicleType], BeamVehicleType] = {

    val vehicleTypes =
      readCsvFileByLine(filePath, scala.collection.mutable.HashMap[Id[BeamVehicleType], BeamVehicleType]()) {
        case (line: util.Map[String, String], z) =>
          val vIdString = line.get("vehicleTypeId")
          val vehicleTypeId = Id.create(line.get("vehicleTypeId"), classOf[BeamVehicleType])
          val seatingCapacity = line.get("seatingCapacity").trim.toInt
          val standingRoomCapacity = line.get("standingRoomCapacity").trim.toInt
          val lengthInMeter = line.get("lengthInMeter").trim.toDouble
          val primaryFuelTypeId = line.get("primaryFuelType")
          val primaryFuelType = FuelType.fromString(primaryFuelTypeId)
          val primaryFuelConsumptionInJoulePerMeter = line.get("primaryFuelConsumptionInJoulePerMeter").trim.toDouble
          val primaryFuelCapacityInJoule = line.get("primaryFuelCapacityInJoule").trim.toDouble
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

          // This is a hack, hope we can fix files soon...
          val fixedVehicleCategory = (vehicleCategory, vIdString) match {
            case (Undefined, typeId) if typeId.toLowerCase == "car" || typeId.toLowerCase == "bike" =>
              val newVehicleCategory = if (typeId.toLowerCase == "car") VehicleCategory.Car else VehicleCategory.Bike
              logger.warn(
                s"vehicleTypeId '$vehicleTypeId' will be used as vehicleCategory. Old value: $vehicleCategory, New value: $newVehicleCategory"
              )
              newVehicleCategory
            case _ =>
              vehicleCategory
          }

          val bvt = BeamVehicleType(
            vehicleTypeId,
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
            fixedVehicleCategory
          )
          z += ((vehicleTypeId, bvt))
      }
    vehicleTypes
  }

  def readCsvFileByLine[A](filePath: String, z: A)(readLine: (java.util.Map[String, String], A) => A): A = {
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
