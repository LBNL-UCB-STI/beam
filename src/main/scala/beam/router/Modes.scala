package beam.router

import beam.agentsim.agents.modalbehaviors.DrivesVehicle.VehicleOrToken
import beam.agentsim.agents.vehicles.VehicleCategory._
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

    import BeamMode._

    override def equals(obj: Any): Boolean = obj match {
      case mode: BeamMode if BeamMode.isCar(mode.value) => BeamMode.isCar(this.value)
      case _                                            => super.equals(obj)
    }

    def isTransit: Boolean = isR5TransitMode(this)
    def isMassTransit: Boolean = this == SUBWAY || this == RAIL || this == FERRY || this == TRAM
    def isRideHail: Boolean = this == RIDE_HAIL
    def isHovTeleportation: Boolean = this == HOV2_TELEPORTATION || this == HOV3_TELEPORTATION
    def isTeleportation: Boolean = this == HOV2_TELEPORTATION || this == HOV3_TELEPORTATION
  }

  object BeamMode extends StringEnum[BeamMode] with StringCirceEnum[BeamMode] {

    def isCar(stringMode: String): Boolean =
      stringMode.equalsIgnoreCase(CAR.value) ||
      stringMode.equalsIgnoreCase(CAR_HOV2.value) ||
      stringMode.equalsIgnoreCase(CAR_HOV3.value)

    override val values: immutable.IndexedSeq[BeamMode] = findValues

    case object HOV2_TELEPORTATION extends BeamMode(value = "hov2_teleportation", None, "")

    case object HOV3_TELEPORTATION extends BeamMode(value = "hov3_teleportation", None, "")

    // Driving / Automobile-like (hailed rides are a bit of a hybrid)

    case object CAR extends BeamMode(value = "car", Some(Left(LegMode.CAR)), TransportMode.car)

    // car with 1 guaranteed additional passenger
    case object CAR_HOV2 extends BeamMode(value = "car_hov2", Some(Left(LegMode.CAR)), TransportMode.car)

    // car with 2 guaranteed additional passengers
    case object CAR_HOV3 extends BeamMode(value = "car_hov3", Some(Left(LegMode.CAR)), TransportMode.car)

    case object CAV extends BeamMode(value = "cav", Some(Left(LegMode.CAR)), TransportMode.car)

    case object RIDE_HAIL extends BeamMode(value = "ride_hail", Some(Left(LegMode.CAR)), TransportMode.other)

    case object RIDE_HAIL_POOLED
        extends BeamMode(value = "ride_hail_pooled", Some(Left(LegMode.CAR)), TransportMode.other)

    case object FREIGHT extends BeamMode(value = "freight", Some(Left(LegMode.CAR)), TransportMode.truck)
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
          TransportMode.pt
        )

    val chainBasedModes = Seq(CAR, BIKE)

    val personalVehicleModes = Seq(CAR, BIKE, DRIVE_TRANSIT, BIKE_TRANSIT)

    val nonPersonalVehicleModes = Seq(WALK, RIDE_HAIL, RIDE_HAIL_POOLED, RIDE_HAIL_TRANSIT, WALK_TRANSIT)

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
        BIKE_TRANSIT,
        HOV2_TELEPORTATION,
        HOV3_TELEPORTATION
      )

    def fromString(stringMode: String): Option[BeamMode] = {
      if (stringMode.equals("") || stringMode.equals("other")) {
        None
      } else if (stringMode.equalsIgnoreCase("drive")) {
        Some(CAR)
      } else if (stringMode.equalsIgnoreCase("transit_walk")) {
        Some(WALK_TRANSIT)
      } else {
        Some(BeamMode.withValue(stringMode))
      }
    }
  }

  def isChainBasedMode(beamMode: BeamMode): Boolean = BeamMode.chainBasedModes.contains(beamMode)

  def isPersonalVehicleMode(beamMode: BeamMode): Boolean = BeamMode.personalVehicleModes.contains(beamMode)

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
    case BeamMode.BIKE => StreetMode.BICYCLE
    case BeamMode.WALK => StreetMode.WALK
    case BeamMode.CAR  => StreetMode.CAR
    case BeamMode.CAV  => StreetMode.CAR
    case _             => throw new IllegalArgumentException
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

  def getAccessVehicleMode(mode: BeamMode): BeamMode = mode match {
    case BeamMode.TRANSIT           => throw new IllegalArgumentException("access vehicle is unknown")
    case BeamMode.WALK_TRANSIT      => BeamMode.WALK
    case BeamMode.DRIVE_TRANSIT     => BeamMode.CAR
    case BeamMode.RIDE_HAIL_TRANSIT => BeamMode.CAR
    case BeamMode.BIKE_TRANSIT      => BeamMode.BIKE
    case _                          => throw new IllegalArgumentException("not a transit mode: " + mode.value)
  }

}

