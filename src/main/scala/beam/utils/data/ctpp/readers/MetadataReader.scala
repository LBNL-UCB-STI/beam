package beam.utils.data.ctpp.readers

import beam.utils.csv.GenericCsvReader
import beam.utils.data.ctpp.readers.MetadataReader.{TableInfo, TableShell}
import com.typesafe.scalalogging.StrictLogging
import de.vandermeer.asciitable.AsciiTable
import org.supercsv.prefs.CsvPreference

import scala.util.Try

class MetadataReader(val pathToDoc: String) extends StrictLogging {
  private val tableShellFileName: String = "acs_ctpp_2012thru2016_table_shell.txt"
  private val tableShellPath: String = pathToDoc + "/" + tableShellFileName

  private val tableInfoFileName: String = "table_list.csv"
  private val tableInfoPath: String = pathToDoc + "/" + tableInfoFileName

  def showTable(tableId: String): Unit = {
    val tablesInfo = getTablesInfo()
    val (it, toClose) = GenericCsvReader.readAs[TableShell](
      pathToDoc + tableShellFileName,
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
      logger.info(s"Description of the table $tableId")
      tablesInfo.find(p => p.tblId == tableId).foreach { t =>
        logger.info(s"Content: ${t.content}")
        logger.info(s"Number of cells: ${t.numberOfCells}")
        logger.info(s"Universe: ${t.universe}")
        logger.info(s"Run Geos: ${t.runGeos}")
      }
      logger.info(table.render())
    } finally {
      Try(toClose.close())
    }
  }

  def readShellTable(): Seq[TableShell] = {
    val (it, toClose) = GenericCsvReader
      .readAs[TableShell](tableShellPath, toTableShell, _ => true, new CsvPreference.Builder('"', '|', "\r\n").build)
    try {
      it.toVector
    } finally {
      Try(toClose.close())
    }
  }

  def getTablesInfo(): List[TableInfo] = {
    val (it, toClose) = GenericCsvReader.readAs[TableInfo](tableInfoPath, toTableInfo, _ => true)
    try {
      it.toList
    } finally {
      Try(toClose.close())
    }
  }

  private[readers] def toTableShell(rec: java.util.Map[String, String]): TableShell = {
    val tblId = GenericCsvReader.getIfNotNull(rec, "TBLID")
    val lineNo = GenericCsvReader.getIfNotNull(rec, "LINENO").toInt
    val lineIdent = GenericCsvReader.getIfNotNull(rec, "LINDENT").toInt
    val description = GenericCsvReader.getIfNotNull(rec, "LDESC")
    TableShell(tblId = tblId, lineNumber = lineNo, lineIdent = lineIdent, description = description)
  }

  private[readers] def toTableInfo(rec: java.util.Map[String, String]): TableInfo = {
    val tblId = GenericCsvReader.getIfNotNull(rec, "TableId")
    val content = GenericCsvReader.getIfNotNull(rec, "Content")
    val universe = GenericCsvReader.getIfNotNull(rec, "Universe")
    val numberOfCells = GenericCsvReader.getIfNotNull(rec, "Number of Cells").toInt
    val runGeos = GenericCsvReader.getIfNotNull(rec, "Run Geos")
    TableInfo(tblId = tblId, content = content, universe = universe, numberOfCells = numberOfCells, runGeos = runGeos)
  }
}

object MetadataReader {
  case class TableShell(tblId: String, lineNumber: Int, lineIdent: Int, description: String)
  case class TableInfo(tblId: String, content: String, universe: String, numberOfCells: Int, runGeos: String)

  def main(args: Array[String]): Unit = {
    val metaRdr = new MetadataReader("D:/Work/beam/Austin/2012-2016 CTPP documentation/")
    metaRdr.readShellTable.filter(x => x.tblId == "B302101").foreach(println)

    metaRdr.showTable("B302101")
  }
}
