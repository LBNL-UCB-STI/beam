package beam.agentsim.agents.household
import beam.agentsim.agents.planning.{BeamPlan, Trip}
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.RoutingRequest
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{
  BIKE,
  CAR,
  CAV,
  DRIVE_TRANSIT,
  RIDE_HAIL,
  RIDE_HAIL_POOLED,
  RIDE_HAIL_TRANSIT,
  TRANSIT,
  WALK,
  WALK_TRANSIT
}
import beam.sim.BeamServices
import org.matsim.api.core.v01.population._
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle

import scala.collection.immutable.{List, Map}

sealed trait MobilityServiceRequestType
case object Pickup extends MobilityServiceRequestType
case object Dropoff extends MobilityServiceRequestType
case object Relocation extends MobilityServiceRequestType
case object Init extends MobilityServiceRequestType

case class MobilityServiceRequest(
  person: Option[Id[Person]],
  activity: Activity,
  time: Double,
  trip: Trip,
  defaultMode: BeamMode,
  tag: MobilityServiceRequestType,
  serviceTime: Double,
  routingRequestId: Option[Int] = None
) {
  val nextActivity = Some(trip.activity)

  def formatTime(secs: Double): String = {
    s"${(secs / 3600).toInt}:${((secs % 3600) / 60).toInt}:${(secs % 60).toInt}"
  }
  override def toString =
    s"${formatTime(time)}|$tag{${person.getOrElse("na")}|${activity.getType}| => ${formatTime(serviceTime)}}\n"
}

case class HouseholdTrips(
  requests: List[MobilityServiceRequest],
  defaultTravelTime: Double,
  tripTravelTime: Map[Trip, Double],
  totalTravelTime: Double
) {
  override def toString: String = requests.toString
}

object HouseholdTrips {

  def apply(
    householdPlans: Seq[BeamPlan],
    householdNbOfVehicles: Int,
    pickupTimeWindow: Double,
    dropoffTimeWindow: Double,
    skim: Map[BeamMode, Map[Coord, Map[Coord, Double]]]
  ): HouseholdTrips = {

    import scala.collection.mutable.{Map => MMap}
    import scala.collection.mutable.{ListBuffer => MListBuffer}

    val requests = MListBuffer.empty[MobilityServiceRequest]
    val tripTravelTime = MMap[Trip, Double]()

    val (_, totTravelTimeOut) = householdPlans.foldLeft(householdNbOfVehicles, 0.0) {
      case ((counter, totTravelTime), plan) => {
        val (totTravelTimeOutBis, usedCarOut) = plan.trips.sliding(2).foldLeft((totTravelTime, false)) {
          case ((totTravelTimeBis, usedCarBis), Seq(prevTrip, curTrip)) =>
            val legTrip = curTrip.leg
            val defaultMode = getDefaultMode(legTrip, counter)
            val travelTime = skim(defaultMode)(prevTrip.activity.getCoord)(curTrip.activity.getCoord)
            val pickups = MobilityServiceRequest(
              Some(plan.getPerson.getId),
              prevTrip.activity,
              prevTrip.activity.getEndTime,
              curTrip,
              defaultMode,
              Pickup,
              prevTrip.activity.getEndTime
            )
            val dropoffs = MobilityServiceRequest(
              Some(plan.getPerson.getId),
              curTrip.activity,
              prevTrip.activity.getEndTime + travelTime,
              curTrip,
              defaultMode,
              Dropoff,
              prevTrip.activity.getEndTime + travelTime
            )
            requests.prependAll(MListBuffer(pickups, dropoffs))
            tripTravelTime(curTrip) = travelTime
            (totTravelTimeBis + travelTime, if (defaultMode == BeamMode.CAR) true else usedCarBis)
        }
        (totTravelTimeOutBis, if (usedCarOut) counter - 1 else counter)
      }
      (counter, totTravelTime)
    }
    // Sum(tDi - tPi) <= Sum(tauDi - tauPi) + (alpha + beta)|R|/2
    val sumTimeWindows = (requests.size / 2) * (dropoffTimeWindow + pickupTimeWindow)
    // adding a time window to the total travel time
    HouseholdTrips(
      requests.sortWith(_.time < _.time).toList,
      totTravelTimeOut + sumTimeWindows,
      tripTravelTime.toMap,
      totTravelTimeOut
    )
  }

