package beam.utils.data.ctpp.readers

import beam.utils.data.ctpp.models.{Age, ResidenceGeography}
import beam.utils.data.ctpp.readers.BaseTableReader.{PathToData, Table}
import beam.utils.data.ctpp.CTPPParser

import scala.util.{Failure, Success}

class AgeTableReader(pathToData: PathToData, val residenceGeography: ResidenceGeography)
    extends BaseTableReader(pathToData, Table.Age, Some(residenceGeography.level)) {

  def read(): Map[String, Map[Age, Double]] = {
    val ageMap = CTPPParser
      .readTable(pathToCsvTable, geographyLevelFilter)
      .groupBy(x => x.geoId)
      .map {
        case (geoId, xs) =>
          // One geoId contains multiple age ranges
          val allAges = xs.flatMap { entry =>
            // Skip total age
            if (entry.lineNumber == 1) None
            else {
              val maybeAge = Age(entry.lineNumber) match {
                case Failure(ex) =>
                  logger.warn(s"Could not represent $entry as age: ${ex.getMessage}", ex)
                  None
                case Success(value) =>
                  Some(value -> entry.estimate)
              }
              maybeAge
            }
          }
          geoId -> allAges.toMap
      }
    ageMap
  }

}

object AgeTableReader {

  def main(args: Array[String]): Unit = {
    val rdr =
      new AgeTableReader(PathToData("D:/Work/beam/Austin/2012-2016 CTPP documentation/tx/48"), ResidenceGeography.TAZ)
    val readData = rdr.read()
    val ageToTotalNumberOfWorkers = readData.values.flatten
      .groupBy { case (age, cnt) => age }
      .map {
        case (age, xs) =>
          age -> xs.map(_._2).sum
      }
    ageToTotalNumberOfWorkers.foreach {
      case (age, cnt) =>
        println(s"$age => $cnt")
    }
    val totalNumberOfWorkers =
      ageToTotalNumberOfWorkers.filter { case (age, _) => age != Age.`Under 16 years` }.values.sum
    println(s"totalNumberOfWorkers: ${totalNumberOfWorkers.toInt}")
  }
}
