package beam.utils.matsim_conversion

import java.io.{File, FileWriter}
import java.util

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.utils.FileUtils
import org.supercsv.io.CsvMapWriter
import org.supercsv.prefs.CsvPreference

import scala.util.Try
import scala.xml.{Elem, NodeSeq, XML}

object VehiclesDataConversion extends App {

  lazy val beamFuelTypesTitles = Seq("fuelTypeId", "priceInDollarsPerMJoule")

  //TODO
  lazy val beamFuelTypes = Seq(
    Seq("gasoline", "0.03"),
    Seq("diesel", "0.02"),
    Seq("electricity", "0.01"),
    Seq("biodiesel", "0.01")
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

  lazy val beamVehicleTitles = Seq(
    "vehicleId",
    "vehicleTypeId"
  )

  lazy val beamVehicleTypes = Seq(
    Seq(
      "CAR",
      "4",
      "0",
      "4.5",
      "gasoline",
      "3655.98",
      "3655980000",
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null
    ),
    Seq(
      "SUV",
      "6",
      "0",
      "5.5",
      "gasoline",
      "3655.98",
      "3655980000",
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null
    ),
    Seq(
      "BUS-DEFAULT",
      "50",
      "50",
      "12",
      "diesel",
      "20048",
      "30000000000",
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null
    ),
    Seq(
      "SUBWAY-DEFAULT",
      "50",
      "50",
      "12",
      "electricity",
      "46800",
      "46800000000",
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null
    ),
    Seq(
      "TRAM-DEFAULT",
      "50",
      "50",
      "7.5",
      "electricity",
      "3600",
      "3600000000",
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null
    ),
    Seq(
      "RAIL-DEFAULT",
      "50",
      "50",
      "7.5",
      "diesel",
      "210518",
      "6E+11",
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null
    ),
    Seq(
      "CABLE_CAR-DEFAULT",
      "50",
      "50",
      "7.5",
      "electricity",
      "1116",
      "1116000000",
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null
    )
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

  def generateFuelTypesDefaults(scenarioDirectory: String): Unit = {
    val beamFuelTypesPath = scenarioDirectory + "/beamFuelTypes.csv"

    writeCsvFile(beamFuelTypesPath, beamFuelTypes, beamFuelTypesTitles)
  }

  def generateVehicleTypesDefaults(scenarioDirectory: String, vehicleTypes: Seq[Seq[String]]): Unit = {
    val beamVehTypesPath = scenarioDirectory + "/vehicleTypes.csv"

    writeCsvFile(beamVehTypesPath, vehicleTypes, beamVehicleTypeTitles)
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
      val litersPerMeter = Try((vt \ "engineInformation" \\ "gasConsumption" \@ "literPerMeter").toDouble).getOrElse(0d)
      val joulesPerMeter = Powertrain.litersPerMeterToJoulesPerMeter(fuelType, litersPerMeter)

      Seq(
        id,
        seatingCap,
        standingCap,
        length,
        fuelType,
        joulesPerMeter.toString,
        "4",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null
      )
    }
  }

  def generateVehiclesDataFromPersons(persons: NodeSeq, conversionConfig: ConversionConfig): Seq[Seq[String]] = {
    val vehicles = persons.zipWithIndex.map { case (_, index) =>
      Seq(s"${index + 1}", "CAR")
    }
    val beamVehiclesPath = conversionConfig.scenarioDirectory + "/vehicles.csv"
    writeCsvFile(beamVehiclesPath, vehicles, beamVehicleTitles)
    vehicles
  }

  def writeCsvFile(beamVehiclesPath: String, data: Seq[Seq[String]], titles: Seq[String]): Unit = {
    FileUtils.using(new CsvMapWriter(new FileWriter(beamVehiclesPath), CsvPreference.STANDARD_PREFERENCE)) { writer =>
      writer.writeHeader(titles: _*)
      val rows = data.map { row =>
        row.zipWithIndex.foldRight(new util.HashMap[String, Object]()) { case ((s, i), acc) =>
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
    val beamVehiclesPath = new File(
      scenarioDirectory +
      "/vehicles.csv"
    ).toString
    writeCsvFile(beamVehiclesPath, vehicles, beamVehicleTitles)
    vehicles
  }

}
