package beam.router.skim

import beam.router.Modes.BeamMode
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.skim.ActivitySimMetric._
import org.matsim.api.core.v01.population.Activity

sealed trait ActivitySimPathType

object ActivitySimPathType {

  private def determineCarPathType(): ActivitySimPathType = {
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
    val (longestWalkTransitLeg, longestWalkTransitLegId) = tryGetLongestLegId(trip, isTransit)

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

  private def determineWalkTransitPathType(trip: EmbodiedBeamTrip): ActivitySimPathType = {
    //    WLK_COM_WLK, = commuter rail
    //    WLK_HVY_WLK, = heavy rail
    //    WLK_LOC_WLK, = local bus
    //    WLK_LRF_WLK, = light rail ferry

    // so far not used:
    //    WLK_EXP_WLK, = express bus
    //    WLK_TRN_WLK  = walk transit (general)

    val (longestWalkTransitLeg, _) = tryGetLongestLegId(trip, isTransit)
    longestWalkTransitLeg.map(leg => leg.beamLeg.mode) match {
      case Some(BeamMode.FERRY) | Some(BeamMode.TRAM) | Some(BeamMode.CABLE_CAR) => WLK_LRF_WLK
      case Some(BeamMode.BUS)                                                    => WLK_LOC_WLK
      case Some(BeamMode.RAIL)                                                   => WLK_COM_WLK
      case Some(BeamMode.SUBWAY)                                                 => WLK_HVY_WLK
      case _                                                                     => OTHER
    }
  }

  private def determineBikeTransitPathType(trip: EmbodiedBeamTrip): ActivitySimPathType = {
    // Right now we don't have bike transit in activitysim, so just return OTHER so it doesn't mess up walk transit skims
    trip.tripClassifier match {
      case _ => OTHER
    }
  }

  def determineTripPathType(trip: EmbodiedBeamTrip): ActivitySimPathType = {
    val allModes = trip.legs.map(_.beamLeg.mode).toSet
    val uniqueNotWalkingModes: Set[BeamMode] = allModes.filter { mode =>
      isCar(mode) || isTransit(mode)
    }

    if (uniqueNotWalkingModes.exists(isCar)) {
      if (uniqueNotWalkingModes.exists(isTransit)) {
        determineDriveTransitPathType(trip)
      } else {
        determineCarPathType()
      }
    } else if (uniqueNotWalkingModes.exists(isTransit)) {
      if (uniqueNotWalkingModes.contains(BeamMode.BIKE)) { determineBikeTransitPathType(trip) }
      else { determineWalkTransitPathType(trip) }
    } else if (
      allModes.contains(BeamMode.BIKE) && allModes.forall(m => List(BeamMode.BIKE, BeamMode.WALK).contains(m))
    ) {
      BIKE
    } else if (allModes.contains(BeamMode.WALK) && allModes.size == 1) {
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
      case BIKE         => BeamMode.BIKE
      case WALK | OTHER => BeamMode.WALK
    }
  }

  def toKeyMode(pathType: ActivitySimPathType): Set[BeamMode] = {
    pathType match {
      case DRV_COM_WLK => Set(BeamMode.RAIL)
      case DRV_EXP_WLK => Set(BeamMode.BUS)
      case DRV_HVY_WLK => Set(BeamMode.SUBWAY)
      case DRV_LOC_WLK => Set(BeamMode.BUS)
      case DRV_LRF_WLK => Set(BeamMode.TRAM, BeamMode.CABLE_CAR)
      case WLK_COM_DRV => Set(BeamMode.RAIL)
      case WLK_EXP_DRV => Set(BeamMode.BUS)
      case WLK_HVY_DRV => Set(BeamMode.SUBWAY)
      case WLK_LOC_DRV => Set(BeamMode.BUS)
      case WLK_LRF_DRV => Set(BeamMode.TRAM, BeamMode.CABLE_CAR)
      case WLK_COM_WLK => Set(BeamMode.RAIL)
      case WLK_EXP_WLK => Set(BeamMode.BUS)
      case WLK_HVY_WLK => Set(BeamMode.SUBWAY)
      case WLK_LOC_WLK => Set(BeamMode.BUS)
      case WLK_LRF_WLK => Set(BeamMode.TRAM, BeamMode.CABLE_CAR)
      case WLK_TRN_WLK => Set.empty[BeamMode]
      case _           => Set.empty[BeamMode]
    }
  }

  def determineActivitySimPathTypesFromBeamMode(
    currentMode: Option[BeamMode],
    currentActivity: Activity
  ): Seq[ActivitySimPathType] = {
    val currentActivityType = currentActivity.getType.toLowerCase()
    currentMode match {
      case Some(BeamMode.WALK) => Seq(ActivitySimPathType.WALK)
      case Some(BeamMode.BIKE) => Seq(ActivitySimPathType.BIKE)
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
          case "home" =>
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
      case _ =>
        Seq.empty[ActivitySimPathType]
    }
  }

  val walkTransitPathTypes: Seq[ActivitySimPathType] = Seq(
    WLK_COM_WLK,
    WLK_HVY_WLK,
    WLK_EXP_WLK,
    WLK_LOC_WLK,
    WLK_LRF_WLK,
    WLK_LOC_DRV,
    WLK_HVY_DRV,
    WLK_COM_DRV,
    WLK_LRF_DRV,
    WLK_EXP_DRV
  )

  val transitPathTypes: Seq[ActivitySimPathType] = Seq(
    WLK_COM_WLK,
    WLK_HVY_WLK,
    WLK_EXP_WLK,
    WLK_LOC_WLK,
    WLK_LRF_WLK,
    DRV_COM_WLK,
    DRV_HVY_WLK,
    DRV_LOC_WLK,
    DRV_LRF_WLK,
    DRV_LOC_WLK,
    DRV_HVY_WLK,
    DRV_COM_WLK,
    DRV_LRF_WLK,
    DRV_EXP_WLK
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
    WALK,
    BIKE
  )

  def isWalkTransit(pathType: ActivitySimPathType): Boolean = walkTransitPathTypes.contains(pathType)

  def isTransit(pathType: ActivitySimPathType): Boolean = transitPathTypes.contains(pathType)

  val allPathTypesMap: Map[String, ActivitySimPathType] =
    allPathTypes.map(x => x.toString -> x).toMap

  def fromString(str: String): Option[ActivitySimPathType] = allPathTypesMap.get(str)

  private def isTransit(beamMode: BeamMode): Boolean = beamMode match {
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

  case object BIKE extends ActivitySimPathType

  case object OTHER extends ActivitySimPathType
}
