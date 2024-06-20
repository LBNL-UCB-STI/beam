package beam.agentsim.agents

import akka.actor.FSM.Failure
import akka.actor.{ActorRef, FSM, Props, Stash, Status}
import beam.agentsim.Resource._
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.freight.input.FreightReader.PAYLOAD_WEIGHT_IN_KG
import beam.agentsim.agents.household.HouseholdActor.ReleaseVehicle
import beam.agentsim.agents.household.HouseholdCAVDriverAgent
import beam.agentsim.agents.modalbehaviors.ChoosesMode.ChoosesModeData
import beam.agentsim.agents.modalbehaviors.DrivesVehicle._
import beam.agentsim.agents.modalbehaviors.{ChoosesMode, DrivesVehicle, ModeChoiceCalculator}
import beam.agentsim.agents.parking.ChoosesParking
import beam.agentsim.agents.parking.ChoosesParking.{ChoosingParkingSpot, ReleasingParkingSpot}
import beam.agentsim.agents.planning.{BeamPlan, Tour}
import beam.agentsim.agents.ridehail._
import beam.agentsim.agents.vehicles.AccessErrorCodes.UnknownInquiryIdError
import beam.agentsim.agents.vehicles.BeamVehicle.FuelConsumed
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleCategory.Bike
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.RideHailReservationConfirmationEvent.{Pooled, Solo}
import beam.agentsim.events._
import beam.agentsim.events.resources.{ReservationError, ReservationErrorCode}
import beam.agentsim.infrastructure.ChargingNetworkManager._
import beam.agentsim.infrastructure.parking.ParkingMNL
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.agentsim.infrastructure.{ParkingInquiryResponse, ParkingNetworkManager, ParkingStall}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, IllegalTriggerGoToError, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.agentsim.scheduler.{BeamAgentSchedulerTimer, Trigger}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{
  CAR,
  CAV,
  HOV2_TELEPORTATION,
  HOV3_TELEPORTATION,
  RIDE_HAIL,
  RIDE_HAIL_POOLED,
  RIDE_HAIL_TRANSIT,
  WALK,
  WALK_TRANSIT
}
import beam.router.RouteHistory
import beam.router.model.{BeamLeg, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.osm.TollCalculator
import beam.router.skim.ActivitySimSkimmerEvent
import beam.router.skim.event.{
  DriveTimeSkimmerEvent,
  ODSkimmerEvent,
  ODVehicleTypeSkimmerEvent,
  RideHailSkimmerEvent,
  UnmatchedRideHailRequestSkimmerEvent
}
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig.Beam.Debug
import beam.sim.population.AttributesOfIndividual
import beam.sim.{BeamScenario, BeamServices, Geofence}
import beam.utils.MeasureUnitConversion._
import beam.utils.NetworkHelper
import beam.utils.logging.ExponentialLazyLogging
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.population._
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.{EventsManager, TeleportationArrivalEvent}
import org.matsim.core.utils.misc.Time

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.concurrent.duration._

/**
  */
object PersonAgent {

  type VehicleStack = Vector[Id[BeamVehicle]]

  def props(
    scheduler: ActorRef,
    services: BeamServices,
    beamScenario: BeamScenario,
    modeChoiceCalculator: ModeChoiceCalculator,
    transportNetwork: TransportNetwork,
    tollCalculator: TollCalculator,
    router: ActorRef,
    rideHailManager: ActorRef,
    parkingManager: ActorRef,
    chargingNetworkManager: ActorRef,
    eventsManager: EventsManager,
    personId: Id[PersonAgent],
    householdRef: ActorRef,
    plan: Plan,
    fleetManagers: Seq[ActorRef],
    sharedVehicleFleets: Seq[ActorRef],
    possibleSharedVehicleTypes: Set[BeamVehicleType],
    routeHistory: RouteHistory
  ): Props = {
    Props(
      new PersonAgent(
        scheduler,
        services,
        beamScenario,
        modeChoiceCalculator,
        transportNetwork,
        router,
        rideHailManager,
        eventsManager,
        personId,
        plan,
        parkingManager,
        chargingNetworkManager,
        tollCalculator,
        householdRef,
        fleetManagers,
        sharedVehicleFleets,
        possibleSharedVehicleTypes,
        routeHistory
      )
    )
  }

  sealed trait Traveling extends BeamAgentState

  trait PersonData extends DrivingData

  trait DrivingData {
    def currentVehicle: VehicleStack

    def passengerSchedule: PassengerSchedule

    def currentLegPassengerScheduleIndex: Int

    def withPassengerSchedule(newPassengerSchedule: PassengerSchedule): DrivingData

    def withCurrentLegPassengerScheduleIndex(currentLegPassengerScheduleIndex: Int): DrivingData

    def hasParkingBehaviors: Boolean

    def geofence: Option[Geofence]

    def legStartsAt: Option[Int]
  }

  case class LiterallyDrivingData(delegate: DrivingData, legEndsAt: Double, legStartsAt: Option[Int])
      extends DrivingData { // sorry
    def currentVehicle: VehicleStack = delegate.currentVehicle

    def passengerSchedule: PassengerSchedule = delegate.passengerSchedule

    def currentLegPassengerScheduleIndex: Int =
      delegate.currentLegPassengerScheduleIndex

    def withPassengerSchedule(newPassengerSchedule: PassengerSchedule): DrivingData =
      LiterallyDrivingData(delegate.withPassengerSchedule(newPassengerSchedule), legEndsAt, legStartsAt)

    def withCurrentLegPassengerScheduleIndex(currentLegPassengerScheduleIndex: Int): DrivingData =
      LiterallyDrivingData(
        delegate.withCurrentLegPassengerScheduleIndex(currentLegPassengerScheduleIndex),
        legEndsAt,
        legStartsAt
      )

    override def hasParkingBehaviors: Boolean = false

    override def geofence: Option[Geofence] = delegate.geofence
  }

  /**
    * holds information for agent enroute
    * @param isInEnrouteState flag to indicate whether agent is in enroute node
    * @param hasReservedFastChargerStall flag indicate if the agent has reserved a stall with fast charger point
    * @param stall2DestLegs car legs from enroute charging stall to original destination
    */
  case class EnrouteData(
    isInEnrouteState: Boolean = false,
    hasReservedFastChargerStall: Boolean = false,
    stall2DestLegs: Vector[EmbodiedBeamLeg] = Vector()
  ) {
    def isEnrouting: Boolean = isInEnrouteState && hasReservedFastChargerStall
  }

  case class BasePersonData(
    currentActivityIndex: Int = 0,
    currentTrip: Option[EmbodiedBeamTrip] = None,
    restOfCurrentTrip: List[EmbodiedBeamLeg] = List.empty,
    currentVehicle: VehicleStack = Vector.empty,
    currentTourMode: Option[BeamMode] = None,
    currentTourPersonalVehicle: Option[Id[BeamVehicle]] = None,
    passengerSchedule: PassengerSchedule = PassengerSchedule(),
    currentLegPassengerScheduleIndex: Int = 0,
    hasDeparted: Boolean = false,
    currentTripCosts: Double = 0.0,
    rideHailReservedForLegs: IndexedSeq[EmbodiedBeamLeg] = IndexedSeq.empty,
    numberOfReplanningAttempts: Int = 0,
    failedTrips: IndexedSeq[EmbodiedBeamTrip] = IndexedSeq.empty,
    lastUsedParkingStall: Option[ParkingStall] = None,
    enrouteData: EnrouteData = EnrouteData()
  ) extends PersonData {

    def hasNextLeg: Boolean = restOfCurrentTrip.nonEmpty
    def nextLeg: EmbodiedBeamLeg = restOfCurrentTrip.head

    def shouldReserveRideHail(): Boolean = {
      // if we are about to walk then ride-hail
      // OR we are at a ride-hail leg but we didn't reserve a RH yet
      hasNextLeg && nextLeg.asDriver && nextLeg.beamLeg.mode == WALK &&
      restOfCurrentTrip.tail.headOption.exists(_.isRideHail) ||
      restOfCurrentTrip.headOption.exists(_.isRideHail) && !rideHailReservedForLegs.contains(restOfCurrentTrip.head)
    }

    def currentTourModeIsIn(modes: BeamMode*): Boolean = currentTourMode.exists(modes.contains)

    override def withPassengerSchedule(newPassengerSchedule: PassengerSchedule): DrivingData =
      copy(passengerSchedule = newPassengerSchedule)

    override def withCurrentLegPassengerScheduleIndex(
      newLegPassengerScheduleIndex: Int
    ): DrivingData = copy(currentLegPassengerScheduleIndex = newLegPassengerScheduleIndex)

    override def hasParkingBehaviors: Boolean = true

    override def geofence: Option[Geofence] = None
    override def legStartsAt: Option[Int] = None
  }

  case class ActivityStartTrigger(tick: Int) extends Trigger

  case class ActivityEndTrigger(tick: Int) extends Trigger

  case class PersonDepartureTrigger(tick: Int) extends Trigger

  case class TeleportationEndsTrigger(tick: Int) extends Trigger

  case object PerformingActivity extends BeamAgentState

  case object ChoosingMode extends Traveling

  case object Teleporting extends Traveling

  case object WaitingForDeparture extends Traveling

  case object WaitingForReservationConfirmation extends Traveling

  case object WaitingForRideHailReservationConfirmation extends Traveling

  case object Waiting extends Traveling

  case object ProcessingNextLegOrStartActivity extends Traveling

  case object ActuallyProcessingNextLegOrStartActivity extends Traveling

  case object TryingToBoardVehicle extends Traveling

  case object WaitingToDrive extends Traveling

  case object WaitingToDriveInterrupted extends Traveling

  case object PassengerScheduleEmpty extends Traveling

  case object PassengerScheduleEmptyInterrupted extends Traveling

  case object ReadyToChooseParking extends Traveling

  case object Moving extends Traveling

  case object Driving extends Traveling

  case object DrivingInterrupted extends Traveling

  case object EnrouteRefueling extends Traveling

  def correctTripEndTime(
    trip: EmbodiedBeamTrip,
    endTime: Int,
    bodyVehicleId: Id[BeamVehicle],
    bodyVehicleTypeId: Id[BeamVehicleType]
  ): EmbodiedBeamTrip = {
    if (trip.tripClassifier != WALK && trip.tripClassifier != WALK_TRANSIT) {
      trip.copy(
        legs = trip.legs
          .dropRight(1) :+ EmbodiedBeamLeg
          .dummyLegAt(
            endTime - trip.legs.last.beamLeg.duration,
            bodyVehicleId,
            isLastLeg = true,
            trip.legs.dropRight(1).last.beamLeg.travelPath.endPoint.loc,
            WALK,
            bodyVehicleTypeId,
            asDriver = true,
            trip.legs.last.beamLeg.duration
          )
      )
    } else {
      trip
    }
  }

  def findPersonData(data: DrivingData): Option[BasePersonData] = data match {
    case basePersonData: BasePersonData => Some(basePersonData)
    case _                              => None
  }
}

class PersonAgent(
  val scheduler: ActorRef,
  val beamServices: BeamServices,
  val beamScenario: BeamScenario,
  val modeChoiceCalculator: ModeChoiceCalculator,
  val transportNetwork: TransportNetwork,
  val router: ActorRef,
  val rideHailManager: ActorRef,
  val eventsManager: EventsManager,
  override val id: Id[PersonAgent],
  val matsimPlan: Plan,
  val parkingManager: ActorRef,
  val chargingNetworkManager: ActorRef,
  val tollCalculator: TollCalculator,
  val householdRef: ActorRef,
  val fleetManagers: Seq[ActorRef] = Vector(),
  val sharedVehicleFleets: Seq[ActorRef] = Vector(),
  val possibleSharedVehicleTypes: Set[BeamVehicleType] = Set.empty,
  val routeHistory: RouteHistory
) extends DrivesVehicle[PersonData]
    with ChoosesMode
    with ChoosesParking
    with Stash
    with ExponentialLazyLogging {

  override val eventBuilderActor: ActorRef = beamServices.eventBuilderActor
  implicit val debug: Debug = beamServices.beamConfig.beam.debug

  val networkHelper: NetworkHelper = beamServices.networkHelper
  val geo: GeoUtils = beamServices.geo

  val minDistanceToTrainStop: Double =
    beamScenario.beamConfig.beam.agentsim.agents.tripBehaviors.carUsage.minDistanceToTrainStop

  val bodyType: BeamVehicleType = beamScenario.vehicleTypes(
    Id.create(beamScenario.beamConfig.beam.agentsim.agents.bodyType, classOf[BeamVehicleType])
  )

  val body: BeamVehicle = new BeamVehicle(
    BeamVehicle.createId(id, Some("body")),
    new Powertrain(bodyType.primaryFuelConsumptionInJoulePerMeter),
    bodyType,
    vehicleManagerId = new AtomicReference(VehicleManager.NoManager.managerId)
  )

  body.setManager(Some(self))
  beamVehicles.put(body.id, ActualVehicle(body))

  val vehicleFleets: Seq[ActorRef] = fleetManagers ++ sharedVehicleFleets

  val attributes: AttributesOfIndividual =
    matsimPlan.getPerson.getCustomAttributes
      .get("beam-attributes")
      .asInstanceOf[AttributesOfIndividual]

  val _experiencedBeamPlan: BeamPlan = BeamPlan(matsimPlan)

  var totFuelConsumed: FuelConsumed = FuelConsumed(0.0, 0.0)
  var curFuelConsumed: FuelConsumed = FuelConsumed(0.0, 0.0)

  override def payloadInKgForLeg(leg: BeamLeg, drivingData: DrivingData): Option[Double] = {
    drivingData match {
      case data: BasePersonData => getPayloadWeightFromLeg(data.currentActivityIndex)
      case _                    => None
    }
  }

  def wheelchairUser: Boolean = {
    attributes.wheelchairUser
  }

  def updateFuelConsumed(fuelOption: Option[FuelConsumed]): Unit = {
    val newFuelConsumed = fuelOption.getOrElse(FuelConsumed(0.0, 0.0))
    curFuelConsumed = FuelConsumed(
      curFuelConsumed.primaryFuel + newFuelConsumed.primaryFuel,
      curFuelConsumed.secondaryFuel + newFuelConsumed.secondaryFuel
    )
    totFuelConsumed = FuelConsumed(
      totFuelConsumed.primaryFuel + curFuelConsumed.primaryFuel,
      totFuelConsumed.secondaryFuel + curFuelConsumed.secondaryFuel
    )
  }

  def resetFuelConsumed(): Unit = curFuelConsumed = FuelConsumed(0.0, 0.0)

  override def logDepth: Int = beamScenario.beamConfig.beam.debug.actor.logDepth

  val lastTickOfSimulation: Int = Time
    .parseTime(beamScenario.beamConfig.beam.agentsim.endTime)
    .toInt - beamServices.beamConfig.beam.agentsim.schedulerParallelismWindow

  /**
    * identifies agents with remaining range which is smaller than their remaining tour
    *
    * @param personData current state data cast as a [[BasePersonData]]
    * @return true if they have enough fuel, or fuel type is not exhaustible
    */
  def calculateRemainingTripData(personData: BasePersonData): Option[ParkingMNL.RemainingTripData] = {

    // if enroute then trip is not started yet, pick vehicle id of next leg (head of rest of the trip)
    // else the vehicle information is available in `currentVehicle`
    val vehicleId =
      if (personData.enrouteData.isInEnrouteState) personData.restOfCurrentTrip.head.beamVehicleId
      else personData.currentVehicle.head

    val beamVehicle = beamVehicles(vehicleId).vehicle

    val refuelNeeded: Boolean =
      beamVehicle.isRefuelNeeded(
        beamScenario.beamConfig.beam.agentsim.agents.rideHail.human.refuelRequiredThresholdInMeters,
        beamScenario.beamConfig.beam.agentsim.agents.rideHail.human.noRefuelThresholdInMeters
      )

    if (refuelNeeded) {

      val primaryFuelLevelInJoules: Double = beamScenario
        .privateVehicles(vehicleId)
        .primaryFuelLevelInJoules

      val primaryFuelConsumptionInJoulePerMeter: Double =
        beamVehicle.beamVehicleType.primaryFuelConsumptionInJoulePerMeter

      val remainingTourDist: Double = nextActivity(personData) match {
        case Some(nextAct) =>
          // in the case that we are headed "home", we need to motivate charging.
          // in order to induce range anxiety, we need to have agents consider
          // their tomorrow activities. the agent's first leg of the day
          // is used here to add distance to a "ghost activity" tomorrow morning
          // which is used in place of our real remaining tour distance of 0.0
          // which should help encourage residential end-of-day charging
          val tomorrowFirstLegDistance =
            if (nextAct.getType.toLowerCase == "home") {
              findFirstCarLegOfTrip(personData) match {
                case Some(carLeg) =>
                  carLeg.beamLeg.travelPath.distanceInM
                case None =>
                  0.0
              }
            } else 0.0

          val tripIndexOfElement = currentTour(personData)
            .tripIndexOfElement(nextAct)
            .getOrElse(throw new IllegalArgumentException(s"Element [$nextAct] not found"))
          val nextActIdx = tripIndexOfElement - 1
          currentTour(personData).trips
            .slice(nextActIdx, currentTour(personData).trips.length)
            .sliding(2, 1)
            .toList
            .foldLeft(tomorrowFirstLegDistance) { (sum, pair) =>
              sum + Math
                .ceil(
                  beamServices.skims.od_skimmer
                    .getTimeDistanceAndCost(
                      pair.head.activity.getCoord,
                      pair.last.activity.getCoord,
                      0,
                      CAR,
                      beamVehicle.beamVehicleType.id,
                      beamVehicle.beamVehicleType,
                      beamServices.beamScenario.fuelTypePrices(beamVehicle.beamVehicleType.primaryFuelType)
                    )
                    .distance
                )
            }

        case None =>
          0.0
      }

      Some(
        ParkingMNL.RemainingTripData(
          primaryFuelLevelInJoules,
          primaryFuelConsumptionInJoulePerMeter,
          remainingTourDist,
          beamScenario.beamConfig.beam.agentsim.agents.parking.rangeAnxietyBuffer
        )
      )

    } else {
      None
    }
  }

  startWith(Uninitialized, BasePersonData())

  def currentTour(data: BasePersonData): Tour = {
    stateName match {
      case PerformingActivity =>
        _experiencedBeamPlan.getTourContaining(currentActivity(data))
      case _ =>
        _experiencedBeamPlan.getTourContaining(nextActivity(data).get)
    }
  }

  def currentActivity(data: BasePersonData): Activity =
    _experiencedBeamPlan.activities(data.currentActivityIndex)

  def nextActivity(data: BasePersonData): Option[Activity] = {
    val ind = data.currentActivityIndex + 1
    if (ind < 0 || ind >= _experiencedBeamPlan.activities.length) {
      None
    } else {
      Some(_experiencedBeamPlan.activities(ind))
    }
  }

  def findFirstCarLegOfTrip(data: BasePersonData): Option[EmbodiedBeamLeg] = {
    @tailrec
    def _find(remaining: IndexedSeq[EmbodiedBeamLeg]): Option[EmbodiedBeamLeg] = {
      if (remaining.isEmpty) None
      else if (remaining.head.beamLeg.mode == CAR) Some { remaining.head }
      else _find(remaining.tail)
    }
    for {
      trip <- data.currentTrip
      leg  <- _find(trip.legs)
    } yield {
      leg
    }
  }

  def calculateActivityEndTime(activity: Activity, tick: Double): Double = {
    def activityEndTime: Double = {
      def fallbackActivityEndTime: Double = {
        // logWarn(s"Activity endTime is negative or infinite ${activity}, assuming duration of 10 minutes.")
        // TODO consider ending the day here to match MATSim convention for start/end activity
        tick + 60 * 10
      }
      val endTime = activity.getEndTime
      var returnVal: Double =
        fallbackActivityEndTime //Because OptionalTime doesn't have a method which returns - given an fn
      endTime.ifDefined(endTimeVal =>
        if (endTimeVal >= tick) returnVal = endTimeVal
        else if (endTimeVal >= 0.0 && endTimeVal < tick) returnVal = tick
      )
      returnVal
    }

    val endTime: Double = beamServices.beamScenario.fixedActivitiesDurations.get(activity.getType) match {
      case Some(fixedDuration) => tick + fixedDuration
      case _                   => activityEndTime
    }
    if (lastTickOfSimulation >= tick) {
      Math.min(lastTickOfSimulation, endTime)
    } else {
      endTime
    }
  }

  def endActivityAndDepart(
    tick: Double,
    currentTrip: EmbodiedBeamTrip,
    data: BasePersonData
  ): Unit = {
    assert(currentActivity(data).getLinkId != null)

    val tripId: String = _experiencedBeamPlan.trips
      .lift(data.currentActivityIndex + 1) match {
      case Some(trip) =>
        trip.leg.map(l => Option(l.getAttributes.getAttribute("trip_id")).getOrElse("").toString).getOrElse("")
      case None => ""
    }

    // We end our activity when we actually leave, not when we decide to leave, i.e. when we look for a bus or
    // hail a ride. We stay at the party until our Uber is there.

    eventsManager.processEvent(
      new ActivityEndEvent(
        tick,
        id,
        currentActivity(data).getLinkId,
        currentActivity(data).getFacilityId,
        currentActivity(data).getType
      )
    )
    val pde = new BeamPersonDepartureEvent(
      tick,
      id,
      currentActivity(data).getLinkId,
      currentTrip.tripClassifier.value,
      tripId
    )
    eventsManager.processEvent(
      pde
    )
  }

  when(Uninitialized) { case Event(TriggerWithId(InitializeTrigger(_), triggerId), _) =>
    goto(Initialized) replying CompletionNotice(
      triggerId,
      Vector(ScheduleTrigger(ActivityStartTrigger(0), self))
    )
  }

  when(Initialized) { case Event(TriggerWithId(ActivityStartTrigger(tick), triggerId), data: BasePersonData) =>
    logDebug(s"starting at ${currentActivity(data).getType} @ $tick")
    goto(PerformingActivity) replying CompletionNotice(
      triggerId,
      Vector(
        ScheduleTrigger(
          ActivityEndTrigger(currentActivity(data).getEndTime.orElse(beam.UNDEFINED_TIME).toInt),
          self
        )
      )
    )
  }

  when(PerformingActivity) { case Event(TriggerWithId(ActivityEndTrigger(tick), triggerId), data: BasePersonData) =>
    nextActivity(data) match {
      case None =>
        logger.warn(s"didn't get nextActivity, PersonAgent:$id")
        stay replying CompletionNotice(triggerId)
      case Some(nextAct) =>
        logDebug(s"wants to go to ${nextAct.getType} @ $tick")
        holdTickAndTriggerId(tick, triggerId)
        val indexOfNextActivity = _experiencedBeamPlan.getPlanElements.indexOf(nextAct)
        val modeOfNextLeg = _experiencedBeamPlan.getPlanElements.get(indexOfNextActivity - 1) match {
          case leg: Leg => BeamMode.fromString(leg.getMode)
          case _        => None
        }
        val currentCoord = currentActivity(data).getCoord
        val nextCoord = nextActivity(data).get.getCoord
        goto(ChoosingMode) using ChoosesModeData(
          personData = data.copy(
            // If the mode of the next leg is defined and is CAV, use it, otherwise,
            // If we don't have a current tour mode (i.e. are not on a tour aka at home),
            // use the mode of the next leg as the new tour mode.
            currentTourMode = modeOfNextLeg match {
              case Some(CAV) =>
                Some(CAV)
              case _ =>
                data.currentTourMode.orElse(modeOfNextLeg)
            },
            numberOfReplanningAttempts = 0,
            failedTrips = IndexedSeq.empty,
            enrouteData = EnrouteData()
          ),
          SpaceTime(currentCoord, _currentTick.get),
          excludeModes =
            if (canUseCars(currentCoord, nextCoord)) Vector.empty
            else Vector(BeamMode.RIDE_HAIL, BeamMode.CAR, BeamMode.CAV)
        )
    }

  }

  when(Teleporting) {
    case Event(
          TriggerWithId(PersonDepartureTrigger(tick), triggerId),
          data: BasePersonData
        ) if data.currentTrip.isDefined && !data.hasDeparted =>
      endActivityAndDepart(tick, data.currentTrip.get, data)

      val arrivalTime = tick + data.currentTrip.get.totalTravelTimeInSecs
      scheduler ! CompletionNotice(
        triggerId,
        Vector(ScheduleTrigger(TeleportationEndsTrigger(arrivalTime), self))
      )

      stay() using data.copy(hasDeparted = true)

    case Event(
          TriggerWithId(TeleportationEndsTrigger(tick), triggerId),
          data: BasePersonData
        ) if data.currentTrip.isDefined && data.hasDeparted =>
      holdTickAndTriggerId(tick, triggerId)

      val currentTrip = data.currentTrip.get
      val teleportationEvent = new TeleportationEvent(
        time = tick,
        person = id,
        departureTime = currentTrip.legs.head.beamLeg.startTime,
        arrivalTime = tick,
        startX = currentTrip.legs.head.beamLeg.travelPath.startPoint.loc.getX,
        startY = currentTrip.legs.head.beamLeg.travelPath.startPoint.loc.getY,
        endX = currentTrip.legs.last.beamLeg.travelPath.endPoint.loc.getX,
        endY = currentTrip.legs.last.beamLeg.travelPath.endPoint.loc.getY,
        currentTourMode = data.currentTourMode.map(_.value)
      )
      eventsManager.processEvent(teleportationEvent)

      goto(ProcessingNextLegOrStartActivity) using data.copy(
        hasDeparted = true,
        currentVehicle = Vector.empty[Id[BeamVehicle]],
        currentTourPersonalVehicle = None
      )

  }

  when(WaitingForDeparture) {

    /**
      * Callback from [[ChoosesMode]]
      */
    case Event(
          TriggerWithId(PersonDepartureTrigger(tick), triggerId),
          data: BasePersonData
        ) if data.currentTrip.isDefined && !data.hasDeparted =>
      endActivityAndDepart(tick, data.currentTrip.get, data)

      holdTickAndTriggerId(tick, triggerId)
      goto(ProcessingNextLegOrStartActivity) using data.copy(hasDeparted = true)

    case Event(
          TriggerWithId(PersonDepartureTrigger(tick), triggerId),
          data: BasePersonData
        ) if data.hasDeparted =>
      // We're coming back from replanning, i.e. we are already on the trip, so we don't throw a departure event
      logDebug(s"replanned to leg ${data.restOfCurrentTrip.head}")
      holdTickAndTriggerId(tick, triggerId)
      goto(ProcessingNextLegOrStartActivity)
  }

  private def canUseCars(currentCoord: Coord, nextCoord: Coord): Boolean = {
    currentCoord == null || beamScenario.trainStopQuadTree
      .getDisk(currentCoord.getX, currentCoord.getY, minDistanceToTrainStop)
      .isEmpty || beamScenario.trainStopQuadTree.getDisk(nextCoord.getX, nextCoord.getY, minDistanceToTrainStop).isEmpty
  }

  def handleFailedRideHailReservation(
    error: ReservationError,
    response: RideHailResponse,
    data: BasePersonData
  ): State = {
    logDebug(s"replanning because ${error.errorCode}")
    val tick = _currentTick.getOrElse(response.request.departAt)
    val replanningReason = getReplanningReasonFrom(data, error.errorCode.entryName)
    eventsManager.processEvent(
      new RideHailReservationConfirmationEvent(
        tick,
        Id.createPersonId(id),
        None,
        RideHailReservationConfirmationEvent.typeWhenPooledIs(response.request.asPooled),
        Some(error.errorCode),
        response.request.requestTime,
        response.request.departAt,
        response.request.quotedWaitTime,
        beamServices.geo.utm2Wgs(response.request.pickUpLocationUTM),
        beamServices.geo.utm2Wgs(response.request.destinationUTM),
        None,
        response.directTripTravelProposal.map(_.travelDistanceForCustomer(bodyVehiclePersonId)),
        response.directTripTravelProposal.map(proposal =>
          proposal.travelTimeForCustomer(bodyVehiclePersonId) + proposal.timeToCustomer(bodyVehiclePersonId)
        ),
        None,
        response.request.withWheelchair
      )
    )
    eventsManager.processEvent(
      new UnmatchedRideHailRequestSkimmerEvent(
        eventTime = tick,
        tazId = beamScenario.tazTreeMap.getTAZ(response.request.pickUpLocationUTM).tazId,
        reservationType = if (response.request.asPooled) Pooled else Solo,
        wheelchairRequired = response.request.withWheelchair,
        serviceName = response.rideHailManagerName
      )
    )
    val currentCoord = beamServices.geo.wgs2Utm(data.restOfCurrentTrip.head.beamLeg.travelPath.startPoint).loc

    eventsManager.processEvent(
      new ReplanningEvent(
        tick,
        Id.createPersonId(id),
        replanningReason,
        currentCoord.getX,
        currentCoord.getY
      )
    )
    val nextCoord = nextActivity(data).get.getCoord
    goto(ChoosingMode) using ChoosesModeData(
      data.copy(
        currentTourMode = None,
        numberOfReplanningAttempts = data.numberOfReplanningAttempts + 1
      ),
      currentLocation = SpaceTime(
        currentCoord,
        tick
      ),
      isWithinTripReplanning = true,
      excludeModes = (if (data.numberOfReplanningAttempts > 0) Vector(RIDE_HAIL, RIDE_HAIL_POOLED, RIDE_HAIL_TRANSIT)
                      else Vector()) ++ (if (canUseCars(currentCoord, nextCoord)) Vector.empty[BeamMode]
                                         else Vector(BeamMode.RIDE_HAIL, BeamMode.CAR, BeamMode.CAV)).distinct
    )
  }

  when(WaitingForReservationConfirmation) {
    // TRANSIT SUCCESS
    case Event(ReservationResponse(Right(response), _), _) =>
      handleSuccessfulTransitReservation(response.triggersToSchedule)
    // TRANSIT FAILURE
    case Event(
          ReservationResponse(Left(firstErrorResponse), _),
          data: BasePersonData
        ) if data.hasNextLeg =>
      logDebug(s"replanning because ${firstErrorResponse.errorCode}")

      val currentCoord = beamServices.geo.wgs2Utm(data.nextLeg.beamLeg.travelPath.startPoint).loc
      val nextCoord = nextActivity(data).get.getCoord
      val replanningReason = getReplanningReasonFrom(data, firstErrorResponse.errorCode.entryName)
      eventsManager.processEvent(
        new ReplanningEvent(
          _currentTick.get,
          Id.createPersonId(id),
          replanningReason,
          currentCoord.getX,
          currentCoord.getY,
          nextCoord.getX,
          nextCoord.getY
        )
      )
      goto(ChoosingMode) using ChoosesModeData(
        data.copy(numberOfReplanningAttempts = data.numberOfReplanningAttempts + 1),
        currentLocation = SpaceTime(currentCoord, _currentTick.get),
        isWithinTripReplanning = true,
        excludeModes =
          if (canUseCars(currentCoord, nextCoord)) Vector.empty
          else Vector(BeamMode.RIDE_HAIL, BeamMode.CAR, BeamMode.CAV)
      )
  }

  when(WaitingForRideHailReservationConfirmation) {
    // RIDE HAIL DELAY
    case Event(DelayedRideHailResponse, data: BasePersonData) =>
      // this means ride hail manager is taking time to assign and we should complete our
      // current trigger and wait to be re-triggered by the manager
      val (_, triggerId) = releaseTickAndTriggerId()
      scheduler ! CompletionNotice(triggerId, Vector())
      stay() using data
    // RIDE HAIL DELAY SUCCESS (buffered mode of RHM)
    // we get RH response with tick and trigger so that we can start our WALKing leg at the right time
    case Event(
          TriggerWithId(RideHailResponseTrigger(tick, response: RideHailResponse), triggerId),
          data: BasePersonData
        ) if response.isSuccessful(id) =>
      //we need to save current tick in order to schedule the next trigger (StartLegTrigger)
      holdTickAndTriggerId(tick, triggerId)
      handleSuccessfulRideHailReservation(tick, response, data)
    // RIDE HAIL DELAY FAILURE
    // we use trigger for this to get triggerId back into hands of the person
    case Event(
          TriggerWithId(RideHailResponseTrigger(tick, response: RideHailResponse), triggerId),
          data: BasePersonData
        ) =>
      holdTickAndTriggerId(tick, triggerId)
      handleFailedRideHailReservation(response.error.getOrElse(UnknownInquiryIdError), response, data)
    // RIDE HAIL SUCCESS (single request mode of RHM)
    case Event(response: RideHailResponse, data: BasePersonData) if response.isSuccessful(id) =>
      handleSuccessfulRideHailReservation(_currentTick.get, response, data)
    // RIDE HAIL FAILURE (single request mode of RHM)
    case Event(response: RideHailResponse, data: BasePersonData) =>
      handleFailedRideHailReservation(response.error.getOrElse(UnknownInquiryIdError), response, data)
  }

  private def handleSuccessfulRideHailReservation(tick: Int, response: RideHailResponse, data: BasePersonData) = {
    val req = response.request
    val travelProposal = response.travelProposal.get
    val actualRideHailLegs =
      travelProposal.toEmbodiedBeamLegsForCustomer(bodyVehiclePersonId, response.rideHailManagerName)
    eventsManager.processEvent(
      new RideHailReservationConfirmationEvent(
        tick,
        Id.createPersonId(id),
        Some(travelProposal.rideHailAgentLocation.vehicleId),
        RideHailReservationConfirmationEvent.typeWhenPooledIs(req.asPooled),
        None,
        tick,
        req.departAt,
        req.quotedWaitTime,
        beamServices.geo.utm2Wgs(req.pickUpLocationUTM),
        beamServices.geo.utm2Wgs(req.destinationUTM),
        Some(actualRideHailLegs.head.beamLeg.startTime),
        response.directTripTravelProposal.map(_.travelDistanceForCustomer(bodyVehiclePersonId)),
        response.directTripTravelProposal.map(_.travelTimeForCustomer(bodyVehiclePersonId)),
        Some(travelProposal.estimatedPrice(req.customer.personId)),
        req.withWheelchair
      )
    )
    eventsManager.processEvent(
      new RideHailSkimmerEvent(
        eventTime = tick,
        tazId = beamScenario.tazTreeMap.getTAZ(req.pickUpLocationUTM).tazId,
        reservationType = if (req.asPooled) Pooled else Solo,
        serviceName = response.rideHailManagerName,
        waitTime = travelProposal.timeToCustomer(req.customer),
        costPerMile = travelProposal.estimatedPrice(req.customer.personId) /
          travelProposal.travelDistanceForCustomer(req.customer) * METERS_IN_MILE,
        wheelchairRequired = req.withWheelchair,
        vehicleIsWheelchairAccessible = travelProposal.rideHailAgentLocation.vehicleType.isWheelchairAccessible
      )
    )
    response.triggersToSchedule.foreach(scheduler ! _)
    // when we reserving a ride-hail the rest of our trip may contain an optional WALK leg before the RH leg
    val (walkLeg, tailLegs) = data.restOfCurrentTrip.span(!_.isRideHail)
    val newWalkLeg = walkLeg.map(leg => leg.copy(beamLeg = leg.beamLeg.updateStartTime(tick)))
    val otherLegs = tailLegs.dropWhile(_.isRideHail)
    val newTailLegs = EmbodiedBeamLeg.makeLegsConsistent(actualRideHailLegs ++ otherLegs)
    val newRestOfCurrentTrip = newWalkLeg ++: newTailLegs
    goto(ActuallyProcessingNextLegOrStartActivity) using data.copy(
      restOfCurrentTrip = newRestOfCurrentTrip.toList,
      rideHailReservedForLegs = actualRideHailLegs
    )
  }

  when(Waiting) {
    /*
     * Learn as passenger that it is time to board the vehicle
     */
    case Event(
          TriggerWithId(BoardVehicleTrigger(tick, vehicleToEnter), triggerId),
          data: BasePersonData
        ) if data.hasNextLeg =>
      val currentLeg = data.nextLeg
      logDebug(s"PersonEntersVehicle: $vehicleToEnter @ $tick")
      eventsManager.processEvent(new PersonEntersVehicleEvent(tick, id, vehicleToEnter))

      if (currentLeg.cost > 0.0) {
        currentLeg.beamLeg.travelPath.transitStops.foreach { transitStopInfo =>
          // If it doesn't have transitStopInfo, it is not a transit but a ridehailing trip
          eventsManager.processEvent(new AgencyRevenueEvent(tick, transitStopInfo.agencyId, currentLeg.cost))
        }
        eventsManager.processEvent(
          new PersonCostEvent(
            tick,
            id,
            data.currentTrip.get.tripClassifier.value,
            0.0, // incentive applies to a whole trip and is accounted for at Arrival
            0.0, // only drivers pay tolls, if a toll is in the fare it's still a fare
            currentLeg.cost
          )
        )
      }

      goto(Moving) replying CompletionNotice(triggerId) using data.copy(
        currentVehicle = vehicleToEnter +: data.currentVehicle
      )
  }

  when(Moving) {
    /*
     * Learn as passenger that it is time to alight the vehicle
     */
    case Event(
          TriggerWithId(AlightVehicleTrigger(tick, vehicleToExit, energyConsumedOption), triggerId),
          data: BasePersonData
        ) if data.hasNextLeg && vehicleToExit.equals(data.currentVehicle.head) =>
      updateFuelConsumed(energyConsumedOption)
      logDebug(s"PersonLeavesVehicle: $vehicleToExit @ $tick")
      eventsManager.processEvent(new PersonLeavesVehicleEvent(tick, id, vehicleToExit))
      holdTickAndTriggerId(tick, triggerId)
      goto(ProcessingNextLegOrStartActivity) using data.copy(
        restOfCurrentTrip = data.restOfCurrentTrip.tail.dropWhile(leg => leg.beamVehicleId == vehicleToExit),
        currentVehicle = data.currentVehicle.tail
      )
  }

  // Callback from DrivesVehicle. Analogous to AlightVehicleTrigger, but when driving ourselves.
  when(PassengerScheduleEmpty) {
    case Event(PassengerScheduleEmptyMessage(_, toll, triggerId, energyConsumedOption), data: BasePersonData) =>
      updateFuelConsumed(energyConsumedOption)
      val netTripCosts = data.currentTripCosts // This includes tolls because it comes from leg.cost
      if (toll > 0.0 || netTripCosts > 0.0)
        eventsManager.processEvent(
          new PersonCostEvent(
            _currentTick.get,
            matsimPlan.getPerson.getId,
            data.restOfCurrentTrip.head.beamLeg.mode.value,
            0.0,
            toll,
            netTripCosts // Again, includes tolls but "net" here means actual money paid by the person
          )
        )
      val dataForNextLegOrActivity = if (data.restOfCurrentTrip.head.unbecomeDriverOnCompletion) {
        data.copy(
          restOfCurrentTrip = data.restOfCurrentTrip.tail,
          currentVehicle = if (data.currentVehicle.size > 1) data.currentVehicle.tail else Vector(),
          currentTripCosts = 0.0
        )
      } else {
        data.copy(
          restOfCurrentTrip = data.restOfCurrentTrip.tail,
          currentVehicle = Vector(body.id),
          currentTripCosts = 0.0
        )
      }
      if (data.restOfCurrentTrip.head.unbecomeDriverOnCompletion) {
        val vehicleToExit = data.currentVehicle.head
        currentBeamVehicle.unsetDriver()
        nextNotifyVehicleResourceIdle.foreach(notifyVehicleIdle =>
          currentBeamVehicle.getManager match {
            case Some(manager) => manager ! notifyVehicleIdle
            case None =>
              logger.error(
                s"Vehicle ${currentBeamVehicle.id} does not have a manager, " +
                s"so I can't notify anyone it is idle"
              )
          }
        )
        eventsManager.processEvent(
          new PersonLeavesVehicleEvent(_currentTick.get, Id.createPersonId(id), vehicleToExit)
        )
        if (currentBeamVehicle != body) {
          if (currentBeamVehicle.beamVehicleType.vehicleCategory != Bike) {
            if (currentBeamVehicle.stall.isEmpty) logWarn("Expected currentBeamVehicle.stall to be defined.")
          }
          if (!currentBeamVehicle.isMustBeDrivenHome) {
            // Is a shared vehicle. Give it up.
            currentBeamVehicle.getManager.get ! ReleaseVehicle(currentBeamVehicle, triggerId)
            beamVehicles -= data.currentVehicle.head
          }
        }
      }
      goto(ProcessingNextLegOrStartActivity) using dataForNextLegOrActivity

  }

  when(ReadyToChooseParking, stateTimeout = Duration.Zero) {
    case Event(
          StateTimeout,
          data: BasePersonData
        ) if data.hasNextLeg =>
      val (trip, cost) = if (data.enrouteData.isInEnrouteState) {
        log.debug("ReadyToChooseParking, enroute trip: {}", data.restOfCurrentTrip.toString())
        // if enroute, keep the original trip and cost
        (data.restOfCurrentTrip, data.currentTripCosts)
      } else {
        log.debug("ReadyToChooseParking, trip: {}", data.restOfCurrentTrip.tail.toString())
        // "head" of the current trip is travelled, and returning rest of the trip
        // adding the cost of the "head" of the trip to the current cost
        (data.restOfCurrentTrip.tail, data.currentTripCosts + data.nextLeg.cost)
      }

      goto(ChoosingParkingSpot) using data.copy(
        restOfCurrentTrip = trip,
        currentTripCosts = cost
      )
  }

  onTransition { case _ -> _ =>
    unstashAll()
  }

  when(TryingToBoardVehicle) {
    case Event(Boarded(vehicle, _), _: BasePersonData) =>
      beamVehicles.put(vehicle.id, ActualVehicle(vehicle))
      potentiallyChargingBeamVehicles.remove(vehicle.id)
      goto(ProcessingNextLegOrStartActivity)
    case Event(NotAvailable(_), basePersonData: BasePersonData) =>
      log.debug("{} replanning because vehicle not available when trying to board")
      val replanningReason = getReplanningReasonFrom(basePersonData, ReservationErrorCode.ResourceUnavailable.entryName)
      val currentCoord =
        beamServices.geo.wgs2Utm(basePersonData.restOfCurrentTrip.head.beamLeg.travelPath.startPoint).loc
      eventsManager.processEvent(
        new ReplanningEvent(
          _currentTick.get,
          Id.createPersonId(id),
          replanningReason,
          currentCoord.getX,
          currentCoord.getY
        )
      )

      val nextCoord = nextActivity(basePersonData).get.getCoord
      goto(ChoosingMode) using ChoosesModeData(
        basePersonData.copy(
          currentTourMode = None, // Have to give up my mode as well, perhaps there's no option left for driving.
          currentTourPersonalVehicle = None,
          numberOfReplanningAttempts = basePersonData.numberOfReplanningAttempts + 1
        ),
        SpaceTime(currentCoord, _currentTick.get),
        excludeModes =
          if (canUseCars(currentCoord, nextCoord)) Vector.empty
          else Vector(BeamMode.RIDE_HAIL, BeamMode.CAR, BeamMode.CAV)
      )
  }

  when(EnrouteRefueling) {
    case Event(_: StartingRefuelSession, _) =>
      val (_, triggerId) = releaseTickAndTriggerId()
      scheduler ! CompletionNotice(triggerId)
      stay
    case Event(_: WaitingToCharge, _) =>
      stay
    case Event(EndingRefuelSession(tick, _, triggerId), data: BasePersonData) =>
      val (updatedTick, updatedData) = createStallToDestTripForEnroute(data, tick)
      if (_currentTick.isEmpty)
        holdTickAndTriggerId(updatedTick, triggerId)
      chargingNetworkManager ! ChargingUnplugRequest(tick, id, currentBeamVehicle, triggerId)
      stay using updatedData
    case Event(UnpluggingVehicle(tick, _, vehicle, _, energyCharged), data: BasePersonData) =>
      log.debug(s"Vehicle ${vehicle.id} ended charging and it is not handled by the CNM at tick $tick")
      ParkingNetworkManager.handleReleasingParkingSpot(
        tick,
        vehicle,
        Some(energyCharged),
        id,
        parkingManager,
        eventsManager
      )
      goto(ProcessingNextLegOrStartActivity) using data
    case Event(UnhandledVehicle(tick, _, vehicle, _), data: BasePersonData) =>
      log.error(
        s"Vehicle ${vehicle.id} is not handled by the CNM at tick $tick. Something is broken." +
        s"the agent will now disconnect the vehicle ${currentBeamVehicle.id} to let the simulation continue!"
      )
      ParkingNetworkManager.handleReleasingParkingSpot(tick, vehicle, None, id, parkingManager, eventsManager)
      goto(ProcessingNextLegOrStartActivity) using data
  }

  private def createStallToDestTripForEnroute(data: BasePersonData, startTime: Int): (Int, BasePersonData) = {
    // read preserved car legs to head back to original destination
    // append walk legs around them, update start time and make legs consistent
    // unset reserved charging stall
    // unset enroute state, and update `data` with new legs
    val stall2DestinationCarLegs = data.enrouteData.stall2DestLegs
    val walkStart = data.currentTrip.head.legs.head
    val walkRest = data.currentTrip.head.legs.last
    val newCurrentTripLegs: Vector[EmbodiedBeamLeg] =
      EmbodiedBeamLeg.makeLegsConsistent(walkStart +: (stall2DestinationCarLegs :+ walkRest), startTime)
    val newRestOfTrip: Vector[EmbodiedBeamLeg] = newCurrentTripLegs.tail
    (
      newRestOfTrip.head.beamLeg.startTime,
      data.copy(
        currentTrip = Some(EmbodiedBeamTrip(newCurrentTripLegs)),
        restOfCurrentTrip = newRestOfTrip.toList,
        enrouteData = EnrouteData()
      )
    )
  }

  when(ProcessingNextLegOrStartActivity, stateTimeout = Duration.Zero) {
    case Event(StateTimeout, data: BasePersonData) if data.shouldReserveRideHail() =>
      // Doing RH reservation before we start walking to our pickup location
      val ridehailTrip = data.restOfCurrentTrip.dropWhile(!_.isRideHail)
      doRideHailReservation(data.nextLeg.beamLeg.startTime, data.nextLeg.beamLeg.endTime, ridehailTrip)
      goto(WaitingForRideHailReservationConfirmation)
    case Event(StateTimeout, _) =>
      goto(ActuallyProcessingNextLegOrStartActivity)
  }

  when(ActuallyProcessingNextLegOrStartActivity, stateTimeout = Duration.Zero) {
    case Event(StateTimeout, data: BasePersonData) if data.hasNextLeg && data.nextLeg.asDriver =>
      val restOfCurrentTrip = data.restOfCurrentTrip.tail
      // Declaring a function here because this case is already so convoluted that I require a return
      // statement from within.
      // TODO: Refactor.
      def nextState: FSM.State[BeamAgentState, PersonData] = {
        val currentVehicleForNextState =
          if (data.currentVehicle.isEmpty || data.currentVehicle.head != data.nextLeg.beamVehicleId) {
            beamVehicles(data.nextLeg.beamVehicleId) match {
              case t @ Token(_, manager, _) =>
                manager ! TryToBoardVehicle(t, self, getCurrentTriggerIdOrGenerate)
                return goto(TryingToBoardVehicle)
              case _: ActualVehicle =>
              // That's fine, continue
            }
            eventsManager.processEvent(
              new PersonEntersVehicleEvent(
                _currentTick.get,
                Id.createPersonId(id),
                data.nextLeg.beamVehicleId
              )
            )
            data.nextLeg.beamVehicleId +: data.currentVehicle
          } else {
            data.currentVehicle
          }
        val legsToInclude = data.restOfCurrentTrip.takeWhile(_.beamVehicleId == data.nextLeg.beamVehicleId)
        val newPassengerSchedule = PassengerSchedule().addLegs(legsToInclude.map(_.beamLeg))

        // Enroute block
        // calculate whether enroute charging required or not.
        val vehicle = beamVehicles(data.nextLeg.beamVehicleId).vehicle
        val needEnroute = if (vehicle.isEV && !vehicle.isRideHail) {
          val enrouteConfig = beamServices.beamConfig.beam.agentsim.agents.vehicles.enroute
          val vehicleTrip = legsToInclude
          val totalDistance: Double = vehicleTrip.map(_.beamLeg.travelPath.distanceInM).sum
          // Calculating distance to cross before enroute charging
          val refuelRequiredThresholdInMeters = totalDistance + enrouteConfig.refuelRequiredThresholdOffsetInMeters
          val noRefuelThresholdInMeters = totalDistance + enrouteConfig.noRefuelThresholdOffsetInMeters
          val originUtm = vehicle.spaceTime.loc
          val lastLeg = vehicleTrip.last.beamLeg
          val destinationUtm = beamServices.geo.wgs2Utm(lastLeg.travelPath.endPoint.loc)
          //sometimes this distance is zero which causes parking stall search to get stuck
          val distUtm = geo.distUTMInMeters(originUtm, destinationUtm)
          val distanceWrtBatteryCapacity = totalDistance / vehicle.beamVehicleType.getTotalRange
          if (
            distanceWrtBatteryCapacity > enrouteConfig.remainingDistanceWrtBatteryCapacityThreshold ||
            totalDistance < enrouteConfig.noRefuelAtRemainingDistanceThresholdInMeters ||
            distUtm < enrouteConfig.noRefuelAtRemainingDistanceThresholdInMeters
          ) false
          else vehicle.isRefuelNeeded(refuelRequiredThresholdInMeters, noRefuelThresholdInMeters)
        } else {
          false
        }

        def sendCompletionNoticeAndScheduleStartLegTrigger(): Unit = {
          val tick = _currentTick.get
          val triggerId = _currentTriggerId.get
          scheduler ! CompletionNotice(
            triggerId,
            if (data.nextLeg.beamLeg.endTime > lastTickOfSimulation) Vector.empty
            else Vector(ScheduleTrigger(StartLegTrigger(tick, data.nextLeg.beamLeg), self))
          )
        }

        // decide next state to go, whether we need to complete the trigger, start a leg or both
        val stateToGo = {
          if (data.nextLeg.beamLeg.mode == CAR || vehicle.isSharedVehicle) {
            if (!needEnroute) sendCompletionNoticeAndScheduleStartLegTrigger()
            ReleasingParkingSpot
          } else {
            sendCompletionNoticeAndScheduleStartLegTrigger()
            releaseTickAndTriggerId()
            WaitingToDrive
          }
        }

        val updatedData = data.copy(
          passengerSchedule = newPassengerSchedule,
          currentLegPassengerScheduleIndex = 0,
          currentVehicle = currentVehicleForNextState,
          enrouteData = if (needEnroute) data.enrouteData.copy(isInEnrouteState = true) else data.enrouteData
        )

        goto(stateToGo) using updatedData
      }
      nextState

    // TRANSIT but too late
    case Event(StateTimeout, data: BasePersonData)
        if data.hasNextLeg && data.nextLeg.beamLeg.mode.isTransit &&
          data.nextLeg.beamLeg.startTime < _currentTick.get =>
      // We've missed the bus. This occurs when something takes longer than planned (based on the
      // initial inquiry). So we replan but change tour mode to WALK_TRANSIT since we've already done our non-transit
      // portion.
      log.debug("Missed transit pickup, late by {} sec", _currentTick.get - data.nextLeg.beamLeg.startTime)

      val replanningReason = getReplanningReasonFrom(data, ReservationErrorCode.MissedTransitPickup.entryName)
      val currentCoord = beamServices.geo.wgs2Utm(data.nextLeg.beamLeg.travelPath.startPoint).loc
      eventsManager.processEvent(
        new ReplanningEvent(
          _currentTick.get,
          Id.createPersonId(id),
          replanningReason,
          currentCoord.getX,
          currentCoord.getY
        )
      )

      val nextCoord = nextActivity(data).get.getCoord
      goto(ChoosingMode) using ChoosesModeData(
        personData = data
          .copy(currentTourMode = Some(WALK_TRANSIT), numberOfReplanningAttempts = data.numberOfReplanningAttempts + 1),
        currentLocation = SpaceTime(currentCoord, _currentTick.get),
        isWithinTripReplanning = true,
        excludeModes =
          if (canUseCars(currentCoord, nextCoord)) Vector.empty
          else Vector(BeamMode.RIDE_HAIL, BeamMode.CAR, BeamMode.CAV)
      )
    // TRANSIT
    case Event(StateTimeout, data: BasePersonData) if data.hasNextLeg && data.nextLeg.beamLeg.mode.isTransit =>
      val resRequest = TransitReservationRequest(
        data.nextLeg.beamLeg.travelPath.transitStops.get.fromIdx,
        data.nextLeg.beamLeg.travelPath.transitStops.get.toIdx,
        PersonIdWithActorRef(id, self),
        getCurrentTriggerIdOrGenerate
      )
      TransitDriverAgent.selectByVehicleId(data.nextLeg.beamVehicleId) ! resRequest
      goto(WaitingForReservationConfirmation)
    // RIDE_HAIL
    case Event(StateTimeout, data: BasePersonData) if data.hasNextLeg && data.nextLeg.isRideHail =>
      val (_, triggerId) = releaseTickAndTriggerId()
      scheduler ! CompletionNotice(triggerId)
      // we have already reserved RideHail now we need to wait for boarding a vehicle
      goto(Waiting)
    // CAV but too late
    // TODO: Refactor so it uses literally the same code block as transit
    case Event(StateTimeout, data: BasePersonData)
        if data.hasNextLeg && data.nextLeg.beamLeg.startTime < _currentTick.get =>
      // We've missed the CAV. This occurs when something takes longer than planned (based on the
      // initial inquiry). So we replan but change tour mode to WALK_TRANSIT since we've already done our non-transit
      // portion.
      log.warning("Missed CAV pickup, late by {} sec", _currentTick.get - data.nextLeg.beamLeg.startTime)

      val replanningReason = getReplanningReasonFrom(data, ReservationErrorCode.MissedTransitPickup.entryName)
      val currentCoord = beamServices.geo.wgs2Utm(data.nextLeg.beamLeg.travelPath.startPoint).loc
      eventsManager.processEvent(
        new ReplanningEvent(
          _currentTick.get,
          Id.createPersonId(id),
          replanningReason,
          currentCoord.getX,
          currentCoord.getY
        )
      )

      val nextCoord = nextActivity(data).get.getCoord
      goto(ChoosingMode) using ChoosesModeData(
        personData = data
          .copy(currentTourMode = Some(WALK_TRANSIT), numberOfReplanningAttempts = data.numberOfReplanningAttempts + 1),
        currentLocation = SpaceTime(currentCoord, _currentTick.get),
        isWithinTripReplanning = true,
        excludeModes =
          if (canUseCars(currentCoord, nextCoord)) Vector.empty
          else Vector(BeamMode.RIDE_HAIL, BeamMode.CAR, BeamMode.CAV)
      )
    // CAV
    // TODO: Refactor so it uses literally the same code block as transit
    case Event(StateTimeout, data: BasePersonData) if data.hasNextLeg =>
      val legSegment = data.restOfCurrentTrip.takeWhile(leg => leg.beamVehicleId == data.nextLeg.beamVehicleId)
      val resRequest = ReservationRequest(
        legSegment.head.beamLeg,
        legSegment.last.beamLeg,
        PersonIdWithActorRef(id, self),
        getCurrentTriggerIdOrGenerate
      )
      context.actorSelection(
        householdRef.path.child(HouseholdCAVDriverAgent.idFromVehicleId(data.nextLeg.beamVehicleId).toString)
      ) ! resRequest
      goto(WaitingForReservationConfirmation)

    case Event(
          StateTimeout,
          data: BasePersonData
        ) if data.currentTourModeIsIn(HOV2_TELEPORTATION, HOV3_TELEPORTATION) =>
      nextActivity(data) match {
        case Some(activity) =>
          val (tick, triggerId) = releaseTickAndTriggerId()
          val activityEndTime = calculateActivityEndTime(activity, tick)

          assert(activity.getLinkId != null)
          eventsManager.processEvent(
            new PersonArrivalEvent(tick, id, activity.getLinkId, CAR.value)
          )

          eventsManager.processEvent(
            new ActivityStartEvent(
              tick,
              id,
              activity.getLinkId,
              activity.getFacilityId,
              activity.getType,
              null
            )
          )

          val nextLegDepartureTime =
            if (activityEndTime > tick + beamServices.beamConfig.beam.agentsim.schedulerParallelismWindow) {
              activityEndTime.toInt
            } else {
              logger.warn(
                "Moving back next activity end time from {} to {} to avoid parallelism issues when teleporting",
                activityEndTime,
                tick + beamServices.beamConfig.beam.agentsim.schedulerParallelismWindow
              )
              tick + beamServices.beamConfig.beam.agentsim.schedulerParallelismWindow
            }

          scheduler ! CompletionNotice(
            triggerId,
            Vector(ScheduleTrigger(ActivityEndTrigger(nextLegDepartureTime), self))
          )
          goto(PerformingActivity) using data.copy(
            currentActivityIndex = data.currentActivityIndex + 1,
            currentTrip = None,
            restOfCurrentTrip = List(),
            currentTourPersonalVehicle = None,
            currentTourMode = if (activity.getType.equals("Home")) None else data.currentTourMode,
            hasDeparted = false
          )
        case None =>
          logDebug("PersonAgent nextActivity returned None")
          val (_, triggerId) = releaseTickAndTriggerId()
          scheduler ! CompletionNotice(triggerId)
          stop
      }
    // NEXT ACTIVITY
    case Event(StateTimeout, data: BasePersonData) if data.currentTrip.isDefined =>
      val currentTrip = data.currentTrip.get
      nextActivity(data) match {
        case Some(activity) =>
          val (tick, triggerId) = releaseTickAndTriggerId()
          val activityEndTime = calculateActivityEndTime(activity, tick)

          // Report travelled distance for inclusion in experienced plans.
          // We currently get large unaccountable differences in round trips, e.g. work -> home may
          // be twice as long as home -> work. Probably due to long links, and the location of the activity
          // on the link being undefined.
          eventsManager.processEvent(
            new TeleportationArrivalEvent(
              tick,
              id,
              currentTrip.legs.map(l => l.beamLeg.travelPath.distanceInM).sum,
              data.currentTourMode.map(_.matsimMode).getOrElse("")
            )
          )
          assert(activity.getLinkId != null)
          eventsManager.processEvent(
            new PersonArrivalEvent(tick, id, activity.getLinkId, currentTrip.tripClassifier.value)
          )
          val incentive = beamScenario.modeIncentives.computeIncentive(attributes, currentTrip.tripClassifier)
          if (incentive > 0.0)
            eventsManager.processEvent(
              new PersonCostEvent(
                tick,
                id,
                currentTrip.tripClassifier.value,
                math.min(incentive, currentTrip.costEstimate),
                0.0,
                0.0 // the cost as paid by person has already been accounted for, this event is just about the incentive
              )
            )
          data.failedTrips.foreach(uncompletedTrip =>
            generateSkimData(
              tick,
              uncompletedTrip,
              failedTrip = true,
              data.currentActivityIndex,
              currentActivity(data),
              nextActivity(data)
            )
          )
          val correctedTrip = correctTripEndTime(data.currentTrip.get, tick, body.id, body.beamVehicleType.id)
          generateSkimData(
            tick,
            correctedTrip,
            failedTrip = false,
            data.currentActivityIndex,
            currentActivity(data),
            nextActivity(data)
          )
          resetFuelConsumed()
          val activityStartEvent = new ActivityStartEvent(
            tick,
            id,
            activity.getLinkId,
            activity.getFacilityId,
            activity.getType,
            null
          )
          eventsManager.processEvent(activityStartEvent)

          val nextLegDepartureTime =
            if (activityEndTime > tick + beamServices.beamConfig.beam.agentsim.schedulerParallelismWindow) {
              activityEndTime.toInt
            } else {
              logger.warn(
                "Moving back next activity end time from {} to {} to avoid parallelism issues, currently on trip {}",
                activityEndTime,
                tick + beamServices.beamConfig.beam.agentsim.schedulerParallelismWindow,
                currentTrip
              )
              tick + beamServices.beamConfig.beam.agentsim.schedulerParallelismWindow
            }

          scheduler ! CompletionNotice(
            triggerId,
            Vector(ScheduleTrigger(ActivityEndTrigger(nextLegDepartureTime), self))
          )
          goto(PerformingActivity) using data.copy(
            currentActivityIndex = data.currentActivityIndex + 1,
            currentTrip = None,
            restOfCurrentTrip = List(),
            currentTourPersonalVehicle = data.currentTourPersonalVehicle match {
              case Some(personalVehId) =>
                val personalVeh = beamVehicles(personalVehId).asInstanceOf[ActualVehicle].vehicle
                if (activity.getType.equals("Home")) {
                  potentiallyChargingBeamVehicles.put(personalVeh.id, beamVehicles(personalVeh.id))
                  beamVehicles -= personalVeh.id
                  personalVeh.getManager.get ! ReleaseVehicle(personalVeh, triggerId)
                  None
                } else {
                  data.currentTourPersonalVehicle
                }
              case None =>
                None
            },
            currentTourMode = if (activity.getType.equals("Home")) None else data.currentTourMode,
            rideHailReservedForLegs = IndexedSeq.empty,
            hasDeparted = false
          )
        case None =>
          logDebug("PersonAgent nextActivity returned None")
          val (_, triggerId) = releaseTickAndTriggerId()
          scheduler ! CompletionNotice(triggerId)
          stop
      }
  }

  def getTazFromActivity(activity: Activity, tazTreeMap: TAZTreeMap): Id[TAZ] = {
    val linkId = Option(activity.getLinkId).getOrElse(
      Id.createLinkId(
        beamServices.geo
          .getNearestR5EdgeToUTMCoord(
            beamServices.beamScenario.transportNetwork.streetLayer,
            activity.getCoord,
            beamScenario.beamConfig.beam.routing.r5.linkRadiusMeters
          )
          .toString
      )
    )
    tazTreeMap
      .getTAZfromLink(linkId)
      .map(_.tazId)
      .getOrElse(tazTreeMap.getTAZ(activity.getCoord).tazId)
  }

  /**
    * Do RH reservation
    * @param restOfCurrentTrip it must start with a RH leg that is the subject of reservation
    */
  private def doRideHailReservation(
    currentTick: Int,
    departureTime: Int,
    restOfCurrentTrip: List[EmbodiedBeamLeg]
  ): Unit = {
    val rideHailLeg = restOfCurrentTrip.head
    val rhVehicleId = rideHailLeg.beamVehicleId
    val rideHailLegEndpoint =
      restOfCurrentTrip.takeWhile(_.beamVehicleId == rhVehicleId).last.beamLeg.travelPath.endPoint.loc

    rideHailManager ! RideHailRequest(
      ReserveRide(rideHailLeg.rideHailManagerName.get),
      PersonIdWithActorRef(id, self),
      beamServices.geo.wgs2Utm(rideHailLeg.beamLeg.travelPath.startPoint.loc),
      departureTime,
      beamServices.geo.wgs2Utm(rideHailLegEndpoint),
      asPooled = rideHailLeg.isPooledTrip,
      withWheelchair = wheelchairUser,
      requestTime = currentTick,
      quotedWaitTime = Some(rideHailLeg.beamLeg.startTime - departureTime),
      requester = self,
      rideHailServiceSubscription = attributes.rideHailServiceSubscription,
      triggerId = getCurrentTriggerIdOrGenerate
    )

    eventsManager.processEvent(
      new ReserveRideHailEvent(
        currentTick.toDouble,
        id,
        departureTime,
        rideHailLeg.beamLeg.travelPath.startPoint.loc,
        rideHailLegEndpoint,
        wheelchairUser
      )
    )
  }

  protected def getOriginAndDestinationFromGeoMap(
    currentAct: Activity,
    maybeNextAct: Option[Activity]
  ): (String, String) = {
    // Selecting the geoMap with highest resolution by comparing their number of zones
    val geoMap = beamScenario.tazTreeMapForASimSkimmer
    val (origin, destination) = if (geoMap.tazListContainsGeoms) {
      val origGeo = getTazFromActivity(currentAct, geoMap).toString
      val destGeo = maybeNextAct.map(act => getTazFromActivity(act, geoMap).toString).getOrElse("NA")
      (origGeo, destGeo)
    } else {
      (
        geoMap.getTAZ(currentAct.getCoord).toString,
        maybeNextAct.map(act => geoMap.getTAZ(act.getCoord).toString).getOrElse("NA")
      )
    }
    (origin, destination)
  }

  private def processActivitySimSkimmerEvent(
    currentAct: Activity,
    maybeNextAct: Option[Activity],
    odSkimmerEvent: ODSkimmerEvent
  ): Unit = {
    val (origin, destination) = getOriginAndDestinationFromGeoMap(currentAct, maybeNextAct)
    val asSkimmerEvent = ActivitySimSkimmerEvent(
      origin,
      destination,
      odSkimmerEvent.eventTime,
      odSkimmerEvent.trip,
      odSkimmerEvent.generalizedTimeInHours,
      odSkimmerEvent.generalizedCost,
      odSkimmerEvent.energyConsumption,
      beamServices.beamConfig.beam.router.skim.activity_sim_skimmer.name
    )
    eventsManager.processEvent(asSkimmerEvent)
  }

  def generateSkimData(
    tick: Int,
    trip: EmbodiedBeamTrip,
    failedTrip: Boolean,
    currentActivityIndex: Int,
    currentActivity: Activity,
    nextActivity: Option[Activity]
  ): Unit = {
    val correctedTrip = correctTripEndTime(trip, tick, body.id, body.beamVehicleType.id)
    val generalizedTime = modeChoiceCalculator.getGeneralizedTimeOfTrip(correctedTrip, Some(attributes), nextActivity)
    val generalizedCost = modeChoiceCalculator.getNonTimeCost(correctedTrip) + attributes.getVOT(generalizedTime)
    val maybePayloadWeightInKg = getPayloadWeightFromLeg(currentActivityIndex)

    if (maybePayloadWeightInKg.isDefined && correctedTrip.tripClassifier != BeamMode.CAR) {
      logger.error("Wrong trip classifier ({}) for freight {}", correctedTrip.tripClassifier, id)
    }
    // Correct the trip to deal with ride hail / disruptions and then register to skimmer
    val (odSkimmerEvent, _, _) = ODSkimmerEvent.forTaz(
      tick,
      beamServices,
      correctedTrip,
      generalizedTime,
      generalizedCost,
      maybePayloadWeightInKg,
      curFuelConsumed.totalEnergyConsumed,
      failedTrip
    )
    eventsManager.processEvent(odSkimmerEvent)
    if (beamServices.beamConfig.beam.exchange.output.activity_sim_skimmer.exists(_.primary.enabled)) {
      processActivitySimSkimmerEvent(currentActivity, nextActivity, odSkimmerEvent)
    }

    correctedTrip.legs.filter(x => x.beamLeg.mode == BeamMode.CAR || x.beamLeg.mode == BeamMode.CAV).foreach { carLeg =>
      eventsManager.processEvent(DriveTimeSkimmerEvent(tick, beamServices, carLeg))
    }
    if (!failedTrip && correctedTrip.tripClassifier == BeamMode.CAR) {
      val vehicleTypeId = correctedTrip.legs.find(_.beamLeg.mode == CAR).get.beamVehicleTypeId
      val odVehicleTypeEvent = ODVehicleTypeSkimmerEvent(
        tick,
        beamServices,
        vehicleTypeId,
        correctedTrip,
        generalizedTime,
        generalizedCost,
        maybePayloadWeightInKg,
        curFuelConsumed.totalEnergyConsumed
      )
      eventsManager.processEvent(odVehicleTypeEvent)
    }
  }

  private def getPayloadWeightFromLeg(currentActivityIndex: Int): Option[Double] = {
    val currentLegIndex = currentActivityIndex * 2 + 1
    if (currentLegIndex < matsimPlan.getPlanElements.size()) {
      val accomplishedLeg = matsimPlan.getPlanElements.get(currentLegIndex)
      Option(accomplishedLeg.getAttributes.getAttribute(PAYLOAD_WEIGHT_IN_KG)).asInstanceOf[Option[Double]]
    } else None
  }

  def getReplanningReasonFrom(data: BasePersonData, prefix: String): String = {
    data.currentTourMode
      .collect { case mode =>
        s"$prefix $mode"
      }
      .getOrElse(prefix)
  }

  private def handleSuccessfulTransitReservation(
    triggersToSchedule: Vector[ScheduleTrigger]
  ): FSM.State[BeamAgentState, PersonData] = {
    val (tick, triggerId) = releaseTickAndTriggerId()
    log.debug("releasing tick {} and scheduling triggers from reservation responses: {}", tick, triggersToSchedule)
    scheduler ! CompletionNotice(triggerId, triggersToSchedule)
    goto(Waiting)
  }

  def handleBoardOrAlightOutOfPlace: State = {
    stash
    stay
  }

  val myUnhandled: StateFunction = {
    case Event(BeamAgentSchedulerTimer, _) =>
      // Put a breakpoint here to see an internal state of the actor
      log.debug(s"Received message from ${sender()}")
      stay
    case Event(IllegalTriggerGoToError(reason), _) =>
      stop(Failure(reason))
    case Event(Status.Failure(reason), _) =>
      stop(Failure(reason))
    case Event(StateTimeout, _) =>
      log.error("Events leading up to this point:\n\t" + getLog.mkString("\n\t"))
      stop(
        Failure(
          "Timeout - this probably means this agent was not getting a reply it was expecting."
        )
      )
    case Event(Finish, _) =>
      if (stateName == Moving) {
        log.warning(s"$id is still travelling at end of simulation.")
        log.warning(s"$id events leading up to this point:\n\t${getLog.mkString("\n\t")}")
      } else if (stateName == PerformingActivity) {
        logger.debug(s"$id is performing Activity at end of simulation")
        logger.warn("Performing Activity at end of simulation")
      } else {
        logger.warn(s"$id has received Finish while in state: $stateName, personId: $id")
      }
      stop
    case Event(TriggerWithId(_: BoardVehicleTrigger, _), _: ChoosesModeData) =>
      handleBoardOrAlightOutOfPlace
    case Event(TriggerWithId(_: AlightVehicleTrigger, _), _: ChoosesModeData) =>
      handleBoardOrAlightOutOfPlace
    case Event(
          TriggerWithId(BoardVehicleTrigger(_, vehicleId), triggerId),
          data: BasePersonData
        ) if data.currentVehicle.headOption.contains(vehicleId) =>
      log.debug("Person {} in state {} received Board for vehicle that he is already on, ignoring...", id, stateName)
      stay() replying CompletionNotice(triggerId, Vector())
    case Event(TriggerWithId(_: BoardVehicleTrigger, _), _: BasePersonData) =>
      handleBoardOrAlightOutOfPlace
    case Event(TriggerWithId(_: AlightVehicleTrigger, _), _: BasePersonData) =>
      handleBoardOrAlightOutOfPlace
    case Event(_: NotifyVehicleIdle, _) =>
      stay()
    case Event(TriggerWithId(_: RideHailResponseTrigger, triggerId), _) =>
      stay() replying CompletionNotice(triggerId)
    case ev @ Event(_: RideHailResponse, _) =>
      stop(Failure(s"Unexpected RideHailResponse from ${sender()}: $ev"))
    case Event(_: ParkingInquiryResponse, _) =>
      stop(Failure("Unexpected ParkingInquiryResponse"))
    case ev @ Event(_: StartingRefuelSession, _) =>
      log.debug("myUnhandled.StartingRefuelSession: {}", ev)
      stay()
    case ev @ Event(_: UnhandledVehicle, _) =>
      log.error("myUnhandled.UnhandledVehicle: {}", ev)
      stay()
    case ev @ Event(_: WaitingToCharge, _) =>
      log.debug("myUnhandled.WaitingInLine: {}", ev)
      stay()
    case ev @ Event(_: EndingRefuelSession, _) =>
      log.debug("myUnhandled.EndingRefuelSession: {}", ev)
      stay()
    case Event(e, s) =>
      log.warning("received unhandled request {} in state {}/{}", e, stateName, s)
      stay()
  }

  whenUnhandled(drivingBehavior.orElse(myUnhandled))

  override def logPrefix(): String = s"PersonAgent:$id "
}
