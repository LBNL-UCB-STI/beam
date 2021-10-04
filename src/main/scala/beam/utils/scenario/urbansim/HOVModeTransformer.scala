package beam.utils.scenario.urbansim

import beam.router.Modes.BeamMode._
import beam.utils.scenario._
import beam.utils.scenario.urbansim.HOVModeTransformer.ForcedCarHOVTransformer.{
  isForcedCarHOVTrip,
  mapToForcedCarHOVTrip
}
import beam.utils.scenario.urbansim.HOVModeTransformer.ForcedHOVTeleportationTransformer.{
  isForcedHOVTeleportationTrip,
  mapToForcedHOVTeleportation
}
import beam.utils.scenario.urbansim.HOVModeTransformer.RandomCarHOVTransformer.mapRandomHOVTeleportationOrCar
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable
import scala.util.Random

/**
  * ActivitySim HOV modes transformer to their Beam representation.
  *
  * ActivitySim provide modes wider than beam modes, like `HOV2` and `HOV3`. <br>
  * `HOV2` - Ride a car with 2 persons in it. <br>
  * `HOV3` - Ride a car with 3 persons in it. <br>
  * However it is unknown who is a driver and who is passenger.
  * The transformation is done in several steps: <br>
  * 1) If it is impossible for a person to be a driver [[HOV2_TELEPORTATION]]/[[HOV3_TELEPORTATION]] assigned. <br>
  * 2) If seems that it is impossible for a person to be a passenger [[CAR_HOV2]]/[[CAR_HOV3]] assigned. <br>
  * 3) For all others the random choice is used.
  * The driver person is chosen randomly with 50% chance for `HOV2` and 33% for `HOV3`.<br>
  * The mode for the driver is replaced from `HOV2` to [[CAR_HOV2]] and respectively to [[CAR_HOV3]] for `HOV3`.<br>
  * The same action is done for passengers for which mode is chosen [[HOV2_TELEPORTATION]] or [[HOV3_TELEPORTATION]].
  * If the person doesnt have an available car from the household, only [[HOV2_TELEPORTATION]] or [[HOV3_TELEPORTATION]]
  * will be assigned, not [[CAR_HOV2]]/[[CAR_HOV3]].
  */
object HOVModeTransformer extends LazyLogging {

  def reseedRandomGenerator(randomSeed: Int): Unit = rand.setSeed(randomSeed)

  private implicit val rand: Random = new Random(42)
  private val hov2: String = "hov2" // HOV2
  private val hov3: String = "hov3" // HOV3

  private def isHOV2(mode: String): Boolean = mode.toLowerCase() match {
    case `hov2` => true
    case _      => false
  }

  private def isHOV3(mode: String): Boolean = mode.toLowerCase() match {
    case `hov3` => true
    case _      => false
  }

  private val allCarModes = Seq(CAR, CAV, CAR_HOV2, CAR_HOV3).map(_.value.toLowerCase)
  private val allHOVModes = Set(hov2, hov3)

  private val allCarModesWithHOV: Set[String] =
    (allCarModes ++ allHOVModes).toSet

