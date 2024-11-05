package beam.agentsim.agents.planning

import org.matsim.api.core.v01.population.{Activity, PlanElement}
import org.matsim.utils.objectattributes.attributable.Attributes

class Tour(
  var tourId: Int = 0,
  private var tripsInternal: Vector[Trip] = Vector(),
  val originActivity: Option[Activity] = None
) extends PlanElement {

  def trips: Seq[Trip] = tripsInternal

  def setTourId(newTourId: Int): Unit = { tourId = newTourId }

  def activities: Seq[Activity] = originActivity.toSeq ++ trips.map(_.activity)

  override def getAttributes = new Attributes

  def addTrip(newTrip: Trip): Unit = tripsInternal = tripsInternal :+ newTrip

  def tripIndexOfElement(planElement: PlanElement): Option[Int] = {
    val indexes = for {
      tripsWithIndex <- tripsInternal.zipWithIndex
      if isTripOrActivityPlanElement(tripsWithIndex._1, planElement) || isTripLegPlanElement(
        tripsWithIndex._1,
        planElement
      )
    } yield tripsWithIndex._2
    indexes.headOption
  }

  @inline
  private def isTripLegPlanElement(
    trip: Trip,
    planElement: PlanElement
  ) = trip.leg.contains(planElement)

  @inline
  private def isTripOrActivityPlanElement(
    trip: Trip,
    planElement: PlanElement
  ) = trip == planElement || trip.activity == planElement

}
