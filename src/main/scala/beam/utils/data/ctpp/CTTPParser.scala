package beam.utils.data.ctpp

import beam.utils.csv.GenericCsvReader
import beam.utils.data.ctpp.Models.{CTTPEntry, TableInfo, TableShell}
import de.vandermeer.asciitable.AsciiTable
import org.supercsv.prefs.CsvPreference

import scala.util.Try

object CTTPParser {
  def toCTTPEntry(rec: java.util.Map[String, String]): CTTPEntry = {
    val geoId = GenericCsvReader.getIfNotNull(rec, "GEOID").toString
    val tblId = GenericCsvReader.getIfNotNull(rec, "TBLID").toString
    val lineNo = GenericCsvReader.getIfNotNull(rec, "LINENO").toInt
    val estimate = GenericCsvReader.getIfNotNull(rec, "EST").toString.replaceAll(",", "").toDouble
    val marginOfError = GenericCsvReader.getIfNotNull(rec, "MOE").toString
    CTTPEntry(geoId = geoId, tblId = tblId, lineNumber = lineNo, estimate = estimate, marginOfError = marginOfError)
  }

  def toTableShell(rec: java.util.Map[String, String]): TableShell = {
    val tblId = GenericCsvReader.getIfNotNull(rec, "TBLID").toString
    val lineNo = GenericCsvReader.getIfNotNull(rec, "LINENO").toInt
    val lineIdent = GenericCsvReader.getIfNotNull(rec, "LINDENT").toInt
    val description = GenericCsvReader.getIfNotNull(rec, "LDESC").toString
    TableShell(tblId = tblId, lineNumber = lineNo, lineIdent = lineIdent, description = description)
  }

  val tableShellFileName: String = "acs_ctpp_2012thru2016_table_shell.txt"

  def main(args: Array[String]): Unit = {

    val pathToDocFolder = "D:/Work/beam/Austin/2012-2016 CTPP documentation/"
    val pathToStateFolder = "D:/Work/beam/Austin/2012-2016 CTPP documentation/tx/48/"

    showTable("A202108", pathToDocFolder)

    val (it, toClose) = GenericCsvReader.readAs[CTTPEntry](pathToStateFolder + "TX_2012thru2016_A202108.csv", toCTTPEntry, x => true)
    try {
      val allData = it.toVector
      println(s"size: ${allData.size}")
    }
    finally {
      Try(toClose.close())
    }
  }

  def showTable(tableId: String, pathToDoc: String): Unit = {
    val tablesInfo = getTablesInfo(pathToDoc)

    val csvPreference = new CsvPreference.Builder('"', '|', "\r\n").build

    val (it, toClose) = GenericCsvReader.readAs[TableShell](pathToDoc + tableShellFileName, toTableShell, x => x.tblId == tableId, csvPreference)
    val table = new AsciiTable()
    table.addRule()
    table.addRow("Line number", "Line indentation", "Description")
    try {
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
    }
    finally {
      Try(toClose.close())
    }
  }

  def getTablesInfo(pathToDoc: String): List[TableInfo] = {
    def toTableInfo(rec: java.util.Map[String, String]): TableInfo = {
      val tblId = GenericCsvReader.getIfNotNull(rec, "TableId").toString
      val content = GenericCsvReader.getIfNotNull(rec, "Content").toString
      val universe = GenericCsvReader.getIfNotNull(rec, "Universe").toString
      val numberOfCells = GenericCsvReader.getIfNotNull(rec, "Number of Cells").toString.toInt
      val runGeos = GenericCsvReader.getIfNotNull(rec, "Run Geos").toString
      TableInfo(tblId = tblId, content = content, universe = universe, numberOfCells  = numberOfCells, runGeos = runGeos)
    }
    val (it, toClose) = GenericCsvReader.readAs[TableInfo](pathToDoc + "table_list.csv", toTableInfo, x => true)
    try {
      it.toList
    }
    finally {
      Try(toClose.close())
    }
  }
}
