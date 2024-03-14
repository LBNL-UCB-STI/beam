package beam.router.skim

import beam.agentsim.agents.ridehail.RideHailVehicleId
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.skim.ActivitySimMetric._
import org.matsim.api.core.v01.population.Activity

sealed trait ActivitySimPathType

object ActivitySimPathType {

  private def determineCarPathTypeAndFleet(trip: EmbodiedBeamTrip): (ActivitySimPathType, Option[String]) = {
    //    HOV2,
    //    HOV3,
    //    SOV,
    //    HOV2TOLL,
    //    HOV3TOLL,
    //    SOVTOLL,

    // we can not get the number of passengers because right here we are processing the possible alternatives of trips
    // from origin to destination but not the real trips from origin to destination.
    trip.tripClassifier match {
      case BeamMode.RIDE_HAIL =>
        (TNC_SINGLE, Some(RideHailVehicleId(trip.legs.find(_.isRideHail).get.beamVehicleId).fleetId))
      case BeamMode.RIDE_HAIL_POOLED =>
        (TNC_SHARED, Some(RideHailVehicleId(trip.legs.find(_.isRideHail).get.beamVehicleId).fleetId))
      case _ => (SOV, None)
    }
  }

  private def determineDriveTransitPathType(trip: EmbodiedBeamTrip): ActivitySimPathType = {
    //    DRV_COM_WLK,
    //    DRV_HVY_WLK,
    //    DRV_LOC_WLK,
    //    DRV_LRF_WLK,
    //
    //    WLK_COM_DRV,
    //    WLK_HVY_DRV,
    //    WLK_LOC_DRV,
    //    WLK_LRF_DRV,
    //
    // so far not used:
    //    DRV_EXP_WLK,
    //    WLK_EXP_DRV,

    val (_, longestCarLegId) = tryGetLongestLegId(trip, isCar)
    val (longestWalkTransitLeg, longestWalkTransitLegId) = tryGetLongestLegId(trip, isWalkTransit)

    if (longestCarLegId.isEmpty || longestWalkTransitLeg.isEmpty || longestWalkTransitLegId.isEmpty) {
      OTHER
    } else if (longestCarLegId.get > longestWalkTransitLegId.get) {
      longestWalkTransitLeg.map(leg => leg.beamLeg.mode) match {
        case Some(BeamMode.FERRY) | Some(BeamMode.TRAM) | Some(BeamMode.CABLE_CAR) => WLK_LRF_DRV
        case Some(BeamMode.BUS)                                                    => WLK_LOC_DRV
        case Some(BeamMode.RAIL)                                                   => WLK_COM_DRV
        case Some(BeamMode.SUBWAY)                                                 => WLK_HVY_DRV
        case _                                                                     => OTHER
      }
    } else {
      longestWalkTransitLeg.map(leg => leg.beamLeg.mode) match {
        case Some(BeamMode.FERRY) | Some(BeamMode.TRAM) | Some(BeamMode.CABLE_CAR) => DRV_LRF_WLK
        case Some(BeamMode.BUS)                                                    => DRV_LOC_WLK
        case Some(BeamMode.RAIL)                                                   => DRV_COM_WLK
        case Some(BeamMode.SUBWAY)                                                 => DRV_HVY_WLK
        case _                                                                     => OTHER
      }
    }
  }

  private def determineTransitPathType(trip: EmbodiedBeamTrip): ActivitySimPathType = {
    //    WLK_COM_WLK, = commuter rail
    //    WLK_HVY_WLK, = heavy rail
    //    WLK_LOC_WLK, = local bus
    //    WLK_LRF_WLK, = light rail ferry

    // so far not used:
    //    WLK_EXP_WLK, = express bus
    //    WLK_TRN_WLK  = walk transit (general)

    val (longestWalkTransitLeg, _) = tryGetLongestLegId(trip, isWalkTransit)
    longestWalkTransitLeg.map(leg => leg.beamLeg.mode) match {
      case Some(BeamMode.FERRY) | Some(BeamMode.TRAM) | Some(BeamMode.CABLE_CAR) => WLK_LRF_WLK
      case Some(BeamMode.BUS)                                                    => WLK_LOC_WLK
      case Some(BeamMode.RAIL)                                                   => WLK_COM_WLK
      case Some(BeamMode.SUBWAY)                                                 => WLK_HVY_WLK
      case _                                                                     => OTHER
    }
  }

