package beam.utils.matsim_conversion

import java.io.FileWriter
import java.util

import beam.utils.FileUtils
import org.supercsv.io.CsvMapWriter
import org.supercsv.prefs.CsvPreference

import scala.xml.{Elem, NodeSeq, XML}

object VehiclesDataConversion extends App {

  lazy val beamFuelTypesTitles = Seq("fuelTypeId", "priceInDollarsPerMJoule")

  lazy val beamFuelTypes = Seq(
    Seq("gasoline", "0.03"),
    Seq("diesel", "0.02"),
    Seq("electricity", "0.01")
  )

  lazy val beamVehicleTypeTitles = Seq(
    "vehicleTypeId",
    "seatingCapacity",
    "standingRoomCapacity",
    "lengthInMeter",
    "primaryFuelType",
    "primaryFuelConsumptionInJoulePerMeter",
    "primaryFuelCapacityInJoule",
    "secondaryFuelType",
    "secondaryFuelConsumptionInJoulePerMeter",
    "secondaryFuelCapacityInJoule",
    "automationLevel",
    "maxVelocity",
    "passengerCarUnit",
    "rechargeLevel2RateLimitInWatts",
    "rechargeLevel3RateLimitInWatts",
    "vehicleCategory"
  )

  lazy val beamVehicleTypes = Seq(
    Seq(
      "CAR-1",
      "4",
      "0",
      "4.5",
      "gasoline",
      "2",
      "4",
      "gasoline",
      "80",
      "3",
      "level",
      "60",
      "unit",
      "50",
      "40",
      "CAR"
    ),
    Seq(
      "SUV-2",
      "6",
      "0",
      "5.5",
      "gasoline",
      "2",
      "4",
      "gasoline",
      "80",
      "3",
      "level",
      "50",
      "unit",
      "50",
      "40",
      "SUV"
    ),
    Seq(
      "BUS-DEFAULT",
      "50",
      "50",
      "12",
      "diesel",
      "2",
      "4",
      "gasoline",
      "80",
      "3",
      "level",
      "50",
      "unit",
      "50",
      "40",
      "TRANSIT-BUS"
    ),
    Seq(
      "SUBWAY-DEFAULT",
      "50",
      "50",
      "12",
      "diesel",
      "2",
      "4",
      "gasoline",
      "80",
      "3",
      "level",
      "50",
      "unit",
      "50",
      "40",
      "TRANSIT-SUBWAY"
    ),
    Seq(
      "TRAM-DEFAULT",
      "50",
      "50",
      "7.5",
      "diesel",
      "2",
      "4",
      "gasoline",
      "80",
      "3",
      "level",
      "50",
      "unit",
      "50",
      "40",
      "TRANSIT-TRAM"
    ),
    Seq(
      "RAIL-DEFAULT",
      "50",
      "50",
      "7.5",
      "diesel",
      "2",
      "4",
      "gasoline",
      "80",
      "3",
      "level",
      "50",
      "unit",
      "50",
      "40",
      "TRANSIT-RAIL"
    ),
    Seq(
      "CABLE_CAR-DEFAULT",
      "50",
      "50",
      "7.5",
      "diesel",
      "2",
      "4",
      "gasoline",
      "80",
      "3",
      "level",
      "50",
      "unit",
      "50",
      "40",
      "TRANSIT-CABLE_CAR"
    ),
  )

  lazy val beamVehicleTitles = Seq(
    "vehicleId",
    "vehicleTypeId"
  )

  if (null == args || args.length < 3) {
    println("Please include parameters: /path/to/vehicles.xml /path/to/transitVehicles.xml /outputDirectory/path")
  } else {
    val vehiclesFile = args(0)
    val transitVehiclesFile = args(1)
    val outputDir = args(2)

    generateVehiclesData(vehiclesFile, transitVehiclesFile, outputDir)
  }

