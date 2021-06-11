package beam.utils.scenario.urbansim

import beam.router.Modes.BeamMode._
import beam.utils.scenario._
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable
import scala.util.Random

object HOVModeTransformer extends LazyLogging {

  def reseedRandomGenerator(randomSeed: Int): Unit = rand.setSeed(randomSeed)

  def splitToTrips(planElements: Iterable[PlanElement]): Iterable[Iterable[PlanElement]] = {
    val trips = mutable.ListBuffer.empty[Iterable[PlanElement]]
    val personToTrip = mutable.HashMap.empty[PersonId, mutable.ListBuffer[PlanElement]]

    val homeActivity = "home"

    def addLeg(leg: PlanElement): Unit = {
      personToTrip.get(leg.personId) match {
        case Some(trip) => trip.append(leg)
        case None =>
          logger.error(
            s"Trip should be started from activity. Can't append leg to the trip, missing trip for person ${leg.personId}"
          )
      }
    }

    def addActivity(activity: PlanElement): Unit = {
      personToTrip.get(activity.personId) match {
        case Some(trip) if activity.activityType.map(_.toLowerCase).contains(homeActivity) =>
          trips.append(trip :+ activity)
          personToTrip.remove(activity.personId)

        case Some(trip) =>
          trip.append(activity)

        case None =>
          personToTrip(activity.personId) = mutable.ListBuffer(activity)
      }
    }

    planElements.foreach { planElement =>
      planElement.planElementType match {
        case PlanElement.Activity => addActivity(planElement)
        case PlanElement.Leg      => addLeg(planElement)
      }
    }

    if (personToTrip.nonEmpty) {
      val cnt = personToTrip.size
      val persons = personToTrip.keySet.mkString(",")
      logger.error(s"There are $cnt trips which did not end with Home activity. Affected persons: $persons")
    }

    trips ++ personToTrip.values
  }

  private val rand: Random = new Random(42)
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

  private val allHOVModes = Set(hov2, hov3)
  private val allCarModesWithHOV: Set[String] =
    (Seq(CAR, CAV, CAR_HOV2, CAR_HOV3).map(_.value.toLowerCase) ++ allHOVModes).toSet

