package beam.agentsim.agents
import beam.agentsim.agents.planning.Trip
import beam.agentsim.agents.vehicles.VehiclePersonId
import beam.router.Modes.BeamMode
import org.matsim.api.core.v01.population.Activity

sealed trait MobilityRequestTrait
case object Pickup extends MobilityRequestTrait
case object Dropoff extends MobilityRequestTrait
case object Relocation extends MobilityRequestTrait
case object Init extends MobilityRequestTrait

case class MobilityRequest(
  person: Option[VehiclePersonId],
  activity: Activity,
  time: Int,
  trip: Trip,
  defaultMode: BeamMode,
  tag: MobilityRequestTrait,
  serviceTime: Int,
  pickupRequest: Option[MobilityRequest] = None,
  routingRequestId: Option[Int] = None
) {
  val nextActivity = Some(trip.activity)

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
    s"${formatTime(time)}|$tag|${personid}|${activity.getType}| => ${formatTime(serviceTime)}"
  }
}
