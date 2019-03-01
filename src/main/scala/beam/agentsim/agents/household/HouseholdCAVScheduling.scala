package beam.agentsim.agents.household
import akka.actor.ActorRef
import beam.agentsim.agents.{MobilityRequest, _}
import beam.agentsim.agents.household.CAVSchedule.RouteOrEmbodyRequest
import beam.agentsim.agents.planning.{BeamPlan, Trip}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehiclePersonId}
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{EmbodyWithCurrentTravelTime, RoutingRequest}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{CAR, CAV}
import beam.router.{BeamRouter, BeamSkimmer, Modes, RouteHistory}
import beam.sim.BeamServices
import beam.utils.logging.ExponentialLoggerWrapperImpl
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population._
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle

import scala.collection.immutable.{List, Map}
import scala.collection.mutable

class HouseholdCAVScheduling(
  val scenario: org.matsim.api.core.v01.Scenario,
  val household: Household,
  val householdVehicles: List[BeamVehicle],
  val timeWindow: Map[MobilityRequestTrait, Int],
  val stopSearchAfterXSolutions: Int = 100,
  val limitCavToXPersons: Int = 3,
  val skim: BeamSkimmer
) {
  implicit val pop: org.matsim.api.core.v01.population.Population = scenario.getPopulation
  import beam.agentsim.agents.memberships.Memberships.RankedGroup._
  private val householdPlans =
    household.members.take(limitCavToXPersons).map(person => BeamPlan(person.getSelectedPlan))

  // ***
  def getAllFeasibleSchedules: List[CAVFleetSchedule] = {
    // extract potential household CAV requests from plans
    val householdRequests =
      HouseholdTripsRequests.get(householdPlans, householdVehicles.size, timeWindow, skim)

    // initialize household schedule
    val cavVehicles = householdVehicles.filter(_.beamVehicleType.automationLevel > 3)

    if (householdRequests.isEmpty || cavVehicles.isEmpty)
      return List[CAVFleetSchedule]()

    val emptySchedules = mutable.ListBuffer.empty[CAVSchedule]
    cavVehicles.foldLeft(()) {
      case (_, cav) =>
        emptySchedules.prepend(new CAVSchedule(List[MobilityRequest](householdRequests.get.homePickup), cav, 0))
    }

    // compute all the feasible schedules through
    var feasibleSchedulesOut = List.empty[CAVFleetSchedule]
    val feasibleSchedules =
      mutable.ListBuffer[CAVFleetSchedule](CAVFleetSchedule(emptySchedules.toList, householdRequests.get))
    for (request <- householdRequests.get.requests; schedule <- feasibleSchedules) {
      val (newSchedule, feasible) = schedule.check(request, skim)
      if (!feasible) feasibleSchedules -= schedule
      feasibleSchedules.prependAll(newSchedule)

      if (feasibleSchedules.size >= stopSearchAfterXSolutions) {
        // pruning incomplete schedules before returning the result
        return feasibleSchedules.filter(_.cavFleetSchedule.forall(_.schedule.head.tag == Dropoff)).toList
      }
    }
    feasibleSchedulesOut = feasibleSchedules.toList
    feasibleSchedules.toList
  }

  // ***
  // get k lowest scored schedules
  def getKBestSchedules(k: Int): List[CAVFleetSchedule] = {
    getAllFeasibleSchedules.sortBy(_.householdTrips.totalTravelTime).take(k)
  }

  // ***
  def getBestScheduleWithTheLongestCAVChain: List[CAVFleetSchedule] = {
    val mapRank =
      getAllFeasibleSchedules.map(x => x -> x.cavFleetSchedule.foldLeft(0)((a, b) => a + b.schedule.size)).toMap
    var output = List.empty[CAVFleetSchedule]
    if (mapRank.nonEmpty) {
      val maxRank = mapRank.maxBy(_._2)._2
      output =
        mapRank.withFilter(_._2 == maxRank).map(x => x._1).toList.sortBy(_.householdTrips.totalTravelTime).take(1)
    }
    output
  }

  // ***
  case class CAVFleetSchedule(cavFleetSchedule: List[CAVSchedule], householdTrips: HouseholdTripsRequests) {

    // ***
    def check(
      request: MobilityRequest,
      skim: BeamSkimmer
    ): (List[CAVFleetSchedule], Boolean) = {
      import scala.collection.mutable.{ListBuffer => MListBuffer}
      val outHouseholdSchedule = MListBuffer.empty[Option[CAVFleetSchedule]]
      var feasibleOut = cavFleetSchedule.foldLeft(true) {
        case (feasible, cavSchedule) =>
          val (scheduleOption, trips, isFeasible) = cavSchedule.check(request, householdTrips, timeWindow, skim)
          outHouseholdSchedule.prepend(
            scheduleOption.map(
              schedule => CAVFleetSchedule(schedule :: cavFleetSchedule.filter(_ != cavSchedule), trips)
            )
          )
          feasible && isFeasible
      }

      var finalHouseholdSchedule = outHouseholdSchedule.flatten

      if (CAVSchedule.isInChainMode(request)) {
        if (CAVSchedule.isWithinTourInAtLeastOneSchedule(request, cavFleetSchedule.map(_.schedule))) {
          // schedule to be marked unfeasible as the agent is using CAV in a chained tour
          // not marking the schedule unfeasible will allow future iteration that violates chain-based modes
          feasibleOut = false
          if (!finalHouseholdSchedule.flatMap(_.cavFleetSchedule).exists(_.schedule.head.person == request.person))
            // Dead end! chain-based mode not to be violated by any CAV of the household
            // agent did not get picked up by a CAV in the next trip of the same tour
            finalHouseholdSchedule = MListBuffer.empty[CAVFleetSchedule]
        } else {
          // Dead end! chain-based mode not to be violated by any CAV of the household
          // agent cannot be picked up by CAV if the tour already started without CAV in chained tours
          finalHouseholdSchedule = MListBuffer.empty[CAVFleetSchedule]
        }
      }

      (finalHouseholdSchedule.toList, feasibleOut)
    }
    // ***
    override def toString: String = {
      cavFleetSchedule
        .foldLeft(new StringBuilder) {
          case (output, cavSchedule) => output.insert(0, s"$cavSchedule\n")
        }
        .insert(
          0,
          s"HH|TT:${householdTrips.totalTravelTime}|TT0:${householdTrips.defaultTravelTime}.\n"
        )
        .toString
    }
  }
}

