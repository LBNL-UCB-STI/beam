package beam.agentsim.core

import enumeratum.values._
import org.matsim.api.core.v01.TransportMode
import org.opentripplanner.routing.core.TraverseMode

import scala.collection.immutable


/**
  * [[ValueEnum]] containing all of the translations b/w BEAM <==> OTP [[TraverseMode]] MATSim [[TransportMode]].
  *
  * Note: There is an implicit conversion
  *
  * Created by sfeygin on 4/5/17.
  */
object Modes {

  sealed abstract class BeamMode(val value: String, val otpMode: Option[TraverseMode], val matsimMode: String) extends StringEnumEntry

  object BeamMode extends StringEnum[BeamMode] with StringCirceEnum[BeamMode] {

    override val values: immutable.IndexedSeq[BeamMode] = findValues

    // Driving / Automobile-like (taxi is a bit of a hybrid)

    case object CAR extends BeamMode(value = "car", Some(TraverseMode.CAR), TransportMode.car)

    case object TAXI extends BeamMode(value = "taxi", None, TransportMode.other)

    case object EV extends BeamMode(value = "ev", None, TransportMode.other)

    // Transit

    case object BUS extends BeamMode(value = "bus", Some(TraverseMode.BUS), TransportMode.pt)

    case object FUNICULAR extends BeamMode(value = "funicular", Some(TraverseMode.FUNICULAR), TransportMode.pt)

    case object GONDOLA extends BeamMode(value = "gondola", Some(TraverseMode.GONDOLA), TransportMode.pt)

    case object CABLE_CAR extends BeamMode(value = "cable_car", Some(TraverseMode.CABLE_CAR), TransportMode.pt)

    case object FERRY extends BeamMode(value = "ferry", Some(TraverseMode.FERRY), TransportMode.pt)

    case object TRANSIT extends BeamMode(value = "transit", Some(TraverseMode.TRANSIT), TransportMode.pt)

    case object RAIL extends BeamMode(value = "rail", Some(TraverseMode.RAIL), TransportMode.pt)

    case object SUBWAY extends BeamMode(value = "subway", Some(TraverseMode.SUBWAY), TransportMode.pt)

    case object TRAM extends BeamMode(value = "tram", Some(TraverseMode.TRAM), TransportMode.pt)

    // Non-motorized

    case object WALK extends BeamMode(value = "walk", Some(TraverseMode.WALK), TransportMode.walk)

    case object BIKE extends BeamMode(value = "bike", Some(TraverseMode.BICYCLE), TransportMode.walk)

    // Transit-specific non-motorized
    case object LEG_SWITCH extends BeamMode(value = "leg_switch", Some(TraverseMode.LEG_SWITCH), TransportMode.other) // This is kind-of like a transit walk, but not really... best to make leg_switch its own type

    case object TRANSIT_WALK extends BeamMode(value = "transit_walk", None, TransportMode.transit_walk)

    case object WAITING extends BeamMode(value = "waiting", None, TransportMode.other)

    // Transit-specific to filter
    // FIXME: These aren't really modes; just included for algorithm purposes
    case object PRE_BOARD extends BeamMode(value = "PRE_BOARD", None, TransportMode.other)
    case object PRE_ALIGHT extends BeamMode(value = "PRE_ALIGHT", None, TransportMode.other)
    case object BOARDING extends BeamMode(value = "BOARDING", None, TransportMode.other)
    case object ALIGHTING extends BeamMode(value = "ALIGHTING", None, TransportMode.other)

  }

  implicit def beamMode2OtpMode(beamMode: BeamMode): TraverseMode = beamMode.otpMode.get


}
