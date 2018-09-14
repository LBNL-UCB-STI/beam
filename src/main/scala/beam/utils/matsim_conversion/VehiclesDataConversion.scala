package beam.utils.matsim_conversion

import java.io.FileWriter
import java.util

import beam.utils.FileUtils
import org.supercsv.io.CsvMapWriter
import org.supercsv.prefs.CsvPreference

import scala.xml.{Elem, NodeSeq}

object VehiclesDataConversion {

  val beamFuelTypesTitles = Seq("fuelTypeId","priceInDollarsPerMJoule")
  val beamFuelTypes = Seq(
    Seq("gasoline","0.03"), Seq("diesel","0.02")
  )

  val beamVehicleTypeTitles = Seq(
    "vehicleTypeId","seatingCapacity","standingRoomCapacity","lengthInMeter","primaryFuelType",
    "primaryFuelConsumptionInJoulePerMeter","primaryFuelCapacityInJoule","secondaryFuelType",
    "secondaryFuelConsumptionInJoulePerMeter","secondaryFuelCapacityInJoule","automationLevel",
    "maxVelocity","passengerCarUnit","rechargeLevel2RateLimitInWatts","rechargeLevel3RateLimitInWatts",
    "vehicleCategory"
  )
  val beamVehicleTypes = Seq(
    Seq("CAR-1","4","0","4.5","gasoline","2","4","gasoline","80","3","level","60","unit","50","40","CAR"),
    Seq("SUV-2","6","0","5.5","gasoline","2","4","gasoline","80","3","level","50","unit","50","40","SUV"),
    Seq("BUS-DEFAULT","50","50","12","diesel","2","4","gasoline","80","3","level","50","unit","50","40","TRANSIT-BUS"),
    Seq("SUBWAY-DEFAULT","50","50","12","diesel","2","4","gasoline","80","3","level","50","unit","50","40","TRANSIT-SUBWAY"),
    Seq("TRAM-DEFAULT","50","50","7.5","diesel","2","4","gasoline","80","3","level","50","unit","50","40","TRANSIT-TRAM"),
    Seq("RAIL-DEFAULT","50","50","7.5","diesel","2","4","gasoline","80","3","level","50","unit","50","40","TRANSIT-RAIL"),
    Seq("CABLE_CAR-DEFAULT","50","50","7.5","diesel","2","4","gasoline","80","3","level","50","unit","50","40","TRANSIT-CABLE_CAR"),
  )

  val beamVehicleTitles = Seq(
    "vehicleId","vehicleTypeId"
  )

  def generateFuelTypesDefaults(conversionConfig: ConversionConfig) = {
    val beamFuelTypesPath = conversionConfig.scenarioDirectory + "/beamFuelTypes.csv"

    writeCsvFile(beamFuelTypesPath, beamFuelTypes, beamFuelTypesTitles)
  }

  def generateVehicleTypesDefaults(conversionConfig: ConversionConfig, vehicleTypes: Seq[Seq[String]]) = {
    val beamVehTypesPath = conversionConfig.scenarioDirectory + "/vehicleTypes.csv"

    writeCsvFile(beamVehTypesPath, beamVehicleTypes ++ vehicleTypes, beamVehicleTypeTitles)
  }

  def generateVehicleTypesFromSource(vehicleTypeSeq: NodeSeq): Seq[Seq[String]] = {
    val requiredFieldsForType = List("description", "capacity", "length", "engineInformation")

    for {
      vehicleType  <- vehicleTypeSeq
      requiredElem <- requiredFieldsForType if (vehicleType \\ requiredElem).isEmpty
    } yield {
      println(s"Input vehicle data is missing $requiredElem xml element in ${vehicleType.label}")
    }

    vehicleTypeSeq.map { vt =>
      val description = (vt \\ "description").text
      val seatingCap = vt \ "capacity" \\ "seats" \@ "persons"
      val standingCap = vt \ "capacity" \\ "standingRoom" \@ "persons"
      val length = vt \\ "length" \@ "meter"
      val fuelType = (vt \ "engineInformation" \\ "fuelType").text
      Seq(description,seatingCap,standingCap,length,fuelType,"2","4","gasoline","80","3","level","60","unit","50","40","CAR")
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
    FileUtils.using(
      new CsvMapWriter(new FileWriter(beamVehiclesPath), CsvPreference.STANDARD_PREFERENCE)){ writer =>
      writer.writeHeader(titles :_*)
      val rows = data.map{ row =>
        row.zipWithIndex.foldRight(new util.HashMap[String, Object]()){ case ((s, i), acc) =>
          acc.put(titles(i), s)
          acc
        }
      }
      val titlesArray = titles.toArray
      rows.foreach(row => writer.write(row, titlesArray :_*))
    }
  }

  def generateVehiclesDataFromSource(vehiclesDoc: Elem, conversionConfig: ConversionConfig): Seq[Seq[String]] = {
    val vehicles = (vehiclesDoc \ "vehicle").map{ vehicle =>
      Seq(vehicle \@ "id", vehicle \@ "type")
    }
    val beamVehiclesPath = conversionConfig.scenarioDirectory + "/vehicles.csv"
    writeCsvFile(beamVehiclesPath, vehicles, beamVehicleTitles)
    vehicles
  }

}