  def determineTripPathTypeAndFleet(trip: EmbodiedBeamTrip): (ActivitySimPathType, Option[String]) = {
    val allMods = trip.legs.map(_.beamLeg.mode).toSet
    val uniqueNotWalkingModes: Set[BeamMode] = allMods.filter { mode =>
      isCar(mode) || isWalkTransit(mode)
    }

    if (uniqueNotWalkingModes.exists(isCar)) {
      if (uniqueNotWalkingModes.exists(isWalkTransit)) {
        (determineDriveTransitPathType(trip), None)
      } else {
        determineCarPathTypeAndFleet(trip)
      }
    } else if (uniqueNotWalkingModes.exists(isWalkTransit)) {
      (determineTransitPathType(trip), None)
    } else if (allMods.contains(BeamMode.WALK) && allMods.size == 1) {
      (WALK, None)
    } else {
      (OTHER, None)
    }
  }

  def toBeamMode(pathType: ActivitySimPathType): BeamMode = {
    pathType match {
      case DRV_COM_WLK  => BeamMode.DRIVE_TRANSIT
      case DRV_EXP_WLK  => BeamMode.DRIVE_TRANSIT
      case DRV_HVY_WLK  => BeamMode.DRIVE_TRANSIT
      case DRV_LOC_WLK  => BeamMode.DRIVE_TRANSIT
      case DRV_LRF_WLK  => BeamMode.DRIVE_TRANSIT
      case WLK_COM_DRV  => BeamMode.DRIVE_TRANSIT
      case WLK_EXP_DRV  => BeamMode.DRIVE_TRANSIT
      case WLK_HVY_DRV  => BeamMode.DRIVE_TRANSIT
      case WLK_LOC_DRV  => BeamMode.DRIVE_TRANSIT
      case WLK_LRF_DRV  => BeamMode.DRIVE_TRANSIT
      case HOV2         => BeamMode.CAR
      case HOV2TOLL     => BeamMode.CAR
      case HOV3         => BeamMode.CAR
      case HOV3TOLL     => BeamMode.CAR
      case SOV          => BeamMode.CAR
      case SOVTOLL      => BeamMode.CAR
      case WLK_COM_WLK  => BeamMode.WALK_TRANSIT
      case WLK_EXP_WLK  => BeamMode.WALK_TRANSIT
      case WLK_HVY_WLK  => BeamMode.WALK_TRANSIT
      case WLK_LOC_WLK  => BeamMode.WALK_TRANSIT
      case WLK_LRF_WLK  => BeamMode.WALK_TRANSIT
      case WLK_TRN_WLK  => BeamMode.WALK_TRANSIT
      case WALK | OTHER => BeamMode.WALK
      case TNC_SINGLE   => BeamMode.RIDE_HAIL
      case TNC_SHARED   => BeamMode.RIDE_HAIL_POOLED
    }
  }

  def toKeyMode(pathType: ActivitySimPathType): Option[BeamMode] = {
    pathType match {
      case DRV_COM_WLK => Some(BeamMode.RAIL)
      case DRV_EXP_WLK => Some(BeamMode.BUS)
      case DRV_HVY_WLK => Some(BeamMode.SUBWAY)
      case DRV_LOC_WLK => Some(BeamMode.BUS)
      case DRV_LRF_WLK => Some(BeamMode.TRAM)
      case WLK_COM_DRV => Some(BeamMode.RAIL)
      case WLK_EXP_DRV => Some(BeamMode.BUS)
      case WLK_HVY_DRV => Some(BeamMode.SUBWAY)
      case WLK_LOC_DRV => Some(BeamMode.BUS)
      case WLK_LRF_DRV => Some(BeamMode.TRAM)
      case WLK_COM_WLK => Some(BeamMode.RAIL)
      case WLK_EXP_WLK => Some(BeamMode.BUS)
      case WLK_HVY_WLK => Some(BeamMode.SUBWAY)
      case WLK_LOC_WLK => Some(BeamMode.BUS)
      case WLK_LRF_WLK => Some(BeamMode.TRAM)
      case WLK_TRN_WLK => Some(BeamMode.RAIL)
      case _           => None
    }
  }

