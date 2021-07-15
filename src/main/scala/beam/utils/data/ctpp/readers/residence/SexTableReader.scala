package beam.utils.data.ctpp.readers.residence

import beam.utils.data.ctpp.models.{Gender, ResidenceGeography}
import beam.utils.data.ctpp.readers.BaseTableReader
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, Table}

import scala.util.{Failure, Success}

class SexTableReader(dbInfo: CTPPDatabaseInfo, val residenceGeography: ResidenceGeography)
    extends BaseTableReader(dbInfo, Table.Sex, Some(residenceGeography.level)) {

  def read(): Map[String, Map[Gender, Double]] = {
    val map = readRaw()
      .groupBy(x => x.geoId)
      .map { case (geoId, xs) =>
        // We skip lineNumber == 1 because it is total counter
        val genders = xs.filter(x => x.lineNumber != 1).flatMap { entry =>
          val maybeAge = Gender(entry.lineNumber) match {
            case Failure(ex) =>
              logger.warn(s"Could not represent $entry as gender: ${ex.getMessage}", ex)
              None
            case Success(value) =>
              Some(value -> entry.estimate)
          }
          maybeAge
        }
        geoId -> genders.toMap
      }
    map
  }
}
