package beam.utils.data.ctpp.readers.residence

import beam.utils.data.ctpp.CTPPParser
import beam.utils.data.ctpp.models.{ResidenceGeography, Vehicles}
import beam.utils.data.ctpp.readers.BaseTableReader
import beam.utils.data.ctpp.readers.BaseTableReader.{PathToData, Table}

import scala.util.{Failure, Success}

class VehiclesAvailableTableReader(pathToData: PathToData, val residenceGeography: ResidenceGeography)
    extends BaseTableReader(pathToData, Table.VehiclesAvailable, Some(residenceGeography.level)) {

  def read(): Map[String, Map[Vehicles, Double]] = {
    val vehiclesAvailableMap = CTPPParser
      .readTable(pathToCsvTable, geographyLevelFilter)
      .groupBy(x => x.geoId)
      .map {
        case (geoId, xs) =>
          // One geoId contains multiple vehicles range
          val vehicles = xs.flatMap { entry =>
            val maybeAge = Vehicles(entry.lineNumber) match {
              case Failure(ex) =>
                logger.warn(s"Could not represent $entry as vehicle: ${ex.getMessage}", ex)
                None
              case Success(value) =>
                Some(value -> entry.estimate)
            }
            maybeAge
          }
          geoId -> vehicles.toMap
      }
    vehiclesAvailableMap
  }
}
