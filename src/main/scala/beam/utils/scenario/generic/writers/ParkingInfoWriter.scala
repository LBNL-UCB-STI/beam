package beam.utils.scenario.generic.writers

import beam.utils.csv.CsvWriter
import beam.utils.data.synthpop.GeoService
import beam.utils.data.synthpop.models.Models.{GenericGeoId, TazGeoId}
import beam.utils.scenario.HouseholdInfo
import com.vividsolutions.jts.geom.Geometry

import scala.util.Try

trait ParkingInfoWriter {
  def write(path: String, geoService: GeoService, tazCounts: Map[GenericGeoId, (Int, Int)]): Unit
}

object CsvParkingInfoWriter extends ParkingInfoWriter {
  private val headers: Array[String] = Array("taz", "parkingType", "pricingModel", "chargingType", "numStalls", "feeInCents", "reservedFor")

  override def write(path: String, geoService: GeoService, tazCounts: Map[GenericGeoId, (Int, Int)]): Unit = {
    val csvWriter = new CsvWriter(path, headers)
    try {
      tazCounts.foreach { case (tazGeoId, (numberPersons, numberWorkers)) =>
        val scaledTazArea = geoService.tazGeoIdToGeom.get(tazGeoId.asInstanceOf[TazGeoId]) match {
          case Some(geometry: Geometry) => geometry.getArea * 1000
          case _ => 100000.0
        }
        val parkingInfo = getNumberOfSpaces( scaledTazArea, numberPersons, numberWorkers)
        parkingInfo.foreach {
          case (parkingType, (spots, cost)) =>
            csvWriter.write(
              tazGeoId.asInstanceOf[TazGeoId].taz,
              parkingType,
              "Block",
              "None",
              spots.toString,
              cost.toString,
              "Any"
            )
        }
      }
    } finally {
      Try(csvWriter.close())
    }
  }

  private def getNumberOfSpaces(scaledTazArea: Double, numberPersons: Int, numberWorkers: Int): Map[String, (Int, Double)] = {
    val populationDensity = numberPersons.toDouble / scaledTazArea
    val employmentDensity = numberWorkers.toDouble / scaledTazArea
    val offStreetParking = math.exp(-1.974 + 1.002 * math.log(employmentDensity)) * scaledTazArea
    val onStreetParking = math.exp(-0.204 + -0.886 * math.log(employmentDensity + populationDensity)) * (numberPersons + numberWorkers)
    val offStreetCost = 28.6894 + 114.7287 * math.log(employmentDensity) - 7.3747 * math.log(populationDensity)
    val onStreetCost = 1.4301 + 38.2084 * math.log(employmentDensity) + 5.2333 * math.log(populationDensity)
    val residentialParking = if (populationDensity + employmentDensity < 100) {
      numberPersons / 2 * (100 - (populationDensity + employmentDensity )).toInt
    } else {
      0
    }
    Map[String, (Int, Double)](
      "Public" -> (offStreetParking.toInt, offStreetCost),
      "Workplace" -> (onStreetParking.toInt, onStreetCost),
      "Residential" -> (residentialParking, 0.0)
    )
    }
}
