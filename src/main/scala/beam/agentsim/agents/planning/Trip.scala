package beam.agentsim.agents.planning

import org.matsim.api.core.v01.population.{Activity, Leg, PlanElement}
import org.matsim.utils.objectattributes.attributable.Attributes

/**
  * BEAM
  */
case class Trip(activity: Activity, leg: Option[Leg], parentTour: Tour) extends PlanElement {
  override def getAttributes = new Attributes
}
