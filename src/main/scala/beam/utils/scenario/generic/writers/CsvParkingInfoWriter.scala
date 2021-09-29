package beam.utils.scenario.generic.writers

import beam.utils.csv.CsvWriter
import beam.utils.data.synthpop.GeoService
import beam.utils.data.synthpop.models.Models.{GenericGeoId, TazGeoId}
import com.vividsolutions.jts.geom.Geometry
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.opengis.referencing.operation.MathTransform

import scala.util.Try

trait ParkingInfoWriter {
  def write(path: String, geoService: GeoService, tazCounts: Map[GenericGeoId, (Int, Int)]): Unit
}

object CsvParkingInfoWriter extends ParkingInfoWriter {

  private val headers: Array[String] =
    Array("taz", "parkingType", "pricingModel", "chargingPointType", "numStalls", "feeInCents", "reservedFor")

  override def write(path: String, geoService: GeoService, tazCounts: Map[GenericGeoId, (Int, Int)]): Unit = {

    val destinationCoordSystem = CRS.decode("EPSG:3395", true)
    val sourceCoordSystem = CRS.decode(geoService.crsCode, true)
    val mt: MathTransform = CRS.findMathTransform(sourceCoordSystem, destinationCoordSystem, true)
    val csvWriter = new CsvWriter(path, headers)
    try {
      tazCounts.foreach { case (tazGeoId, (numberPersons, numberWorkers)) =>
        val scaledTazArea = geoService.tazGeoIdToGeom.get(tazGeoId.asInstanceOf[TazGeoId]) match {
          case Some(geometry: Geometry) => JTS.transform(geometry, mt).getArea / 1000
          case _                        => 100000
        }
        val parkingInfo = getNumberOfSpaces(scaledTazArea, numberPersons, numberWorkers)
        parkingInfo.foreach { case (parkingType, (spots, cost)) =>
          if (spots > 0) {
            csvWriter.write(
              tazGeoId.asInstanceOf[TazGeoId].asUniqueKey,
              parkingType,
              "Block",
              "None",
              spots.toString,
              cost.toString,
              "Any"
            )
          }
        }
      }
    } finally {
      Try(csvWriter.close())
    }
  }

  private def getNumberOfSpaces(
    scaledTazArea: Double,
    numberPersons: Int,
    numberWorkers: Int
  ): Map[String, (Int, Double)] = {
    val populationDensity = numberPersons.toDouble / scaledTazArea
    val employmentDensity = numberWorkers.toDouble / scaledTazArea
    val offStreetParking =
      (math.exp(-1.974 + 1.002 * math.log(employmentDensity)) * scaledTazArea).max(0).min(numberWorkers)
    val onStreetParking =
      ((numberPersons + numberWorkers) * (0.5559 - 0.1454 * (populationDensity + employmentDensity)))
        .max(0)
        .min(numberPersons + numberWorkers)
    val offStreetCost = (-35.9285 + 5.2970 * employmentDensity + 15.7921 * populationDensity).max(0).min(2000)
    val onStreetCost = (-22.6038 + 1.6954 * employmentDensity + 7.5305 * populationDensity).max(0).min(2000)
    val residentialParking = if (populationDensity + employmentDensity < 50) {
      (numberPersons / 2 * (50 - (populationDensity + employmentDensity)) / 50).toInt
    } else {
      0
    }
    Map[String, (Int, Double)](
      "Public"      -> (offStreetParking.toInt, offStreetCost),
      "Workplace"   -> (onStreetParking.toInt, onStreetCost),
      "Residential" -> (residentialParking, 0.0)
    )
  }
}
