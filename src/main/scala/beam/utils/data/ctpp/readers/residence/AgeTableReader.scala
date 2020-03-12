package beam.utils.data.ctpp.readers.residence

import beam.utils.data.ctpp.CTPPParser
import beam.utils.data.ctpp.models.{AgeRange, ResidenceGeoParser, ResidenceGeography}
import beam.utils.data.ctpp.readers.BaseTableReader
import beam.utils.data.ctpp.readers.BaseTableReader.{PathToData, Table}

class AgeTableReader(pathToData: PathToData, val residenceGeography: ResidenceGeography)
    extends BaseTableReader(pathToData, Table.Age, Some(residenceGeography.level)) {

  private val lineNumberToAge: Map[Int, AgeRange] = Map(
    2  -> AgeRange(Range(0, 16)),
    3  -> AgeRange(Range.inclusive(16, 17)),
    4  -> AgeRange(Range.inclusive(18, 20)),
    5  -> AgeRange(Range.inclusive(21, 24)),
    6  -> AgeRange(Range.inclusive(25, 34)),
    7  -> AgeRange(Range.inclusive(35, 44)),
    8  -> AgeRange(Range.inclusive(45, 59)),
    9  -> AgeRange(Range.inclusive(60, 64)),
    10 -> AgeRange(Range.inclusive(65, 74)),
    11 -> AgeRange(Range.inclusive(75, 100)) // "75 years and over"
  )

  def read(): Map[String, Map[AgeRange, Double]] = {
    val seq = CTPPParser
      .readTable(pathToCsvTable, geographyLevelFilter)
      .filter(x => x.geoId.startsWith("C0200US")) // `00` => Not a geographic component
    val ageMap = seq
      .flatMap { entry =>
        if (entry.lineNumber == 1) None
        else {
          val maybeAge = lineNumberToAge.get(entry.lineNumber) match {
            case None =>
              logger.warn(s"Could not find a match for the line number ${entry.lineNumber} as age range")
              None
            case Some(value) =>
              Some(value -> entry.estimate)
          }
          for {
            geoId      <- ResidenceGeoParser.parse(entry.geoId).toOption
            (age, cnt) <- maybeAge
          } yield (geoId, age, cnt)
        }
      }
      .groupBy { case (geoId, _, _) => geoId }
      .map {
        case (geoId, xs) =>
          val ageToCntMap = xs.map {
            case (_, ageRng, cnt) =>
              ageRng -> cnt
          }.toMap
          geoId -> ageToCntMap
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
    val peopleElderThan16 =
      ageToTotalNumberOfWorkers.filter { case (age, _) => age.range.start > 16 }.values.sum
    println(s"peopleElderThan16: ${peopleElderThan16.toInt}")
  }
}
