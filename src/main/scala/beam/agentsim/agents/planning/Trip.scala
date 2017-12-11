package beam.agentsim.agents.planning

import org.matsim.api.core.v01.population.{Activity, Leg, PlanElement}
import org.matsim.utils.objectattributes.attributable.Attributes

/**
  * BEAM
  */
case class Trip(val activity: Activity, val leg : Option[Leg]) extends PlanElement{
  override def getAttributes = new Attributes
}
