package beam.agentsim.agents
import beam.agentsim.agents.planning.{Tour, Trip}
import beam.agentsim.agents.vehicles.{BeamVehicleType, PersonIdWithActorRef}
import beam.router.Modes.BeamMode
import beam.router.model.{BeamLeg, EmbodiedBeamLeg}
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.population.Activity
import org.matsim.core.population.PopulationUtils

sealed trait MobilityRequestType
case object Pickup extends MobilityRequestType { override def toString: String = "pickup" }
case object Dropoff extends MobilityRequestType { override def toString: String = "dropoff" }
case object Relocation extends MobilityRequestType { override def toString: String = "relocation" }
case object EnRoute extends MobilityRequestType { override def toString: String = "enroute" }
case object Init extends MobilityRequestType { override def toString: String = "init" }

case class MobilityRequest(
  person: Option[PersonIdWithActorRef],
  activity: Activity,
  baselineNonPooledTime: Int,
  trip: Trip,
  defaultMode: BeamMode,
  tag: MobilityRequestType,
  serviceTime: Int,
  upperBoundTime: Int,
  serviceDistance: Double,
  pickupRequest: Option[MobilityRequest] = None,
  routingRequestId: Option[Int] = None,
  vehicleOccupancy: Option[Int] = None,
  beamLegAfterTag: Option[EmbodiedBeamLeg] = None // In other words, this leg is traversed **after** the action described in "tag" so if tag is a dropoff, we do the dropoff first then complete the beamLeg
) {
  val nextActivity: Some[Activity] = Some(trip.activity)

  def isPickup: Boolean = tag == Pickup
  def isDropoff: Boolean = tag == Dropoff

  def formatTime(secs: Int): String = {
    s"${secs / 3600}:${(secs % 3600) / 60}:${secs % 60}"
  }
  override def toString: String = {
    val personid = person match {
      case Some(p) => p.personId.toString
      case None    => "None"
    }
    s"${baselineNonPooledTime}|$tag|${personid}|${activity.getCoord}| => ${serviceTime}"
  }
  override def equals(that: Any): Boolean = {
    that match {
      case _: MobilityRequest =>
        val thatMobReq = that.asInstanceOf[MobilityRequest]
        this.person == thatMobReq.person && this.baselineNonPooledTime == thatMobReq.baselineNonPooledTime && this.tag == thatMobReq.tag
      case _ =>
        false
    }
  }
}

object MobilityRequest {

  def simpleRequest(
    requestType: MobilityRequestType,
    person: Option[PersonIdWithActorRef],
    leg: Option[EmbodiedBeamLeg]
  ): MobilityRequest = {
    val act = PopulationUtils.createActivityFromCoord("", new Coord(-1, -1))
    MobilityRequest(
      person,
      act,
      -1,
      Trip(act, None, new Tour()),
      BeamMode.CAR,
      requestType,
      -1,
      -1,
      -1,
      None,
      None,
      None,
      leg
    )
  }
}