  def transformHOVtoHOVCARorHOVTeleportation(
    plansProbablyWithHOV: Iterable[PlanElement]
  ): Iterable[PlanElement] = {
    val allHOVUsers: Set[PersonId] = plansProbablyWithHOV
      .filter(planElement => {
        val legMode = planElement.legMode.map(_.toLowerCase)
        legMode.contains(hov2) || legMode.contains(hov3)
      })
      .map(_.personId)
      .toSet

    var forcedHOV2Teleports = 0
    var forcedHOV3Teleports = 0

    if (forcedHOV2Teleports > 0 || forcedHOV3Teleports > 0) {
      logger.info(
        s"There were $forcedHOV2Teleports hov2 and $forcedHOV3Teleports hov3 forced teleports because actors did not get access to a car."
      )
    }

    var forcedCarHOV2Count = 0
    var forcedCarHOV3Count = 0

    def thereAreMoreHOVTeleportations: Boolean = {
      forcedHOV2Teleports > forcedCarHOV2Count ||
      forcedHOV3Teleports > forcedCarHOV3Count * 2
    }

    def thereAreMoreHOVCars: Boolean = {
      forcedCarHOV2Count > forcedHOV2Teleports ||
      forcedCarHOV3Count * 2 > forcedHOV3Teleports
    }

    def replaceHOVwithCar(trip: List[PlanElement]): List[PlanElement] = {
      trip.map {
        case hov2Leg if itIsAnHOV2Leg(hov2Leg) =>
          forcedHOV2Teleports -= 1
          hov2Leg.copy(legMode = Some(CAR_HOV2.value))
        case hov3Leg if itIsAnHOV3Leg(hov3Leg) =>
          //as car_hov3 contains two passengers, reduce by 2
          forcedHOV3Teleports -= 2
          hov3Leg.copy(legMode = Some(CAR_HOV3.value))
        case other => other
      }
    }

    def replaceHOVwithTeleportation(trip: List[PlanElement]): List[PlanElement] = {
      trip.map {
        case hov2Leg if itIsAnHOV2Leg(hov2Leg) =>
          forcedCarHOV2Count -= 1
          hov2Leg.copy(legMode = Some(HOV2_TELEPORTATION.value))
        case hov3Leg if itIsAnHOV3Leg(hov3Leg) =>
          forcedCarHOV3Count -= 1
          hov3Leg.copy(legMode = Some(HOV3_TELEPORTATION.value))
        case other => other
      }
    }

    val tripsTransformed: Iterable[List[PlanElement]] = splitToTrips(plansProbablyWithHOV)
      .map { trip =>
        if (allHOVUsers.contains(trip.head.personId)) {
          if (isForcedHOVTeleportationTrip(trip)) {
            val (mappedTrip, forcedHOV2, forcedHOV3) = mapToForcedHOVTeleportation(trip)
            forcedHOV2Teleports += forcedHOV2
            forcedHOV3Teleports += forcedHOV3
            mappedTrip
          } else if (isForcedCarHOVTrip(trip)) {
            val (mappedTrip, forcedHOV2, forcedHOV3) = mapToForcedCarHOVTrip(trip)
            forcedCarHOV2Count += forcedHOV2
            forcedCarHOV3Count += forcedHOV3
            mappedTrip
          } else if (thereAreMoreHOVTeleportations) {
            replaceHOVwithCar(trip)
          } else if (thereAreMoreHOVCars) {
            replaceHOVwithTeleportation(trip)
          } else {
            mapRandomHOVTeleportationOrCar(trip)
          }
        } else {
          trip
        }
      }
    // we need to merge plans without creating duplicates of home activity for persons with more than one trip
    val plans = joinTripsIntoPlans(tripsTransformed)
    plans
  }

  def joinTripsIntoPlans(tripsTransformed: Iterable[List[PlanElement]]): Iterable[PlanElement] = {
    val personToLastActivity = mutable.HashMap.empty[PersonId, PlanElement]
    val plans = tripsTransformed.map { trip =>
      val personId = trip.head.personId
      val tripToAdd = personToLastActivity.get(personId) match {
        // we need to remove the first activity because it is a duplicate
        // that was added while plans were split to trips
        case Some(lastSeenActivity) if lastSeenActivity == trip.head => trip.tail
        case _                                                       => trip
      }
      personToLastActivity(personId) = trip.last
      tripToAdd
    }

    plans.flatten
  }