  def determineActivitySimPathTypesFromBeamMode(
    currentMode: Option[BeamMode],
    currentActivity: Option[Activity]
  ): Seq[ActivitySimPathType] = {
    val currentActivityType = currentActivity.map(_.getType.toLowerCase())
    currentMode match {
      case Some(BeamMode.WALK) => Seq(ActivitySimPathType.WALK)
      case Some(BeamMode.CAR)  =>
        // Note: Attempt to future-proof this in case there are some routes that can only be accomplished by HOVs.
        // The reverse shouldn't ever be the case, where a route cant be accomplished by HOVs
        Seq(
          ActivitySimPathType.SOV,
          ActivitySimPathType.SOVTOLL
        )
      case Some(BeamMode.CAR_HOV2) =>
        Seq(
          ActivitySimPathType.HOV2,
          ActivitySimPathType.HOV2TOLL,
          ActivitySimPathType.SOV,
          ActivitySimPathType.SOVTOLL
        )
      case Some(BeamMode.CAR_HOV3) =>
        Seq(
          ActivitySimPathType.HOV3,
          ActivitySimPathType.HOV3TOLL,
          ActivitySimPathType.HOV2,
          ActivitySimPathType.HOV2TOLL,
          ActivitySimPathType.SOV,
          ActivitySimPathType.SOVTOLL
        )
      case Some(BeamMode.WALK_TRANSIT) =>
        Seq(
          ActivitySimPathType.WLK_LOC_WLK,
          ActivitySimPathType.WLK_HVY_WLK,
          ActivitySimPathType.WLK_COM_WLK,
          ActivitySimPathType.WLK_LRF_WLK,
          ActivitySimPathType.WLK_EXP_WLK,
          ActivitySimPathType.WLK_TRN_WLK
        )
      case Some(BeamMode.DRIVE_TRANSIT) =>
        currentActivityType match {
          case Some("home") =>
            Seq(
              ActivitySimPathType.DRV_LOC_WLK,
              ActivitySimPathType.DRV_HVY_WLK,
              ActivitySimPathType.DRV_COM_WLK,
              ActivitySimPathType.DRV_LRF_WLK,
              ActivitySimPathType.DRV_EXP_WLK
            )
          case _ =>
            Seq(
              ActivitySimPathType.WLK_LOC_DRV,
              ActivitySimPathType.WLK_HVY_DRV,
              ActivitySimPathType.WLK_COM_DRV,
              ActivitySimPathType.WLK_LRF_DRV,
              ActivitySimPathType.WLK_EXP_DRV
            )
        }
      case Some(BeamMode.RIDE_HAIL) =>
        Seq(ActivitySimPathType.TNC_SINGLE)
      case Some(BeamMode.RIDE_HAIL_POOLED) =>
        Seq(ActivitySimPathType.TNC_SHARED)
      case _ =>
        Seq.empty[ActivitySimPathType]
    }
  }

  val walkTransitPathTypes: Seq[ActivitySimPathType] = Seq(
    WLK_COM_WLK,
    WLK_HVY_WLK,
    WLK_EXP_WLK,
    WLK_LOC_WLK,
    WLK_LRF_WLK
  )

