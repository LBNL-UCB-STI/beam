package beam.utils.data.ctpp.readers

import java.io.{File, FileFilter}

import beam.utils.data.ctpp.CTPPParser
import beam.utils.data.ctpp.Models.CTPPEntry
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData, Table}
import com.typesafe.scalalogging.StrictLogging

abstract class BaseTableReader(
  protected val dbInfo: CTPPDatabaseInfo,
  protected val table: Table,
  maybeGeographyLevelFilter: Option[String]
) extends StrictLogging {
  import BaseTableReader._

  protected val stateToCsvTablePath: Map[String, String] =
    dbInfo.states.map(stateCode => stateCode -> findTablePath(stateCode)).toMap
  logger.info(s"Path to table $table for states ${dbInfo.states}")
  stateToCsvTablePath.foreach {
    case (state, fullPath) =>
      logger.info(s"   $state: $fullPath")
  }

  def geographyLevelFilter(x: CTPPEntry): Boolean = {
    maybeGeographyLevelFilter.forall(level => x.geoId.startsWith(level))
  }

  protected def findTablePath(stateCode: String): String = {
    val fullStatePath = s"${dbInfo.pathToData.path}/$stateCode/"
    val folder = new File(fullStatePath)
    require(
      folder.isDirectory && folder.canRead,
      s"THe folder ${folder.getAbsolutePath} does not exist or permission is denied"
    )
    findFile(fullStatePath, table.name)
  }

  protected def findEstimateByLineNumberOr0(xs: Iterable[CTPPEntry], lineNumber: Int, what: String): Double = {
    xs.find(x => x.lineNumber == lineNumber).map(_.estimate).getOrElse {
      // TODO better data missing handling
      // logger.warn(s"Could not find total count for '$what' in input ${xs.mkString(" ")}")
      0
    }
  }

  protected def readRaw(): Iterable[CTPPEntry] = {
    stateToCsvTablePath.values.flatMap { path =>
      CTPPParser
        .readTable(path, geographyLevelFilter)
    }
  }
}

object BaseTableReader {
  case class PathToData(path: String) extends AnyVal
  case class CTPPDatabaseInfo(pathToData: PathToData, states: Set[String])

  sealed abstract class Table(val name: String, val desc: String)

  object Table {
    case object TotalHouseholds extends Table("A112100", "Total households (1)")
    case object VehiclesAvailable extends Table("A111102", "Vehicles available (6)")
    case object PopulationInHouseholds extends Table("A112107", "Population in households (1)")
    case object Age extends Table("A101101", "Age (1)")
    case object Sex extends Table("A101110", "Sex (3)")
    case object MeanHouseholdIncome extends Table("B112103", "Mean Household income in the past 12 months (2016$)  (1)")
    case object MedianHouseholdIncome
        extends Table("B112104", "Median Household income in the past 12 months (2016$) (1)")
    case object HouseholdSizeByUnitsInStructure extends Table("A112210", "Household size (5) by Units in Structure (9)")
    case object UsualHoursWorkedPerWeek extends Table("A102109", "Usual Hours worked per week (7)")
    case object MeanOfTransportation extends Table("A302103", "Means of transportation (18)")
    case object TimeLeavingHome extends Table("B302104", "Time leaving home (17)")
    case object HouseholdIncomeInThePast12Months
        extends Table(
          "B303100",
          "Household income in the past 12 months (2016$) (9) (Workers 16 years and over in households)"
        )
    case object TravelTime extends Table("B302106", "Travel time (12) (Workers 16 years and over)")
    case object AgeOfWorker
        extends Table(
          "B302101",
          "Age of Worker (8) (Workers 16 years and over) - Large Geos Only: State, County, MCD, Place, PUMA/POWPUMA, MSA"
        )
    case object Industry
        extends Table(
          "B302102",
          "Industry (8) (Workers 16 years and over)"
        )
  }

  def findFile(folderPath: String, fileName: String): String = {
    val foundFiles = new File(folderPath).listFiles(new FileFilter {
      override def accept(pathname: File): Boolean = {
        pathname.getName.contains(fileName)
      }
    })
    require(
      foundFiles.size == 1,
      s"Could not find file '${fileName}' under folder '${folderPath}'. Please, make sure input is correct"
    )
    foundFiles.head.getAbsolutePath
  }
}