  /**
    * Splits provided plans to trips. Each sub collection is a separate trip for separate person.
    */
  def splitToTrips(planElements: Iterable[PlanElement]): Iterable[List[PlanElement]] = {
    val trips = mutable.ListBuffer.empty[List[PlanElement]]
    val personToTrip = mutable.HashMap.empty[PersonId, mutable.ListBuffer[PlanElement]]
    val homeActivity = "home"
    val plansByPerson = mutable.LinkedHashMap.empty[PersonId, mutable.ListBuffer[PlanElement]]

    planElements.foreach { plan =>
      plansByPerson.get(plan.personId) match {
        case Some(plans) => plans.append(plan)
        case None        => plansByPerson(plan.personId) = mutable.ListBuffer(plan)
      }
    }

    def isHomeActivity(activity: PlanElement): Boolean = {
      activity.activityType.map(_.toLowerCase).contains(homeActivity)
    }

    def canBeSplitToTrips(plans: Iterable[PlanElement]): Boolean = {
      isHomeActivity(plans.head) && isHomeActivity(plans.last)
    }

    def addLeg(leg: PlanElement): Unit = personToTrip.get(leg.personId) match {
      case Some(trip) => trip.append(leg)
      case None       =>
        //not possible if there are no bugs, as before splitting plans are checked if it is possible
        throw new RuntimeException(
          s"Trip should be started from activity. Can't append leg to the trip, missing trip for person ${leg.personId}"
        )
    }

    def addActivity(activity: PlanElement): Unit = personToTrip.get(activity.personId) match {
      case Some(trip) if isHomeActivity(activity) && trip.size > 1 =>
        trips.append(trip.toList :+ activity)
        personToTrip.remove(activity.personId)

        // we should start a new trip in case a person will continue, if not we will drop this orphaned home activity later
        // this also creates a home activity duplicates later when we join trips back together
        // i.e.
        // original plans: home - leg - work - leg - home - leg - other - leg - home
        // splitted trip1: home - leg - work - leg - home
        //          trip2: home - leg - other - leg - home
        personToTrip(activity.personId) = mutable.ListBuffer(activity)

      case Some(trip) if isHomeActivity(activity) && trip.size == 1 =>
        // replace home activity
        personToTrip(activity.personId) = mutable.ListBuffer(activity)

      case Some(trip) => trip.append(activity)

      case None => personToTrip(activity.personId) = mutable.ListBuffer(activity)
    }

    val cantSplitTripsForPersons = mutable.ListBuffer.empty[PersonId]
    plansByPerson.values.foreach { plans =>
      if (canBeSplitToTrips(plans)) {
        plans.foreach { planElement =>
          planElement.planElementType match {
            case PlanElement.Activity => addActivity(planElement)
            case PlanElement.Leg      => addLeg(planElement)
          }
        }
      } else {
        cantSplitTripsForPersons.append(plans.head.personId)
        trips.append(plans.toList)
      }
    }

    if (cantSplitTripsForPersons.nonEmpty) {
      logger.info(
        "Cannot split plans to trips because plans does not start and end by Home activity for {} persons: {}",
        cantSplitTripsForPersons.size,
        cantSplitTripsForPersons.mkString(",")
      )
    }

    // remove orphaned home locations
    personToTrip.retain((_, trip) => trip.size > 1)

    if (personToTrip.nonEmpty) {
      val cnt = personToTrip.size
      val persons = personToTrip.keySet.mkString(",")
      logger.warn(
        s"There are $cnt trips which did not end with Home activity. Affected persons: $persons"
      )
    }

    trips ++ personToTrip.values.map(_.toList)
  }

  def itIsAnHOV2Leg(planElement: PlanElement): Boolean = {
    planElement.planElementType == PlanElement.Leg &&
    planElement.legMode.exists(legMode => legMode.toLowerCase == hov2)
  }

  def itIsAnHOV3Leg(planElement: PlanElement): Boolean = {
    planElement.planElementType == PlanElement.Leg &&
    planElement.legMode.exists(legMode => legMode.toLowerCase == hov3)
  }

  object ForcedHOVTeleportationTransformer {

    def isForcedHOVTeleportationTrip(trip: Iterable[PlanElement]): Boolean = {
      val maybeTripCarInfo = (trip.head.activityLocationX, trip.head.activityLocationY) match {
        case (Some(locationX), Some(locationY)) => Some(TripCarInfo(locationX, locationY, carIsNearby = true))
        case _                                  => None
      }
      maybeTripCarInfo match {
        case None =>
          throw new RuntimeException(
            s"Trip can start only from activity that contains location. person: ${trip.headOption.map(_.personId)}"
          )

        case Some(initialTripCarInfo) =>
          val collectedTripCarInfo = trip.foldLeft(initialTripCarInfo) {
            // if car was lost once, there is no need to analyze anymore
            case (tripCarInfo, _) if tripCarInfo.carWasLost => tripCarInfo

            // if this is an activity and car was driven, then move the car to the new location
            case (tripCarInfo, planElement)
                if planElement.planElementType == PlanElement.Activity && tripCarInfo.carWasUsed =>
              tripCarInfo.withNewActivityLocation(planElement)

            // if this is an activity and car was not driven, check if car is nearby right now
            case (tripCarInfo, planElement) if planElement.planElementType == PlanElement.Activity =>
              tripCarInfo.withInformationIfCarIsNearby(planElement)

            // if this is a leg then check what happens with the car
            case (tripCarInfo, planElement) if planElement.planElementType == PlanElement.Leg =>
              val isCARmode = planElement.legMode.exists(mode => allCarModesWithHOV.contains(mode.toLowerCase))
              if (isCARmode && tripCarInfo.carIsNearby) {
                tripCarInfo.copy(carWasUsed = true)
              } else if (isCARmode) {
                tripCarInfo.copy(carWasLost = true)
              } else {
                tripCarInfo.copy(carWasUsed = false)
              }
          }

          collectedTripCarInfo.carWasLost || !collectedTripCarInfo.carWasUsed
      }
    }

