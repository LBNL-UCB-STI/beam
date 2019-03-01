package beam.agentsim.agents.household
import beam.agentsim.agents.household.CAVSchedule.RouteOrEmbodyRequest
import beam.agentsim.agents.planning.{BeamPlan, Trip}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
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
  val timeWindow: Map[MobilityServiceRequestType, Int],
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
    val householdRequests: HouseholdTrips =
      HouseholdTrips(householdPlans, householdVehicles.size, timeWindow, skim)

    // initialize household schedule
    val cavVehicles = householdVehicles.filter(_.beamVehicleType.automationLevel > 3)

    if (householdRequests.requests.isEmpty || cavVehicles.isEmpty)
      return List[CAVFleetSchedule]()

    val emptyFleetSchedule = mutable.ListBuffer.empty[CAVSchedule]
    cavVehicles.foldLeft(householdRequests.requests.head) {
      case (req, cav) =>
        emptyFleetSchedule.prepend(
          new CAVSchedule(
            List[MobilityServiceRequest](
              MobilityServiceRequest(
                None,
                req.activity,
                req.time,
                req.trip,
                req.defaultMode,
                Init,
                req.time
              )
            ),
            cav,
            0
          )
        )
        req
    }
    // compute all the feasible schedules through
    var feasibleSchedulesOut = List.empty[CAVFleetSchedule]
    val feasibleSchedules =
      mutable.ListBuffer[CAVFleetSchedule](CAVFleetSchedule(emptyFleetSchedule.toList, householdRequests))
    for (request <- householdRequests.requests; schedule <- feasibleSchedules) {
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
  case class CAVFleetSchedule(cavFleetSchedule: List[CAVSchedule], householdTrips: HouseholdTrips) {

    // ***
    def check(
      request: MobilityServiceRequest,
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
  schedule: List[MobilityServiceRequest],
  cav: BeamVehicle,
  occupancy: Int
) {

  def check(
    request: MobilityServiceRequest,
    householdTrips: HouseholdTrips,
    timeWindow: Map[MobilityServiceRequestType, Int],
    skim: BeamSkimmer
  ): (Option[CAVSchedule], HouseholdTrips, Boolean) = {
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
    var newHouseholdTrips: HouseholdTrips = householdTrips
    var feasible: Boolean = true

    request.tag match {
      case Pickup if occupancy == 0 && serviceTime <= upperBoundServiceTime =>
        // otherwise the empty cav arrives too late to pickup a passenger
        var newSchedule = schedule
        var newServiceTime = serviceTime
        if (serviceTime < request.time) {
          val relocationRequest = MobilityServiceRequest(
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
    var newMobilityRequests = List[MobilityServiceRequest]()
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

  def isInChainMode(request: MobilityServiceRequest): Boolean = {
    request.tag == Pickup && Modes.isChainBasedMode(request.defaultMode) && request.trip.parentTour.trips
      .indexOf(request.trip) > 0
  }

  def isWithinTour(request: MobilityServiceRequest, schedule: List[MobilityServiceRequest]): Boolean = {
    val index = schedule.indexWhere(_.person == request.person)
    index >= 0 && request.trip.parentTour == schedule(index).trip.parentTour
  }

  def isWithinTourInAtLeastOneSchedule(
    request: MobilityServiceRequest,
    scheduleList: List[List[MobilityServiceRequest]]
  ): Boolean = {
    for (schedule <- scheduleList)
      if (isWithinTour(request, schedule))
        return true
    false
  }
}

// **** Data Structure
sealed trait MobilityServiceRequestType
case object Pickup extends MobilityServiceRequestType
case object Dropoff extends MobilityServiceRequestType
case object Relocation extends MobilityServiceRequestType
case object Init extends MobilityServiceRequestType

case class MobilityServiceRequest(
  person: Option[Id[Person]],
  activity: Activity,
  time: Int,
  trip: Trip,
  defaultMode: BeamMode,
  tag: MobilityServiceRequestType,
  serviceTime: Int,
  routingRequestId: Option[Int] = None,
  pickupRequest: Option[MobilityServiceRequest] = None
) {
  val nextActivity = Some(trip.activity)

  def formatTime(secs: Int): String = {
    s"${secs / 3600}:${(secs % 3600) / 60}:${secs % 60}"
  }
  override def toString =
    s"${formatTime(time)}|$tag{${person.getOrElse("na")}|${activity.getType}| => ${formatTime(serviceTime)}}"
}

// Helper classes for convert Beam plans to MobilityServiceRequest
case class HouseholdTripsException(message: String, cause: Throwable = null) extends Exception(message, cause)
case class HouseholdTripsLogger(name: String) extends ExponentialLoggerWrapperImpl(name)
case class HouseholdTrips(
  requests: List[MobilityServiceRequest],
  defaultTravelTime: Int,
  tripTravelTime: Map[Trip, Int],
  totalTravelTime: Int
) {
  override def toString: String = requests.toString
}

object HouseholdTrips {

  def apply(
    householdPlans: Seq[BeamPlan],
    householdNbOfVehicles: Int,
    timeWindow: Map[MobilityServiceRequestType, Int],
    skim: BeamSkimmer
  ): HouseholdTrips = {

    import scala.collection.mutable.{ListBuffer => MListBuffer, Map => MMap}

    val logger = new HouseholdTripsLogger(getClass.getName)

    val requests = MListBuffer.empty[MobilityServiceRequest]
    val tripTravelTime = MMap[Trip, Int]()
    var totTravelTime = 0
    import scala.util.control.Breaks._
    breakable {
      householdPlans.foldLeft(householdNbOfVehicles) {
        case (counter, plan) =>
          val usedCarOut = plan.trips.sliding(2).foldLeft(false) {
            case (usedCar, Seq(prevTrip, curTrip)) =>
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
              if (nextTripStartTime != Double.NegativeInfinity) {
                if (startTime >= nextTripStartTime.toInt) {
                  throw HouseholdTripsException(
                    s"Illegal plan for person ${plan.getPerson.getId.toString}, activity ends at $startTime which is later than the next activity ending at $nextTripStartTime"
                  )
                } else if (arrivalTime > nextTripStartTime.toInt) {
                  logger.warn(
                    "The necessary travel time to arrive to the next activity is beyond the end time of the same activity"
                  )
                  break
                }
              }

              val pickup = MobilityServiceRequest(
                Some(plan.getPerson.getId),
                prevTrip.activity,
                startTime,
                curTrip,
                defaultMode,
                Pickup,
                startTime
              )
              val dropoff = MobilityServiceRequest(
                Some(plan.getPerson.getId),
                curTrip.activity,
                arrivalTime,
                curTrip,
                defaultMode,
                Dropoff,
                arrivalTime,
                pickupRequest = Some(pickup)
              )
              requests.prependAll(MListBuffer(pickup, dropoff))
              tripTravelTime(curTrip) = travelTime
              totTravelTime += travelTime
              if (defaultMode == BeamMode.CAR) true else usedCar
          }
          if (usedCarOut) counter - 1 else counter
      }
    }
    // Sum(tDi - tPi) <= Sum(tauDi - tauPi) + (alpha + beta)|R|/2
    val sumTimeWindows = (requests.size / 2) * timeWindow.foldLeft(0)(_ + _._2)
    // adding a time window to the total travel time
    HouseholdTrips(
      requests.sortWith(_.time < _.time).toList,
      totTravelTime + sumTimeWindows,
      tripTravelTime.toMap,
      totTravelTime
    )
  }

  def getDefaultMode(legOption: Option[Leg], nbVehicles: Int): BeamMode = {
    legOption
      .flatMap(leg => BeamMode.fromString(leg.getMode))
      .getOrElse(if (nbVehicles <= 0) BeamMode.TRANSIT else BeamMode.CAR)
  }
}
