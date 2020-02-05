package beam.utils.data.ctpp.readers

import java.io.{File, FileFilter}

import beam.utils.data.ctpp.Models.CTPPEntry
import beam.utils.data.ctpp.readers.BaseTableReader.Table

abstract class BaseTableReader(val pathToData: String, val table: Table, maybeGeographyLevelFilter: Option[String]) {
  import BaseTableReader._

  protected val pathToCsvTable: String = findTablePath()

  def geographyLevelFilter(x: CTPPEntry): Boolean = {
    maybeGeographyLevelFilter.forall(level => x.geoId.startsWith(level))
  }

  protected def findTablePath(): String = findFile(pathToData, table.name)
}

object BaseTableReader {
  sealed abstract class Table(val name: String, val desc: String)

  object Table {
    case object TotalHouseholds extends Table("A112100", "Total households")
    case object VehiclesAvailable extends Table("A111102", "Vehicles available")
    case object PopulationInHouseholds extends Table("A112107", "Population in households")
    case object Age extends Table("A101101", "Age")
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
