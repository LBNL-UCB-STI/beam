package beam.utils.data.ctpp

object Models {
  case class CTTPEntry(geoId: String, tblId: String, lineNumber: Int, estimate: Double, marginOfError: String)

  case class TableShell(tblId: String, lineNumber: Int, lineIdent: Int, description: String)

  case class TableInfo(tblId: String, content: String, universe: String, numberOfCells: Int, runGeos: String)
}