    /** @return the tuple of (transformed trip, transformed HOV2 count, transformed HOV3 count) */
    def mapToForcedHOVTeleportation(trip: List[PlanElement]): (List[PlanElement], Int, Int) = {
      var forcedHOV2Teleports = 0
      var forcedHOV3Teleports = 0
      // return trip with all HOV replaced by HOV_teleportation
      val transformedTrip = trip.map {
        case hov2Leg if itIsAnHOV2Leg(hov2Leg) =>
          forcedHOV2Teleports += 1
          hov2Leg.copy(legMode = Some(HOV2_TELEPORTATION.value))

        case hov3Leg if itIsAnHOV3Leg(hov3Leg) =>
          forcedHOV3Teleports += 1
          hov3Leg.copy(legMode = Some(HOV3_TELEPORTATION.value))

        case leg if leg.planElementType == PlanElement.Leg                => leg
        case activity if activity.planElementType == PlanElement.Activity => activity
      }

      (transformedTrip, forcedHOV2Teleports, forcedHOV3Teleports)
    }

    private case class TripCarInfo(
      lastCarPositionX: Double,
      lastCarPositionY: Double,
      carIsNearby: Boolean,
      carWasLost: Boolean = false,
      carWasUsed: Boolean = false
    ) {

      def withNewActivityLocation(activity: PlanElement): TripCarInfo = {
        (activity.activityLocationX, activity.activityLocationY) match {
          case (Some(locationX), Some(locationY)) =>
            this.copy(lastCarPositionX = locationX, lastCarPositionY = locationY)
          case _ => this
        }
      }

      def withInformationIfCarIsNearby(activity: PlanElement): TripCarInfo = {
        val carIsNearby = (activity.activityLocationX, activity.activityLocationY) match {
          case (Some(locationX), Some(locationY)) => isNearby(locationX, lastCarPositionX, locationY, lastCarPositionY)
          case _                                  => false
        }
        this.copy(carIsNearby = carIsNearby)
      }

      def isNearby(x1: Double, x2: Double, y1: Double, y2: Double): Boolean = {
        x1 == x2 && y1 == y2
      }
    }
  }

  object ForcedCarHOVTransformer {

    def isForcedCarHOVTrip(trip: List[PlanElement]): Boolean = {
      val modes = trip.flatMap(_.legMode.map(_.toLowerCase))
      modes.exists(allCarModes.contains) && modes.exists(allHOVModes.contains)
    }

    /** @return the tuple of (transformed trip, transformed CAR_HOV2 count, transformed CAR_HOV3 count) */
    def mapToForcedCarHOVTrip(trip: List[PlanElement]): (List[PlanElement], Int, Int) = {
      var forcedCarHOV2Count = 0
      var forcedCarHOV3Count = 0

      val transformedTrip = trip.map {
        case hov2Leg if itIsAnHOV2Leg(hov2Leg) =>
          forcedCarHOV2Count += 1
          hov2Leg.copy(legMode = Some(CAR_HOV2.value))

        case hov3Leg if itIsAnHOV3Leg(hov3Leg) =>
          forcedCarHOV3Count += 1
          hov3Leg.copy(legMode = Some(CAR_HOV3.value))

        case leg if leg.planElementType == PlanElement.Leg                => leg
        case activity if activity.planElementType == PlanElement.Activity => activity
      }

      (transformedTrip, forcedCarHOV2Count, forcedCarHOV3Count)
    }
  }

  object RandomCarHOVTransformer {
    private val chanceToBeCarHOV2 = 0.5
    private val chanceToBeCarHOV3 = 0.333333333333

    def mapRandomHOVTeleportationOrCar(trip: List[PlanElement])(implicit rand: Random): List[PlanElement] = {
      def getHOV2CarOrTeleportation: String = {
        if (rand.nextDouble <= chanceToBeCarHOV2) {
          CAR_HOV2.value
        } else {
          HOV2_TELEPORTATION.value
        }
      }

      def getHOV3CarOrTeleportation: String = {
        if (rand.nextDouble <= chanceToBeCarHOV3) {
          CAR_HOV3.value
        } else {
          HOV3_TELEPORTATION.value
        }
      }

      trip.map { planElement =>
        planElement.legMode match {
          case Some(value) if isHOV2(value) => planElement.copy(legMode = Some(getHOV2CarOrTeleportation))
          case Some(value) if isHOV3(value) => planElement.copy(legMode = Some(getHOV3CarOrTeleportation))
          case _                            => planElement
        }
      }
    }
  }
}
