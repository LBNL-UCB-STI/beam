package scripts.ctpp

object Models {
  case class CTPPEntry(geoId: String, tblId: String, lineNumber: Int, estimate: Double, marginOfError: String)

}