  def getDefaultMode(legOption: Option[Leg], nbVehicles: Int): BeamMode = {
    legOption
      .flatMap(leg => BeamMode.fromString(leg.getMode))
      .getOrElse(if (nbVehicles <= 0) BeamMode.TRANSIT else BeamMode.CAR)
  }
}

class HouseholdCAVScheduling(
  val population: org.matsim.api.core.v01.population.Population,
  val household: Household,
  val householdVehicles: List[BeamVehicle],
  val pickupTimeWindow: Double,
  val dropoffTimeWindow: Double,
  val skim: Map[BeamMode, Map[Coord, Map[Coord, Double]]]
) {

  // ***
  def getAllFeasibleSchedules: List[CAVFleetSchedule] = {
    import beam.agentsim.agents.memberships.Memberships.RankedGroup._
    implicit val pop: org.matsim.api.core.v01.population.Population = population
    val householdPlans = household.members.map(person => BeamPlan(person.getSelectedPlan))
    // extract potential household CAV requests from plans
    val householdRequests: HouseholdTrips =
      HouseholdTrips(householdPlans, householdVehicles.size, pickupTimeWindow, dropoffTimeWindow, skim)

    // initialize household schedule
    val cavVehicles = householdVehicles.filter(_.beamVehicleType.automationLevel > 3)
    import scala.collection.mutable.{ListBuffer => MListBuffer}
    val emptyFleetSchedule = MListBuffer.empty[CAVSchedule]
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
    val feasibleSchedules =
      MListBuffer[CAVFleetSchedule](CAVFleetSchedule(emptyFleetSchedule.toList, householdRequests))
    for (request  <- householdRequests.requests;
         schedule <- feasibleSchedules) {
      val (newSchedule, feasible) = schedule.check(request, skim)
      feasibleSchedules.prependAll(newSchedule) //.map(cavSched => cavSched.copy(cavFleetSchedule = cavSched.cavFleetSchedule.reverse)))
      if (!feasible) feasibleSchedules -= schedule
    }
    feasibleSchedules.toList
  }

  // ***
  // get k lowest scored schedules
  def getKBestSchedules(k: Int): List[CAVFleetSchedule] = {
    getAllFeasibleSchedules.sortBy(_.householdTrips.totalTravelTime).take(k)
  }

  // ***
  def getBestScheduleWithTheLongestCAVChain: CAVFleetSchedule = {
    val maprank =
      getAllFeasibleSchedules.map(x => x -> x.cavFleetSchedule.foldLeft(0)((a, b) => a + b.schedule.size)).toMap
    val maxrank = maprank.maxBy(_._2)._2
    maprank.filter(_._2 == maxrank).keys.toList.sortBy(_.householdTrips.totalTravelTime).take(1).head
  }

  // ***
  case class CAVFleetSchedule(cavFleetSchedule: List[CAVSchedule], householdTrips: HouseholdTrips) {

    // ***
    def check(
      request: MobilityServiceRequest,
      skim: Map[BeamMode, Map[Coord, Map[Coord, Double]]]
    ): (List[CAVFleetSchedule], Boolean) = {
      import scala.collection.mutable.{ListBuffer => MListBuffer}
      val newHouseholdSchedule = MListBuffer.empty[Option[CAVFleetSchedule]]
      val timeWindow = request.tag match {
        case Pickup  => pickupTimeWindow
        case Dropoff => dropoffTimeWindow
        case _       => 0
      }
      val feasibleOut = cavFleetSchedule.foldLeft(true) {
        case (feasible, cavSchedule) =>
          val (scheduleOption, trips, isFeasible) = cavSchedule.check(request, householdTrips, timeWindow, skim)
          newHouseholdSchedule.prepend(
            scheduleOption.map(
              schedule => CAVFleetSchedule(schedule :: cavFleetSchedule.filter(_ != cavSchedule), trips)
            )
          )
          feasible && isFeasible
      }
      (newHouseholdSchedule.flatten.toList, feasibleOut)
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

// **
class CAVSchedule(
  val schedule: List[MobilityServiceRequest],
  val cav: BeamVehicle,
  val occupancy: Int
) {

  def check(
    request: MobilityServiceRequest,
    householdTrips: HouseholdTrips,
    timeWindow: Double,
    skim: Map[BeamMode, Map[Coord, Map[Coord, Double]]]
  ): (Option[CAVSchedule], HouseholdTrips, Boolean) = {
    val travelTime = skim(BeamMode.CAR)(schedule.head.activity.getCoord)(request.activity.getCoord)
    val prevServiceTime = schedule.head.serviceTime
    val serviceTime = prevServiceTime + travelTime
    val upperBoundServiceTime = request.time + timeWindow
    val lowerBoundServiceTime = request.time - timeWindow
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
        // since the agent is already a passenger, such a schedule should be marked unfeasible
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
        // whether the passenger successfully got dropped of or not, the current schedule
        // should be marked unfeasible, since you wouldn't need a combination where a passenger
        // never get dropped of.
        feasible = false
      case _ => // no action
    }
    (newCavSchedule, newHouseholdTrips, feasible)
  }

  // ***
  def toRoutingRequests(beamServices: BeamServices): (List[Option[RoutingRequest]], CAVSchedule) = {
    var newMobilityRequests = List[MobilityServiceRequest]()
    val requestList = (schedule.reverse :+ schedule.head)
      .tail
      .sliding(2)
      .map { wayPoints =>
        val orig = wayPoints(0)
        val dest = wayPoints(1)
        val origin = SpaceTime(orig.activity.getCoord, math.round(orig.time).toInt)
        if (beamServices.geo.distUTMInMeters(orig.activity.getCoord, dest.activity.getCoord) < beamServices.beamConfig.beam.agentsim.thresholdForWalkingInMeters) {
          newMobilityRequests = newMobilityRequests :+ orig
          None
        } else {
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
          Some(routingRequest)
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

object HouseholdCAVScheduling {

  def computeSkim(
    plans: List[BeamPlan],
    avgSpeed: Map[BeamMode, Double]
  ): Map[BeamMode, Map[Coord, Map[Coord, Double]]] = {
    val activitySet: Set[Coord] = (for {
      plan <- plans
      act  <- plan.activities
    } yield act.getCoord).toSet

    val theModes =
      List(CAR, CAV, WALK, BIKE, WALK_TRANSIT, DRIVE_TRANSIT, RIDE_HAIL, RIDE_HAIL_POOLED, RIDE_HAIL_TRANSIT, TRANSIT)
    var skim: Map[BeamMode, Map[Coord, Map[Coord, Double]]] = Map()

    theModes.foreach { mode =>
      skim = skim + (mode -> Map())
    }
    for (src <- activitySet;
         dst <- activitySet) {
      val dist = beam.sim.common.GeoUtils.distFormula(src, dst)
      if (!skim(BeamMode.CAR).contains(src)) {
        theModes.foreach { mode =>
          val sourceToDestToDist = (src -> Map[Coord, Double]())
          skim = skim + (mode           -> (skim(mode) + sourceToDestToDist))
        }
      }
      theModes.foreach { mode =>
        val destToDist = skim(mode)(src) + (dst -> dist / avgSpeed(mode))
        val sourceToDestToDist = (src           -> destToDist)
        skim = skim + (mode                     -> (skim(mode) + sourceToDestToDist))
      }
    }
    skim
  }
}