object TourModes {
  import beam.router.Modes.BeamMode
  import beam.router.Modes.BeamMode._

  sealed abstract class BeamTourMode(
    val value: String,
    val vehicleCategory: VehicleCategory,
    val allowedBeamModes: Seq[BeamMode],
    val allowedBeamModesForFirstAndLastLeg: Seq[BeamMode]
  ) extends StringEnumEntry {

    import BeamTourMode._

    def allowedBeamModesGivenAvailableVehicles(
      vehicles: Vector[VehicleOrToken],
      firstOrLastLeg: Boolean
    ): Seq[BeamMode] = {
      val relevantModes = if (firstOrLastLeg) { allowedBeamModesForFirstAndLastLeg }
      else allowedBeamModes
      if (
        vehicles
          .exists(vehOrToken => !vehOrToken.vehicle.isSharedVehicle && vehOrToken.streetVehicle.mode.in(relevantModes))
      ) { relevantModes }
      else { Seq.empty[BeamMode] }
    }

    def isVehicleBased: Boolean = this match {
      case WALK_BASED => false
      case _          => true
    }
  }

  object BeamTourMode extends StringEnum[BeamTourMode] with StringCirceEnum[BeamTourMode] {

    override val values: immutable.IndexedSeq[BeamTourMode] = findValues

    val enabledModes: Map[BeamMode, Seq[BeamMode]] =
      Map[BeamMode, Seq[BeamMode]](CAR -> Seq(DRIVE_TRANSIT), BIKE -> Seq(BIKE_TRANSIT))

    // TODO: Also allow use of shared bikes/cars in walk based tours
    case object WALK_BASED
        extends BeamTourMode(
          "walk_based",
          Body,
          Seq[BeamMode](
            WALK,
            WALK_TRANSIT,
            RIDE_HAIL,
            RIDE_HAIL_POOLED,
            RIDE_HAIL_TRANSIT,
            HOV2_TELEPORTATION,
            HOV3_TELEPORTATION
          ),
          Seq[BeamMode](
            WALK,
            WALK_TRANSIT,
            RIDE_HAIL,
            RIDE_HAIL_POOLED,
            RIDE_HAIL_TRANSIT,
            DRIVE_TRANSIT,
            BIKE_TRANSIT,
            HOV2_TELEPORTATION,
            HOV3_TELEPORTATION
          )
        ) {

      // When we're on a walk based tour, we can still use shared vehicles
      override def allowedBeamModesGivenAvailableVehicles(
        vehicles: Vector[VehicleOrToken],
        firstOrLastLeg: Boolean
      ): Seq[BeamMode] = {
        vehicles.flatMap { veh =>
          if (veh.vehicle.isSharedVehicle) { Seq(veh.streetVehicle.mode) ++ enabledModes(veh.streetVehicle.mode) }
          else { Seq.empty[BeamMode] }
        } ++ allowedBeamModes
      }
    }

    case object CAR_BASED
        extends BeamTourMode(
          "car_based",
          Car,
          Seq[BeamMode](CAR, CAR_HOV2, CAR_HOV3),
          Seq[BeamMode](CAR, CAR_HOV2, CAR_HOV3)
        )

    case object BIKE_BASED extends BeamTourMode("bike_based", Bike, Seq[BeamMode](BIKE), Seq[BeamMode](BIKE))

  }

}
