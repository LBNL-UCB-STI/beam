package beam.agentsim.agents.ridehail.kpis

import beam.agentsim.agents.ridehail.kpis.KpiRegistry.Kpi
import beam.utils.Registry

object KpiRegistry {

  trait Kpi

}

/**
  * List of default key performance indicators.
  */
object DefaultKpiRegistry extends Registry[Kpi] {

  case object NumberOfIdleVehicles extends Kpi

  override val entries = Set(NumberOfIdleVehicles)
}