// ************
// ************
case class CAVSchedule(
  schedule: List[MobilityRequest],
  cav: BeamVehicle,
  occupancy: Int
) {

  def check(
    request: MobilityRequest,
    householdTrips: HouseholdTripsRequests,
    timeWindow: Map[MobilityRequestTrait, Int],
    skim: BeamSkimmer
  ): (Option[CAVSchedule], HouseholdTripsRequests, Boolean) = {
    val travelTime = skim
      .getTimeDistanceAndCost(
        schedule.head.activity.getCoord,
        request.activity.getCoord,
        request.time,
        CAR,
        cav.beamVehicleType.id
      )
      .timeAndCost
      .time
      .get
    val prevServiceTime = schedule.head.serviceTime
    val serviceTime = prevServiceTime + travelTime
    val upperBoundServiceTime = request.time + timeWindow(request.tag)
    val lowerBoundServiceTime = request.time - timeWindow(request.tag)
    val index = schedule.indexWhere(_.person == request.person)

    var newCavSchedule: Option[CAVSchedule] = None
    var newHouseholdTrips: HouseholdTripsRequests = householdTrips
    var feasible: Boolean = true

    request.tag match {
      case Pickup if occupancy == 0 && serviceTime <= upperBoundServiceTime =>
        // otherwise the empty cav arrives too late to pickup a passenger
        var newSchedule = schedule
        var newServiceTime = serviceTime
        if (serviceTime < request.time) {
          val relocationRequest = MobilityRequest(
            None,
            request.activity,
            prevServiceTime,
            request.trip,
            BeamMode.CAV,
            Relocation,
            prevServiceTime
          )
          newSchedule = relocationRequest :: newSchedule
          newServiceTime = request.time
        }
        newCavSchedule = Some(
          new CAVSchedule(
            request.copy(serviceTime = newServiceTime) :: newSchedule,
            cav,
            occupancy + 1
          )
        )
      case Pickup if occupancy != 0 && serviceTime >= lowerBoundServiceTime && serviceTime <= upperBoundServiceTime =>
        // otherwise the cav arrives either too early or too late to pickup another passenger
        val newServiceTime = if (serviceTime < request.time) request.time else serviceTime
        newCavSchedule = Some(
          new CAVSchedule(
            request.copy(serviceTime = newServiceTime) :: schedule,
            cav,
            occupancy + 1
          )
        )
      case Dropoff if index < 0 || schedule(index).tag != Pickup =>
      // cav cannot dropoff a non passenger
      case Dropoff if serviceTime < lowerBoundServiceTime || serviceTime > upperBoundServiceTime =>
        // cav arriving too early or too late to the dropoff time
        // since the agent is already a passenger, such a schedule to be marked unfeasible
        // to avoid the agent to be picked up in the first place
        feasible = false
      case Dropoff =>
        val cavTripTravelTime = serviceTime - schedule(index).time // it includes the waiting time
        val newTotalTravelTime = householdTrips.totalTravelTime - householdTrips.tripTravelTime(
          request.trip
        ) + cavTripTravelTime
        if (newTotalTravelTime < householdTrips.defaultTravelTime) {
          newCavSchedule = Some(
            new CAVSchedule(
              request.copy(serviceTime = serviceTime) :: schedule,
              cav,
              occupancy - 1
            )
          )
          newHouseholdTrips = householdTrips.copy(
            tripTravelTime = householdTrips.tripTravelTime + (request.trip -> cavTripTravelTime),
            totalTravelTime = newTotalTravelTime
          )
        }
        // whether the passenger successfully get dropped of or not, the current schedule
        // should be marked unfeasible, to avoid that the passenger never get dropped of.
        feasible = false
      case _ => // no action
    }
    (newCavSchedule, newHouseholdTrips, feasible)
  }

  // ***
  def toRoutingRequests(
    beamServices: BeamServices,
    transportNetwork: TransportNetwork,
    routeHistory: RouteHistory
  ): (List[Option[RouteOrEmbodyRequest]], CAVSchedule) = {
    var newMobilityRequests = List[MobilityRequest]()
    val requestList = (schedule.reverse :+ schedule.head).tail
      .sliding(2)
      .map { wayPoints =>
        val orig = wayPoints(0)
        val dest = wayPoints(1)
        val origin = SpaceTime(orig.activity.getCoord, Math.round(orig.time))
        if (beamServices.geo.distUTMInMeters(orig.activity.getCoord, dest.activity.getCoord) < beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters) {
          newMobilityRequests = newMobilityRequests :+ orig
          None
        } else {
          val theVehicle = StreetVehicle(
            Id.create(cav.id.toString, classOf[Vehicle]),
            cav.beamVehicleType.id,
            origin,
            CAV,
            asDriver = true
          )
          val origLink = beamServices.geo.getNearestR5Edge(
            transportNetwork.streetLayer,
            beamServices.geo.utm2Wgs(orig.activity.getCoord),
            10E3
          )
          val destLink = beamServices.geo.getNearestR5Edge(
            transportNetwork.streetLayer,
            beamServices.geo.utm2Wgs(dest.activity.getCoord),
            10E3
          )
          routeHistory.getRoute(origLink, destLink, orig.time) match {
            case Some(rememberedRoute) =>
              val embodyReq = BeamRouter.linkIdsToEmbodyRequest(
                rememberedRoute,
                theVehicle,
                origin.time,
                CAV,
                beamServices,
                orig.activity.getCoord,
                dest.activity.getCoord
              )
              newMobilityRequests = newMobilityRequests :+ orig.copy(routingRequestId = Some(embodyReq.requestId))
              Some(RouteOrEmbodyRequest(None, Some(embodyReq)))
            case None =>
              val routingRequest = RoutingRequest(
                orig.activity.getCoord,
                dest.activity.getCoord,
                origin.time,
                IndexedSeq(),
                IndexedSeq(
                  StreetVehicle(
                    Id.create(cav.id.toString, classOf[Vehicle]),
                    cav.beamVehicleType.id,
                    origin,
                    CAV,
                    asDriver = true
                  )
                )
              )
              newMobilityRequests = newMobilityRequests :+ orig.copy(routingRequestId = Some(routingRequest.requestId))
              Some(RouteOrEmbodyRequest(Some(routingRequest), None))
          }
        }
      }
      .toList
    (requestList, new CAVSchedule(newMobilityRequests, cav, occupancy))
  }
  // ***
  override def toString: String = {
    schedule
      .foldLeft(new StringBuilder) {
        case (output, request) => output.insert(0, "\t\t" + request.toString + "\n")
      }
      .insert(0, s"\tcav-id:${cav.id}\n")
      .toString
  }
}

