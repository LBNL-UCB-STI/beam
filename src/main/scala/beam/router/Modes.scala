package beam.router

import beam.router.Modes.BeamMode.{
  BIKE,
  BUS,
  CABLE_CAR,
  CAR,
  CAV,
  FERRY,
  FUNICULAR,
  GONDOLA,
  RAIL,
  RIDE_HAIL,
  SUBWAY,
  TRAM,
  TRANSIT,
  WALK
}
import com.conveyal.r5.api.util.{LegMode, TransitModes}
import com.conveyal.r5.profile.StreetMode
import enumeratum.values._
import org.matsim.api.core.v01.TransportMode

import scala.collection.immutable
import scala.language.implicitConversions

/**
  * [[ValueEnum]] containing all of the translations b/w BEAM <==> R5[[LegMode]] MATSim [[TransportMode]].
  *
  * Note: There is an implicit conversion
  *
  * Created by sfeygin on 4/5/17.
  */
object Modes {

  sealed abstract class BeamMode(
    val value: String,
    val r5Mode: Option[Either[LegMode, TransitModes]],
    val matsimMode: String
  ) extends StringEnumEntry {

    def isTransit: Boolean = isR5TransitMode(this)
    def isMassTransit: Boolean = this == SUBWAY || this == RAIL || this == FERRY || this == TRAM
    def isRideHail: Boolean = this == RIDE_HAIL
  }

  object BeamMode extends StringEnum[BeamMode] with StringCirceEnum[BeamMode] {

    override val values: immutable.IndexedSeq[BeamMode] = findValues

    // Driving / Automobile-like (hailed rides are a bit of a hybrid)

    case object CAR extends BeamMode(value = "car", Some(Left(LegMode.CAR)), TransportMode.car)

    case object CAV extends BeamMode(value = "cav", Some(Left(LegMode.CAR)), TransportMode.car)

    case object RIDE_HAIL extends BeamMode(value = "ride_hail", Some(Left(LegMode.CAR)), TransportMode.other)

    case object RIDE_HAIL_POOLED
        extends BeamMode(value = "ride_hail_pooled", Some(Left(LegMode.CAR)), TransportMode.other)

    // Transit

    case object BUS extends BeamMode(value = "bus", Some(Right(TransitModes.BUS)), TransportMode.pt)

    case object FUNICULAR extends BeamMode(value = "funicular", Some(Right(TransitModes.FUNICULAR)), TransportMode.pt)

    case object GONDOLA extends BeamMode(value = "gondola", Some(Right(TransitModes.GONDOLA)), TransportMode.pt)

    case object CABLE_CAR extends BeamMode(value = "cable_car", Some(Right(TransitModes.CABLE_CAR)), TransportMode.pt)

    case object FERRY extends BeamMode(value = "ferry", Some(Right(TransitModes.FERRY)), TransportMode.pt)

    case object TRANSIT extends BeamMode(value = "transit", Some(Right(TransitModes.TRANSIT)), TransportMode.pt)

    case object RAIL extends BeamMode(value = "rail", Some(Right(TransitModes.RAIL)), TransportMode.pt)

    case object SUBWAY extends BeamMode(value = "subway", Some(Right(TransitModes.SUBWAY)), TransportMode.pt)

    case object TRAM extends BeamMode(value = "tram", Some(Right(TransitModes.TRAM)), TransportMode.pt)

    // Non-motorized

    case object WALK extends BeamMode(value = "walk", Some(Left(LegMode.WALK)), TransportMode.walk)

    case object BIKE extends BeamMode(value = "bike", Some(Left(LegMode.BICYCLE)), TransportMode.bike)

    // Transit-specific
    case object WALK_TRANSIT
        extends BeamMode(
          value = "walk_transit",
          Some(Right(TransitModes.TRANSIT)),
          TransportMode.transit_walk
        )

    case object DRIVE_TRANSIT
        extends BeamMode(
          value = "drive_transit",
          Some(Right(TransitModes.TRANSIT)),
          TransportMode.pt
        )

