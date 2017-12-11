package beam.agentsim.agents.planning

import org.matsim.api.core.v01.population.PlanElement
import org.matsim.utils.objectattributes.attributable.Attributes

/**
  * BEAM
  */
class Tour(var trips: Vector[Trip] = Vector()) extends PlanElement{
  override def getAttributes = new Attributes

  def addTrip(newTrip: Trip) = trips = trips :+ newTrip
}