  val allPathTypes: Seq[ActivitySimPathType] = Seq(
    DRV_COM_WLK,
    DRV_HVY_WLK,
    DRV_LOC_WLK,
    DRV_LRF_WLK,
    // ignored because we do not have passengers count or paid roads yet
    //    HOV2,
    //    HOV2TOLL,
    //    HOV3,
    //    HOV3TOLL,
    //    SOVTOLL,
    SOV,
    WLK_COM_DRV,
    WLK_COM_WLK,
    // ignored because we can not distinguish local buses from express buses yet
    //    DRV_EXP_WLK,
    //    WLK_EXP_DRV,
    //    WLK_EXP_WLK,
    WLK_HVY_DRV,
    WLK_HVY_WLK,
    WLK_LOC_DRV,
    WLK_LOC_WLK,
    WLK_LRF_DRV,
    WLK_LRF_WLK,
    // UPDATE: TRN is a catch-all for all walk-transit trips
    WLK_TRN_WLK,
    WALK
  )

  def isWalkTransit(pathType: ActivitySimPathType): Boolean = walkTransitPathTypes.contains(pathType)

  val allPathTypesMap: Map[String, ActivitySimPathType] =
    allPathTypes.map(x => x.toString -> x).toMap

  def fromString(str: String): Option[ActivitySimPathType] = allPathTypesMap.get(str)

  private def isWalkTransit(beamMode: BeamMode): Boolean = beamMode match {
    case BeamMode.BUS | BeamMode.FERRY | BeamMode.RAIL | BeamMode.SUBWAY | BeamMode.TRAM | BeamMode.CABLE_CAR => true

    case _ => false
  }

  private def isCar(beamMode: BeamMode): Boolean = beamMode match {
    case BeamMode.CAR | BeamMode.CAV => true
    case _                           => false
  }

  private def tryGetLongestLegId(
    trip: EmbodiedBeamTrip,
    isModeToCheck: BeamMode => Boolean
  ): (Option[EmbodiedBeamLeg], Option[Int]) = {
    var longestLeg: Option[EmbodiedBeamLeg] = Option.empty[EmbodiedBeamLeg]
    var longestLegId: Option[Int] = Option.empty[Int]

    var currentId = 0
    trip.legs.foreach { leg =>
      if (isModeToCheck(leg.beamLeg.mode)) {
        longestLeg match {
          case None =>
            longestLeg = Some(leg)
            longestLegId = Some(currentId)
          case Some(longleg) if longleg.beamLeg.duration < leg.beamLeg.duration =>
            longestLeg = Some(leg)
            longestLegId = Some(currentId)
          case _ =>
        }
      }
      currentId += 1
    }

    (longestLeg, longestLegId)
  }

  case object DRV_COM_WLK extends ActivitySimPathType
  case object DRV_EXP_WLK extends ActivitySimPathType
  case object DRV_HVY_WLK extends ActivitySimPathType
  case object DRV_LOC_WLK extends ActivitySimPathType
  case object DRV_LRF_WLK extends ActivitySimPathType
  case object HOV2 extends ActivitySimPathType
  case object HOV2TOLL extends ActivitySimPathType
  case object HOV3 extends ActivitySimPathType
  case object HOV3TOLL extends ActivitySimPathType
  case object SOV extends ActivitySimPathType
  case object SOVTOLL extends ActivitySimPathType
  case object TNC_SINGLE extends ActivitySimPathType
  case object TNC_SHARED extends ActivitySimPathType
  case object WLK_COM_DRV extends ActivitySimPathType
  case object WLK_COM_WLK extends ActivitySimPathType
  case object WLK_EXP_DRV extends ActivitySimPathType
  case object WLK_EXP_WLK extends ActivitySimPathType
  case object WLK_HVY_DRV extends ActivitySimPathType
  case object WLK_HVY_WLK extends ActivitySimPathType
  case object WLK_LOC_DRV extends ActivitySimPathType
  case object WLK_LOC_WLK extends ActivitySimPathType
  case object WLK_LRF_DRV extends ActivitySimPathType
  case object WLK_LRF_WLK extends ActivitySimPathType
  case object WLK_TRN_WLK extends ActivitySimPathType
  case object WALK extends ActivitySimPathType

  case object OTHER extends ActivitySimPathType
}