  def generateVehiclesData(vehiclesFile: String, transitFile: String, outputDir: String) = {
    generateFuelTypesDefaults(outputDir)

    val vehiclesDoc = XML.loadFile(vehiclesFile)
    val transitDoc = XML.loadFile(transitFile)

    val vehicleTypes = generateVehicleTypesFromSource(vehiclesDoc \\ "vehicleType")
    val transitVehicleTypes = generateVehicleTypesFromSource(transitDoc \\ "vehicleType")

    generateVehicleTypesDefaults(outputDir, vehicleTypes ++ transitVehicleTypes)
    generateVehiclesDataFromSource(outputDir, vehiclesDoc)
  }

  def generateFuelTypesDefaults(scenarioDirectory: String) = {
    val beamFuelTypesPath = scenarioDirectory + "/beamFuelTypes.csv"

    writeCsvFile(beamFuelTypesPath, beamFuelTypes, beamFuelTypesTitles)
  }

  def generateVehicleTypesDefaults(scenarioDirectory: String, vehicleTypes: Seq[Seq[String]]) = {
    val beamVehTypesPath = scenarioDirectory + "/vehicleTypes.csv"

    writeCsvFile(beamVehTypesPath, beamVehicleTypes ++ vehicleTypes, beamVehicleTypeTitles)
  }

  def generateVehicleTypesFromSource(vehicleTypeSeq: NodeSeq): Seq[Seq[String]] = {
    val requiredFieldsForType = List("capacity", "length", "engineInformation") //description ?

    for {
      vehicleType  <- vehicleTypeSeq
      requiredElem <- requiredFieldsForType if (vehicleType \\ requiredElem).isEmpty
    } yield {
      println(s"Input vehicle data is missing $requiredElem xml element in ${vehicleType.label}")
    }

    vehicleTypeSeq.map { vt =>
      val id = vt \@ "id"
      val seatingCap = vt \ "capacity" \\ "seats" \@ "persons"
      val standingCap = vt \ "capacity" \\ "standingRoom" \@ "persons"
      val length = vt \\ "length" \@ "meter"
      val fuelType = (vt \ "engineInformation" \\ "fuelType").text
      Seq(
        id,
        seatingCap,
        standingCap,
        length,
        fuelType,
        "2",
        "4",
        "gasoline",
        "80",
        "3",
        "level",
        "60",
        "unit",
        "50",
        "40",
        "CAR"
      )
    }
  }

  def generateVehiclesDataFromPersons(persons: NodeSeq, conversionConfig: ConversionConfig): Seq[Seq[String]] = {
    val vehicles = persons.zipWithIndex.map {
      case (_, index) =>
        Seq(s"${index + 1}", "CAR-1")
    }
    val beamVehiclesPath = conversionConfig.scenarioDirectory + "/vehicles.csv"
    writeCsvFile(beamVehiclesPath, vehicles, beamVehicleTitles)
    vehicles
  }

  def writeCsvFile(beamVehiclesPath: String, data: Seq[Seq[String]], titles: Seq[String]) = {
    FileUtils.using(new CsvMapWriter(new FileWriter(beamVehiclesPath), CsvPreference.STANDARD_PREFERENCE)) { writer =>
      writer.writeHeader(titles: _*)
      val rows = data.map { row =>
        row.zipWithIndex.foldRight(new util.HashMap[String, Object]()) {
          case ((s, i), acc) =>
            acc.put(titles(i), s)
            acc
        }
      }
      val titlesArray = titles.toArray
      rows.foreach(row => writer.write(row, titlesArray: _*))
    }
  }

  def generateVehiclesDataFromSource(scenarioDirectory: String, vehiclesDoc: Elem): Seq[Seq[String]] = {
    val vehicles = (vehiclesDoc \ "vehicle").map { vehicle =>
      Seq(vehicle \@ "id", vehicle \@ "type")
    }
    val beamVehiclesPath = scenarioDirectory + "/vehicles.csv"
    writeCsvFile(beamVehiclesPath, vehicles, beamVehicleTitles)
    vehicles
  }

}
