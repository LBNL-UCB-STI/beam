package beam.utils.data.ctpp

import beam.utils.csv.GenericCsvReader
import beam.utils.data.ctpp.Models.{CTPPEntry, TableInfo, TableShell}
import de.vandermeer.asciitable.AsciiTable
import org.supercsv.prefs.CsvPreference

import scala.util.Try

object CTPPParser {

  def readTable(path: String, predicate: CTPPEntry => Boolean = x => true): Seq[CTPPEntry] = {
    val (it, toClose) = GenericCsvReader.readAs[CTPPEntry](path, toCTTPEntry, predicate)
    try {
      it.toVector
    } finally {
      Try(toClose.close())
    }
  }

  def readShellTable(pathToShellFile: String): Seq[TableShell] = {
    val (it, toClose) = GenericCsvReader
      .readAs[TableShell](pathToShellFile, toTableShell, x => true, new CsvPreference.Builder('"', '|', "\r\n").build)
    try {
      it.toVector
    } finally {
      Try(toClose.close())
    }
  }

  def main(args: Array[String]): Unit = {
    val pathToDocFolder = "D:/Work/beam/Austin/2012-2016 CTPP documentation/"
    val pathToStateFolder = "D:/Work/beam/Austin/2012-2016 CTPP documentation/tx/48/"

    showTable("A112303", pathToDocFolder)
    println

    showTable("A112107", pathToDocFolder)
    println

    showTable("B103101", pathToDocFolder)
    println
  }

  def showTable(tableId: String, pathToDoc: String): Unit = {
    val tablesInfo = getTablesInfo(pathToDoc)
    val (it, toClose) = GenericCsvReader.readAs[TableShell](
      pathToDoc + Metadata.tableShellFileName,
      toTableShell,
      x => x.tblId == tableId,
      new CsvPreference.Builder('"', '|', "\r\n").build
    )
    try {
      val table = new AsciiTable()
      table.addRule()
      table.addRow("Line number", "Line indentation", "Description")

      it.foreach { ts =>
        table.addRule()
        table.addRow(ts.lineNumber.toString, ts.lineIdent.toString, ts.description)
      }
      table.addRule()
      println(s"Description of the table $tableId")
      tablesInfo.find(p => p.tblId == tableId).foreach { t =>
        println(s"Content: ${t.content}")
        println(s"Number of cells: ${t.numberOfCells}")
        println(s"Universe: ${t.universe}")
        println(s"Run Geos: ${t.runGeos}")
      }
      println(table.render())
    } finally {
      Try(toClose.close())
    }
  }

  def getTablesInfo(pathToDoc: String): List[TableInfo] = {

    val (it, toClose) = GenericCsvReader.readAs[TableInfo](pathToDoc + "table_list.csv", toTableInfo, x => true)
    try {
      it.toList
    } finally {
      Try(toClose.close())
    }
  }

  private[ctpp] def toCTTPEntry(rec: java.util.Map[String, String]): CTPPEntry = {
    val geoId = GenericCsvReader.getIfNotNull(rec, "GEOID").toString
    val tblId = GenericCsvReader.getIfNotNull(rec, "TBLID").toString
    val lineNo = GenericCsvReader.getIfNotNull(rec, "LINENO").toInt
    val estimate = GenericCsvReader.getIfNotNull(rec, "EST").toString.replaceAll(",", "").toDouble
    val marginOfError = GenericCsvReader.getIfNotNull(rec, "MOE").toString
    CTPPEntry(geoId = geoId, tblId = tblId, lineNumber = lineNo, estimate = estimate, marginOfError = marginOfError)
  }

  private[ctpp] def toTableShell(rec: java.util.Map[String, String]): TableShell = {
    val tblId = GenericCsvReader.getIfNotNull(rec, "TBLID").toString
    val lineNo = GenericCsvReader.getIfNotNull(rec, "LINENO").toInt
    val lineIdent = GenericCsvReader.getIfNotNull(rec, "LINDENT").toInt
    val description = GenericCsvReader.getIfNotNull(rec, "LDESC").toString
    TableShell(tblId = tblId, lineNumber = lineNo, lineIdent = lineIdent, description = description)
  }

  private[ctpp] def toTableInfo(rec: java.util.Map[String, String]): TableInfo = {
    val tblId = GenericCsvReader.getIfNotNull(rec, "TableId").toString
    val content = GenericCsvReader.getIfNotNull(rec, "Content").toString
    val universe = GenericCsvReader.getIfNotNull(rec, "Universe").toString
    val numberOfCells = GenericCsvReader.getIfNotNull(rec, "Number of Cells").toString.toInt
    val runGeos = GenericCsvReader.getIfNotNull(rec, "Run Geos").toString
    TableInfo(tblId = tblId, content = content, universe = universe, numberOfCells = numberOfCells, runGeos = runGeos)
  }
}