object CAVSchedule {
  case class RouteOrEmbodyRequest(routeReq: Option[RoutingRequest], embodyReq: Option[EmbodyWithCurrentTravelTime])

  def isInChainMode(request: MobilityRequest): Boolean = {
    request.tag == Pickup && Modes.isChainBasedMode(request.defaultMode) && request.trip.parentTour.trips
      .indexOf(request.trip) > 0
  }

  def isWithinTour(request: MobilityRequest, schedule: List[MobilityRequest]): Boolean = {
    val index = schedule.indexWhere(_.person == request.person)
    index >= 0 && request.trip.parentTour == schedule(index).trip.parentTour
  }

  def isWithinTourInAtLeastOneSchedule(
    request: MobilityRequest,
    scheduleList: List[List[MobilityRequest]]
  ): Boolean = {
    for (schedule <- scheduleList)
      if (isWithinTour(request, schedule))
        return true
    false
  }

}

// Helper classes for convert Beam plans to MobilityServiceRequest
case class HouseholdTripsException(message: String, cause: Throwable = null) extends Exception(message, cause)
case class HouseholdTripsLogger(name: String) extends ExponentialLoggerWrapperImpl(name)

object HouseholdTripsHelper {

  import scala.collection.mutable.{ListBuffer => MListBuffer, Map => MMap}
  import scala.util.control.Breaks._
  val logger = HouseholdTripsLogger(getClass.getName)

