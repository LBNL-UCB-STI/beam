package beam.agentsim.infrastructure.parking

sealed trait DepotStallLocationType

object DepotStallLocationType {
  case object AtRequestLocation extends DepotStallLocationType
  case object AtTAZCenter extends DepotStallLocationType
}