    case object RIDE_HAIL_TRANSIT
        extends BeamMode(
          value = "ride_hail_transit",
          Some(Right(TransitModes.TRANSIT)),
          TransportMode.pt
        )

    case object BIKE_TRANSIT
        extends BeamMode(
          value = "bike_transit",
          Some(Right(TransitModes.TRANSIT)),
          TransportMode.other
        )

    val chainBasedModes = Seq(CAR, BIKE)

    val transitModes =
      Seq(BUS, FUNICULAR, GONDOLA, CABLE_CAR, FERRY, TRAM, TRANSIT, RAIL, SUBWAY)

    val massTransitModes: List[BeamMode] = List(FERRY, TRANSIT, RAIL, SUBWAY, TRAM)

    val allModes: Seq[BeamMode] =
      Seq(
        CAR,
        CAV,
        WALK,
        BIKE,
        TRANSIT,
        RIDE_HAIL,
        RIDE_HAIL_POOLED,
        RIDE_HAIL_TRANSIT,
        DRIVE_TRANSIT,
        WALK_TRANSIT,
        BIKE_TRANSIT
      )

    def fromString(stringMode: String): Option[BeamMode] = {
      if (stringMode.equals("")) {
        None
      } else if (stringMode.equalsIgnoreCase("drive")) {
        Some(CAR)
      } else {
        Some(BeamMode.withValue(stringMode))
      }
    }
  }

  def isChainBasedMode(beamMode: BeamMode): Boolean = BeamMode.chainBasedModes.contains(beamMode)

  implicit def beamMode2R5Mode(beamMode: BeamMode): Either[LegMode, TransitModes] =
    beamMode.r5Mode.get

  def isR5TransitMode(beamMode: BeamMode): Boolean = {
    beamMode.r5Mode match {
      case Some(Right(_)) =>
        true
      case _ => false
    }
  }

  def isR5LegMode(beamMode: BeamMode): Boolean = {
    beamMode.r5Mode match {
      case Some(Left(_)) =>
        true
      case _ => false
    }
  }

  def isOnStreetTransit(beamMode: BeamMode): Boolean = {
    beamMode.r5Mode match {
      case Some(Left(_)) =>
        false
      case Some(Right(transitMode)) =>
        transitMode match {
          case TransitModes.BUS =>
            true
          case _ =>
            false
        }
      case _ =>
        false
    }
  }

  def toR5StreetMode(mode: BeamMode): StreetMode = mode match {
    case BIKE => StreetMode.BICYCLE
    case WALK => StreetMode.WALK
    case CAR  => StreetMode.CAR
    case CAV  => StreetMode.CAR
    case _    => throw new IllegalArgumentException
  }

  def toR5StreetMode(mode: LegMode): StreetMode = mode match {
    case LegMode.BICYCLE | LegMode.BICYCLE_RENT => StreetMode.BICYCLE
    case LegMode.WALK                           => StreetMode.WALK
    case LegMode.CAR                            => StreetMode.CAR
    case _                                      => throw new IllegalArgumentException
  }

  def mapLegMode(mode: LegMode): BeamMode = mode match {
    case LegMode.BICYCLE | LegMode.BICYCLE_RENT => BeamMode.BIKE
    case LegMode.WALK                           => BeamMode.WALK
    case LegMode.CAR | LegMode.CAR_PARK         => BeamMode.CAR
  }

  def mapTransitMode(mode: TransitModes): BeamMode = mode match {
    case TransitModes.TRANSIT   => BeamMode.TRANSIT
    case TransitModes.SUBWAY    => BeamMode.SUBWAY
    case TransitModes.BUS       => BeamMode.BUS
    case TransitModes.FUNICULAR => BeamMode.FUNICULAR
    case TransitModes.GONDOLA   => BeamMode.GONDOLA
    case TransitModes.CABLE_CAR => BeamMode.CABLE_CAR
    case TransitModes.FERRY     => BeamMode.FERRY
    case TransitModes.RAIL      => BeamMode.RAIL
    case TransitModes.TRAM      => BeamMode.TRAM
    case _                      => throw new IllegalArgumentException
  }

}
