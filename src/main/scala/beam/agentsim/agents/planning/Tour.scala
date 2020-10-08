package beam.agentsim.agents.planning

import org.matsim.api.core.v01.population.{Activity, Leg, PlanElement}
import org.matsim.utils.objectattributes.attributable.Attributes

/***
  * BEAM
  */
class Tour(var trips: Vector[Trip] = Vector()) extends PlanElement {

  def originActivity(): Activity = {
    trips.head.activity
  }

  def destActivity(): Activity = {
    trips.reverse.head.activity
  }

  override def getAttributes = new Attributes

  def addTrip(newTrip: Trip): Unit = trips = trips :+ newTrip

  def tripIndexOfElement(planElement: PlanElement): Int = {
    val checkLeg = (leg: Option[Leg]) =>
      leg match {
        case Some(value) => value == planElement
        case None        => false
    }
    (for (trip <- trips.zipWithIndex
          if trip._1 == planElement || trip._1.activity == planElement || checkLeg(trip._1.leg))
      yield trip._2).head
  }

}