  def getDefaultMode(legOption: Option[Leg], nbVehicles: Int): BeamMode = {
    legOption
      .flatMap(leg => BeamMode.fromString(leg.getMode))
      .getOrElse(if (nbVehicles <= 0) BeamMode.TRANSIT else BeamMode.CAR)
  }

  def getListOfPickupsDropoffs(
    householdPlans: Seq[BeamPlan],
    householdNbOfVehicles: Int,
    skim: BeamSkimmer
  ): (List[List[MobilityRequest]], Option[MobilityRequest], MMap[Trip, Int], Int) = {
    val requests = MListBuffer.empty[List[MobilityRequest]]
    val tours = MListBuffer.empty[MobilityRequest]
    val tripTravelTime = MMap[Trip, Int]()
    var totTravelTime = 0
    var firstPickupOfTheDay: Option[MobilityRequest] = None
    breakable {
      householdPlans.foldLeft(householdNbOfVehicles) {
        case (counter, plan) =>
          val usedCarOut = plan.trips.sliding(2).foldLeft(false) {
            case (usedCar, Seq(prevTrip, curTrip)) =>
              val (pickup, dropoff, travelTime) = getPickupAndDropoff(plan, curTrip, prevTrip, counter, skim)
              if (firstPickupOfTheDay.isEmpty || firstPickupOfTheDay.get.time > pickup.time)
                firstPickupOfTheDay = Some(pickup)
              tours.append(pickup)
              tours.append(dropoff)
              if (!Modes.isChainBasedMode(pickup.defaultMode) || tours.head.trip.parentTour != pickup.trip.parentTour) {
                requests.append(tours.toList)
                tours.clear()
              }
              tripTravelTime(curTrip) = travelTime
              totTravelTime += travelTime
              if (pickup.defaultMode == BeamMode.CAR) true else usedCar
          }
          requests.append(tours.toList)
          tours.clear()
          if (usedCarOut) counter - 1 else counter
      }
    }
    (requests.toList, firstPickupOfTheDay, tripTravelTime, totTravelTime)
  }

