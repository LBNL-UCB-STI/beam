package beam.agentsim.agents.ridehail.charging

import beam.agentsim.agents.ridehail.charging.StallAssignmentStrategyRegistry.StallAssignmentStrategyName
import beam.utils.Registry

object StallAssignmentStrategyRegistry {

  trait StallAssignmentStrategyName

}

/**
  * List of default stall assignment strategies.
  */
object DefaultStallAssignmentStrategyRegistry extends Registry[StallAssignmentStrategyName] {

  case object SeparateParkingZoneStrategy extends StallAssignmentStrategyName

  override val entries = Set(SeparateParkingZoneStrategy)
}
