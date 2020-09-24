package beam.agentsim.agents.planning

import org.matsim.api.core.v01.population.{Activity, PlanElement}
import org.matsim.utils.objectattributes.attributable.Attributes

/***
  * BEAM
  */
class Tour(var trips: Vector[Trip] = Vector()) extends PlanElement {
  // TODO: is it possible to assume trips will be never be empty?
  // in case yes we should have a required, otherwise this class is totally error-prone

  def originActivity(): Activity = {
    trips.head.activity
  }

  def destActivity(): Activity = {
    trips.reverse.head.activity
  }

  override def getAttributes = new Attributes

  def addTrip(newTrip: Trip): Unit = trips = trips :+ newTrip

  @SuppressWarnings(Array("UnsafeTraversableMethods"))
  def tripIndexOfElement(planElement: PlanElement): Int = {
    (for (trip <- trips.zipWithIndex
          if trip._1 == planElement || trip._1.activity == planElement || (trip._1.leg.isDefined && trip._1.leg.get == planElement))
      yield trip._2).head
  }
}
