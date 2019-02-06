package beam.agentsim.infrastructure.parking

sealed trait AgencyStallLocation
object AgencyStallLocation {
  case object AtRequestLocation extends AgencyStallLocation
  case object AtTAZCenter extends AgencyStallLocation
}