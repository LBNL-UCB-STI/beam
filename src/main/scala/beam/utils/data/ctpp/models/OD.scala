package beam.utils.data.ctpp.models

case class OD[+T](source: String, destination: String, attribute: T, value: Double)