  def getPickupAndDropoff(
    plan: BeamPlan,
    curTrip: Trip,
    prevTrip: Trip,
    counter: Int,
    skim: BeamSkimmer
  ): (MobilityRequest, MobilityRequest, Int) = {
    val legTrip = curTrip.leg
    val defaultMode = getDefaultMode(legTrip, counter)
    val travelTime = skim
      .getTimeDistanceAndCost(
        prevTrip.activity.getCoord,
        curTrip.activity.getCoord,
        0,
        defaultMode,
        org.matsim.api.core.v01.Id.create[BeamVehicleType]("", classOf[BeamVehicleType])
      )
      .timeAndCost
      .time
      .get

    val startTime = prevTrip.activity.getEndTime.toInt
    val arrivalTime = startTime + travelTime

    val nextTripStartTime = curTrip.activity.getEndTime
    if (nextTripStartTime != Double.NegativeInfinity && startTime >= nextTripStartTime.toInt) {
      logger.warn(
        s"Illegal plan for person ${plan.getPerson.getId.toString}, activity ends at $startTime which is later than the next activity ending at $nextTripStartTime"
      )
      break
    } else if (nextTripStartTime != Double.NegativeInfinity && arrivalTime > nextTripStartTime.toInt) {
      logger.warn(
        "The necessary travel time to arrive to the next activity is beyond the end time of the same activity"
      )
      break
    }

    val vehiclePersonId =
      VehiclePersonId(Id.create(plan.getPerson.getId, classOf[Vehicle]), plan.getPerson.getId, ActorRef.noSender)

    val pickup = MobilityRequest(
      Some(vehiclePersonId),
      prevTrip.activity,
      startTime,
      curTrip,
      defaultMode,
      Pickup,
      startTime
    )
    val dropoff = MobilityRequest(
      Some(vehiclePersonId),
      curTrip.activity,
      arrivalTime,
      curTrip,
      defaultMode,
      Dropoff,
      arrivalTime,
      pickupRequest = Some(pickup)
    )
    (pickup, dropoff, travelTime)
  }
}

case class HouseholdTripsRequests(
  requests: List[MobilityRequest],
  homePickup: MobilityRequest,
  defaultTravelTime: Int,
  tripTravelTime: Map[Trip, Int],
  totalTravelTime: Int
) {
  override def toString: String = requests.toString
}

object HouseholdTripsRequests {

  def get(
    householdPlans: Seq[BeamPlan],
    householdNbOfVehicles: Int,
    timeWindow: Map[MobilityRequestTrait, Int],
    skim: BeamSkimmer
  ): Option[HouseholdTripsRequests] = {
    val (requests, firstPickupOfTheDay, tripTravelTime, totTravelTime) =
      HouseholdTripsHelper.getListOfPickupsDropoffs(householdPlans, householdNbOfVehicles, skim)
    // Sum(tDi - tPi) <= Sum(tauDi - tauPi) + (alpha + beta)|R|/2
    val sumTimeWindows = requests.size * timeWindow.foldLeft(0)(_ + _._2)
    // adding a time window to the total travel time
    firstPickupOfTheDay map (
      homePickup =>
        HouseholdTripsRequests(
          requests.flatten.sortWith(_.time < _.time),
          homePickup.copy(person = None, tag = Init),
          totTravelTime + sumTimeWindows,
          tripTravelTime.toMap,
          totTravelTime
        )
    )
  }
}
