package scripts.ctpp.models

case class OD[+T](source: String, destination: String, attribute: T, value: Double)