  def transformHOVtoHOVCARorHOVTeleportation(
    plansProbablyWithHOV: Iterable[PlanElement],
    persons: Iterable[PersonInfo],
    households: Iterable[HouseholdInfo]
  ): Iterable[PlanElement] = {
    val hh2car: mutable.HashMap[HouseholdId, Int] =
      mutable.HashMap(households.map(hh => hh.householdId -> hh.cars).toSeq: _*)

    val allHOVUsers: Set[PersonId] = plansProbablyWithHOV
      .filter(planElement => {
        val legMode = planElement.legMode.map(_.toLowerCase)
        legMode.contains(hov2) || legMode.contains(hov3)
      })
      .map(_.personId)
      .toSet

    val allExpectedCarUsers: Set[PersonId] = plansProbablyWithHOV
      .filter(
        planElement => planElement.legMode.exists(mode => allCarModesWithHOV.contains(mode.toLowerCase))
      )
      .map(_.personId)
      .toSet

    val didNotGetACar = mutable.Set.empty[PersonId]
    val didGetACar = mutable.Set.empty[PersonId]
    persons.foreach { person =>
      if (!didGetACar.contains(person.personId) && allExpectedCarUsers.contains(person.personId)) {
        hh2car.get(person.householdId) match {
          case Some(value) if value > 0 =>
            didGetACar.add(person.personId)
            hh2car(person.householdId) = value - 1
          case _ => didNotGetACar.add(person.personId)
        }
      }
    }

    var forcedHOV2Teleports = 0
    var forcedHOV3Teleports = 0

    val plansWithAccessToCar = if (didNotGetACar.nonEmpty) {
      plansProbablyWithHOV.map { planElement =>
        val personDidNotGetACar = didNotGetACar.contains(planElement.personId)

        planElement.legMode match {
          case Some(legMode) if personDidNotGetACar && isHOV2(legMode) =>
            forcedHOV2Teleports += 1
            planElement.copy(legMode = Some(HOV2_TELEPORTATION.value))

          case Some(legMode) if personDidNotGetACar && isHOV3(legMode) =>
            forcedHOV3Teleports += 1
            planElement.copy(legMode = Some(HOV3_TELEPORTATION.value))

          case _ => planElement
        }
      }
    } else {
      plansProbablyWithHOV
    }

    val theRestOfHOVUsers = allHOVUsers -- didNotGetACar

    val tripsWithHOV = splitToTrips(plansWithAccessToCar).map { trip =>
      if (theRestOfHOVUsers.contains(trip.head.personId)) {

        case class TripCarInfo(
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
              case (Some(locationX), Some(locationY)) => lastCarPositionX == locationX && lastCarPositionY == locationY
              case _                                  => false
            }
            this.copy(carIsNearby = carIsNearby)
          }
        }
        val maybeTripCarInfo = (trip.head.activityLocationX, trip.head.activityLocationY) match {
          case (Some(locationX), Some(locationY)) => Some(TripCarInfo(locationX, locationY, carIsNearby = true))
          case _                                  => None
        }
        val itIsForcedHOVTeleportation: Boolean = maybeTripCarInfo match {
          case None => false // we can't work with trip that starts not from activity

          case Some(initialTripCarInfo) =>
            val collectedTripCarInfo = trip.foldLeft(initialTripCarInfo) {
              // if car was lost once, there is no need to analyze anymore
              case (tripCarInfo, _) if tripCarInfo.carWasLost => tripCarInfo

              // if this is an activity and car was driven, then move the car to the new location
              case (tripCarInfo, planElement)
                  if planElement.planElementType == PlanElement.Activity && tripCarInfo.carWasUsed =>
                tripCarInfo.withNewActivityLocation(planElement).copy(carWasUsed = false)

              // if this is an activity and car was not driven, check if car is nearby right now
              case (tripCarInfo, planElement) if planElement.planElementType == PlanElement.Activity =>
                tripCarInfo.withInformationIfCarIsNearby(planElement)

              // if this is a leg then check what happens with the car
              case (tripCarInfo, planElement) if planElement.planElementType == PlanElement.Leg =>
                val isCARmode = planElement.legMode.exists(mode => allCarModesWithHOV.contains(mode))
                if (isCARmode && tripCarInfo.carIsNearby) {
                  tripCarInfo.copy(carWasUsed = true)
                } else if (isCARmode) {
                  tripCarInfo.copy(carWasLost = true)
                } else {
                  tripCarInfo.copy(carWasUsed = false)
                }
            }

            collectedTripCarInfo.carWasLost
        }

        if (itIsForcedHOVTeleportation) {
          // return trip with all HOV replaced by HOV_teleportation
          def itIsAnHOV2Leg(planElement: PlanElement): Boolean = {
            planElement.planElementType == PlanElement.Leg &&
            planElement.legMode.exists(legMode => legMode.toLowerCase == hov2)
          }
          def itIsAnHOV3Leg(planElement: PlanElement): Boolean = {
            planElement.planElementType == PlanElement.Leg &&
            planElement.legMode.exists(legMode => legMode.toLowerCase == hov3)
          }
          trip.map {
            case hov2Leg if itIsAnHOV2Leg(hov2Leg) =>
              forcedHOV2Teleports += 1
              hov2Leg.copy(legMode = Some(HOV2_TELEPORTATION.value))

            case hov3Leg if itIsAnHOV3Leg(hov3Leg) =>
              forcedHOV3Teleports += 1
              hov3Leg.copy(legMode = Some(HOV3_TELEPORTATION.value))

            case leg if leg.planElementType == PlanElement.Leg                => leg
            case activity if activity.planElementType == PlanElement.Activity => activity
          }
        } else {
          trip
        }
      } else {
        trip
      }
    }

    if (forcedHOV2Teleports > 0 || forcedHOV3Teleports > 0) {
      logger.info(
        s"There were $forcedHOV2Teleports hov2 and $forcedHOV3Teleports hov3 forced teleports because actors did not get access to a car."
      )
    }

    def getHOV2CarOrTeleportation: String = {
      if (forcedHOV2Teleports > 0) {
        forcedHOV2Teleports -= 1
        CAR_HOV2.value
      } else if (rand.nextDouble <= 0.5) {
        CAR_HOV2.value
      } else {
        HOV2_TELEPORTATION.value
      }
    }

    // there are 1 driver and two passengers in HOV3, because of that:
    // it is minus 2 forced teleports
    // and probability to be a driver is 1/3
    def getHOV3CarOrTeleportation: String = {
      if (forcedHOV3Teleports > 0) {
        forcedHOV3Teleports -= 2
        CAR_HOV3.value
      } else if (rand.nextDouble <= 0.333333333333) {
        CAR_HOV3.value
      } else {
        HOV3_TELEPORTATION.value
      }
    }

    plansWithAccessToCar.map { planElement =>
      planElement.legMode match {
        case Some(value) if isHOV2(value) => planElement.copy(legMode = Some(getHOV2CarOrTeleportation))
        case Some(value) if isHOV3(value) => planElement.copy(legMode = Some(getHOV3CarOrTeleportation))
        case _                            => planElement
      }
    }
  }
}
