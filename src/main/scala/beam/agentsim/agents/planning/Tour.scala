package beam.agentsim.agents.planning

import org.matsim.api.core.v01.population.PlanElement
import org.matsim.utils.objectattributes.attributable.Attributes

/**
  * BEAM
  */
class Tour(var trips: Vector[Trip] = Vector()) extends PlanElement{

  override def getAttributes = new Attributes

  def addTrip(newTrip: Trip): Unit = trips = trips :+ newTrip

  def tripIndexOfElement(planElement: PlanElement): Int = {
    (for(trip <- trips.zipWithIndex if trip._1 == planElement || trip._1.activity == planElement || (trip._1.leg.isDefined && trip._1.leg.get == planElement))yield (trip._2)).head
  }
}
