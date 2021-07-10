package beam.router.skim

import beam.router.Modes.BeamMode
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}

sealed trait ActivitySimPathType

object ActivitySimPathType {

  private def determineCarPathType(trip: EmbodiedBeamTrip): ActivitySimPathType = {
    //    HOV2,
    //    HOV3,
    //    SOV,
    //    HOV2TOLL,
    //    HOV3TOLL,
    //    SOVTOLL,

    // we can not get the number of passengers because right here we are processing the possible alternatives of trips
    // from origin to destination but not the real trips from origin to destination.
    SOV
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
    //    WLK_TRN_WLK  = train ??

    val (longestWalkTransitLeg, _) = tryGetLongestLegId(trip, isWalkTransit)
    longestWalkTransitLeg.map(leg => leg.beamLeg.mode) match {
      case Some(BeamMode.FERRY) | Some(BeamMode.TRAM) | Some(BeamMode.CABLE_CAR) => WLK_LRF_WLK
      case Some(BeamMode.BUS)                                                    => WLK_LOC_WLK
      case Some(BeamMode.RAIL)                                                   => WLK_COM_WLK
      case Some(BeamMode.SUBWAY)                                                 => WLK_HVY_WLK
      case _                                                                     => OTHER
    }
  }

  def determineTripPathType(trip: EmbodiedBeamTrip): ActivitySimPathType = {
    val allMods = trip.legs.map(_.beamLeg.mode).toSet
    val uniqueNotWalkingModes: Set[BeamMode] = allMods.filter { mode =>
      isCar(mode) || isWalkTransit(mode)
    }

    if (uniqueNotWalkingModes.exists(isCar)) {
      if (uniqueNotWalkingModes.exists(isWalkTransit)) {
        determineDriveTransitPathType(trip)
      } else {
        determineCarPathType(trip)
      }
    } else if (uniqueNotWalkingModes.exists(isWalkTransit)) {
      determineTransitPathType(trip)
    } else if (allMods.contains(BeamMode.WALK) && allMods.size == 1) {
      WALK
    } else {
      OTHER
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
    }
  }

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
    // ignored because we did not understand what kind of vehicles are TRN yet
    //    WLK_TRN_WLK
    WALK,
  )

  private def isWalkTransit(beamMode: BeamMode): Boolean = beamMode match {
    case BeamMode.BUS | BeamMode.FERRY | BeamMode.RAIL | BeamMode.SUBWAY | BeamMode.TRAM | BeamMode.CABLE_CAR => true

    case BeamMode.CAR | BeamMode.CAV | BeamMode.RIDE_HAIL | BeamMode.RIDE_HAIL_POOLED                   => false
    case BeamMode.FUNICULAR | BeamMode.GONDOLA | BeamMode.WALK | BeamMode.BIKE | BeamMode.BIKE_TRANSIT  => false
    case BeamMode.TRANSIT | BeamMode.WALK_TRANSIT | BeamMode.DRIVE_TRANSIT | BeamMode.RIDE_HAIL_TRANSIT => false
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
