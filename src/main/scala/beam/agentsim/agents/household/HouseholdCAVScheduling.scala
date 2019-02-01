package beam.agentsim.agents.household
import beam.agentsim.agents.planning.{BeamPlan, Trip}
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.RoutingRequest
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.CAV
import beam.sim.BeamServices
import org.matsim.api.core.v01.population._
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.geometry.CoordUtils
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
  nextActivity: Option[Activity] = None,
  routingRequestId: Option[Int] = None
) {
  def this(req: MobilityServiceRequest, serviceTime: Double) =
    this(req.person, req.activity, req.time, req.trip, req.defaultMode, req.tag, serviceTime, req.nextActivity)

  def formatTime(secs: Double): String = {
    (s"${(secs / 3600).toInt}:${((secs % 3600) / 60).toInt}:${(secs % 60).toInt}")
  }
  override def toString =
    s"$tag{ ${person match {
      case Some(x) => x.toString
      case None    => "NA"
    }}|${activity.getType}|${formatTime(time)}|serviceTime=>${formatTime(serviceTime)} }"
}

case class HouseholdTrips(
  requests: List[MobilityServiceRequest],
  defaultTravelTime: Double,
  tripTravelTime: Map[Trip, Double],
  totalTravelTime: Double
) {
  def this(hhTrips: HouseholdTrips, tripTT: Map[Trip, Double], totalTT: Double) =
    this(hhTrips.requests, hhTrips.defaultTravelTime, tripTT, totalTT)
  override def toString = s"${requests}"
}

object HouseholdTrips {

  def getInstance(
    householdPlans: List[BeamPlan],
    householdNbOfVehicles: Int,
    pickupTimeWindow: Double,
    dropoffTimeWindow: Double,
    skim: Map[BeamMode, Map[Coord, Map[Coord, Double]]]
  ): HouseholdTrips = {
    var requests = List[MobilityServiceRequest]()
    var totTravelTime: Double = 0
    var tripTravelTime: Map[Trip, Double] = Map[Trip, Double]()

    householdPlans.foreach { plan =>
      var counter = householdNbOfVehicles
      var usedCar = false

      // iterating over trips and (1) compute totTravelTime (2) get pick up requests (3) get dropoff requests
      plan.trips.sliding(2).foreach { tripTuple =>
        val startActivity = tripTuple(0).activity
        val endActivity = tripTuple(1).activity
        val legTrip = tripTuple(1).leg
        val defaultMode = getDefaultMode(legTrip, counter)
        val travelTime = skim(defaultMode)(startActivity.getCoord)(endActivity.getCoord)

        defaultMode match {
          case BeamMode.CAR => usedCar = true
          case _            => None
        }

        // keep trip travel time in memory
        tripTravelTime += (tripTuple(1) -> travelTime)

        // increment totTravelTime
        totTravelTime += travelTime

        // identifying pickups
        val pickupTime = startActivity.getEndTime
        requests = new MobilityServiceRequest(
          Some(plan.getPerson.getId),
          startActivity,
          pickupTime,
          tripTuple(1),
          defaultMode,
          Pickup,
          pickupTime,
          nextActivity = Some(endActivity)
        ) :: requests

        // identifying dropoffs
        val dropoffTime = startActivity.getEndTime + skim(defaultMode)(startActivity.getCoord)(endActivity.getCoord)
        requests = new MobilityServiceRequest(
          Some(plan.getPerson.getId),
          endActivity,
          dropoffTime,
          tripTuple(1),
          defaultMode,
          Dropoff,
          dropoffTime
        ) :: requests
      }

      // one less available vehicle if it is used by a member of the household
      if (usedCar) counter -= 1
    }

    // Sum(tDi - tPi) <= Sum(tauDi - tauPi) + (alpha + beta)|R|/2
    val sumTimeWindows = (requests.size / 2) * (dropoffTimeWindow + pickupTimeWindow)

    // adding a time window to the total travel time
    HouseholdTrips(requests.sortWith(_.time < _.time), totTravelTime + sumTimeWindows, tripTravelTime, totTravelTime)
  }

  def getDefaultMode(legOption: Option[Leg], nbVehicles: Int): BeamMode = {
    // TODO: delete the code if the simulation does not break due a None in Option[Leg]
    val beamModeOption = legOption match {
      case Some(leg) => BeamMode.fromString(leg.getMode)
      case None      => None
    }

    // If the mode is undefined and no available household is available, agent will be considered as using pt as a base mode
    // If the default mode is car and no available household is available, agent will be still be considered using car as a base mode
    // TODO: an agent cannot drive a car if it does not have a driving license
    val defaultMode = beamModeOption match {
      case Some(beamMode) => beamMode
      case None           => if (nbVehicles <= 0) BeamMode.TRANSIT else BeamMode.CAR
    }

    defaultMode
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
  import beam.agentsim.agents.memberships.Memberships.RankedGroup._
  private implicit val pop: org.matsim.api.core.v01.population.Population = population
  private val householdPlans = household.members.map(person => BeamPlan(person.getSelectedPlan)).toList
  private var feasibleSchedules: List[CAVFleetSchedule] = List[CAVFleetSchedule]()

  // extract potential household CAV requests from plans
  private val householdRequests: HouseholdTrips =
    HouseholdTrips.getInstance(householdPlans, householdVehicles.size, pickupTimeWindow, dropoffTimeWindow, skim)

  // **
  class CAVFleetSchedule(val cavFleetSchedule: List[CAVSchedule], val householdTrips: HouseholdTrips) {
    var feasible: Boolean = true

    def check(
      request: MobilityServiceRequest,
      skim: Map[BeamMode, Map[Coord, Map[Coord, Double]]]
    ): List[CAVFleetSchedule] = {
      var newHouseholdSchedule = List[CAVFleetSchedule]()
      for (cavSchedule <- cavFleetSchedule) {
        val timeWindow: Double = request.tag match {
          case Pickup  => pickupTimeWindow
          case Dropoff => dropoffTimeWindow
          //TODO case Dropoff => request.defaultMode match { case BeamMode.CAR => dropoffTimeWindow case _ => 0}
          case _ => 0
        }
        val result = cavSchedule.check(request, householdTrips, timeWindow, skim)
        result._1 match {
          case Some(x) =>
            newHouseholdSchedule = new CAVFleetSchedule(x :: (cavFleetSchedule.filter(_ != cavSchedule)), result._2) :: newHouseholdSchedule
          case None => //Nothing to do here
        }
        feasible = feasible && cavSchedule.feasible
      }
      newHouseholdSchedule
    }
    override def toString = {
      var output =
        s"Household Schedule | totalTT: ${householdTrips.totalTravelTime} | defaultTT: ${householdTrips.defaultTravelTime}.\n"
      cavFleetSchedule.foreach { i =>
        output += s"${i}\n"
      }
      output
    }
  }

  def getAllSchedules(): List[CAVFleetSchedule] = {
    // deploy the householdFleet or set up the initial household schedule
    var emptyFleetSchedule = List[CAVSchedule]()
    val cavVehicles = householdVehicles.filter(_.beamVehicleType.automationLevel>3)
    cavVehicles.foreach(
      veh => // initial Cost and Occupancy
        emptyFleetSchedule = new CAVSchedule(
          List[MobilityServiceRequest](
            new MobilityServiceRequest(
              None,
              householdRequests.requests.head.activity,
              householdRequests.requests.head.time,
              householdRequests.requests.head.trip,
              householdRequests.requests.head.defaultMode,
              Init,
              householdRequests.requests.head.time
            )
          ),
          veh,
          0
        ) :: emptyFleetSchedule
    )
    feasibleSchedules = new CAVFleetSchedule(emptyFleetSchedule, householdRequests) :: feasibleSchedules

    // extract all possible schedule combinations
    for (request <- householdRequests.requests) {
      var householdSchedulesToAdd = List[CAVFleetSchedule]()
      var householdSchedulesToDelete = List[CAVFleetSchedule]()
      for (schedule <- feasibleSchedules) {
        householdSchedulesToAdd ++= schedule.check(request, skim)
        if (!schedule.feasible) {
          householdSchedulesToDelete = schedule :: householdSchedulesToDelete
        }
      }
      feasibleSchedules = householdSchedulesToAdd ++ feasibleSchedules.diff(householdSchedulesToDelete)
    }
    return feasibleSchedules
  }

  // ******
  // get k lowest scored schedules
  def getKBestSchedules(k: Int): List[CAVFleetSchedule] = {
    return getAllSchedules().sortBy(_.householdTrips.totalTravelTime).take(k)
  }

  def getMostProductiveCAVSchedule(): CAVFleetSchedule = {
    val maprank = getAllSchedules().map(x => x -> x.cavFleetSchedule.foldLeft(0)((a, b) => a + b.schedule.size)).toMap
    val maxrank = maprank.maxBy(_._2)._2
    maprank.filter(_._2 == maxrank).keys.toList.sortBy(_.householdTrips.totalTravelTime).take(1)(0)
  }

}

// **
class CAVSchedule(
  val schedule: List[MobilityServiceRequest],
  val cav: BeamVehicle,
  val occupancy: Int
) {
  var feasible: Boolean = true

  def check(
    request: MobilityServiceRequest,
    householdTrips: HouseholdTrips,
    timeWindow: Double,
    skim: Map[BeamMode, Map[Coord, Map[Coord, Double]]]
  ): (Option[CAVSchedule], HouseholdTrips) = {
    val travelTime = skim(BeamMode.CAR)(schedule.head.activity.getCoord)(request.activity.getCoord)
    val prevServiceTime = schedule.head.serviceTime
    val serviceTime = prevServiceTime + travelTime
    val upperBoundServiceTime = request.time + timeWindow
    val lowerBoundServiceTime = request.time - timeWindow
    var newCavSchedule: Option[CAVSchedule] = None
    var newHouseholdTrips: HouseholdTrips = householdTrips
    request.tag match {
      case Pickup =>
        if (occupancy == 0) { // case of an empty cav
          if (serviceTime > upperBoundServiceTime) {
            // the empty cav arrives too late to pickup a passenger
          } else {
            var newSchedule = schedule
            var newServiceTime = serviceTime
            if (serviceTime < request.time) {
              val relocationRequest = new MobilityServiceRequest(
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
                new MobilityServiceRequest(request, newServiceTime) :: newSchedule,
                cav,
                occupancy + 1
              )
            )
          }
        } else {
          if (serviceTime < lowerBoundServiceTime || lowerBoundServiceTime > upperBoundServiceTime) {
            // the cav arrives either too early or too late to pickup another passenger
          } else {
            val newServiceTime = if (serviceTime < request.time) request.time else serviceTime
            newCavSchedule = Some(
              new CAVSchedule(
                new MobilityServiceRequest(request, newServiceTime) :: schedule,
                cav,
                occupancy + 1
              )
            )
          }
        }

      case Dropoff =>
        val index = schedule.indexWhere(_.person == request.person)
        if (index < 0 || schedule(index).tag != Pickup) {
          // cav cannot dropoff a non passenger
        } else if (serviceTime < lowerBoundServiceTime || serviceTime > upperBoundServiceTime) {
          // cav arriving too early or too late to the dropoff time
          // since the agent is already a passenger, such a schedule should be marked unfeasible
          // to avoid the agent to be picked up in the first place
          feasible = false
        } else {
          val cavTripTravelTime = serviceTime - schedule(index).time // it includes the waiting time
          val newTotalTravelTime = householdTrips.totalTravelTime - householdTrips.tripTravelTime(
            request.trip
          ) + cavTripTravelTime
          if (newTotalTravelTime < householdTrips.defaultTravelTime) {
            newCavSchedule = Some(
              new CAVSchedule(
                new MobilityServiceRequest(request, serviceTime) :: schedule,
                cav,
                occupancy - 1
              )
            )
            newHouseholdTrips = new HouseholdTrips(
              householdTrips,
              householdTrips.tripTravelTime + (request.trip -> cavTripTravelTime),
              newTotalTravelTime
            )
          }
          // whether the passenger successfully got dropped of or not, the current schedule
          // should be marked unfeasible, since you wouldn't need a combination where a passenger
          // never get dropped of.
          feasible = false
        }
      case _ => // no action
    }
    (newCavSchedule, newHouseholdTrips)
  }

  def toRoutingRequests(beamServices: BeamServices): (List[Option[RoutingRequest]], CAVSchedule) = {
    var newMobilityRequests = List[MobilityServiceRequest]()
    var requestList = schedule
      .sliding(2)
      .filter(_.size > 1)
      .map { wayPoints =>
        val orig = wayPoints(0)
        val dest = wayPoints(1)
        val origin = SpaceTime(orig.activity.getCoord, math.round(orig.activity.getEndTime).toInt)
        val routingRequest = RoutingRequest(
          orig.activity.getCoord,
          dest.activity.getCoord,
          origin.time,
          IndexedSeq(),
          IndexedSeq(
            StreetVehicle(Id.create(cav.id.toString, classOf[Vehicle]), cav.beamVehicleType.id, origin, CAV, true)
          )
        )
        newMobilityRequests = orig.copy(routingRequestId = Some(routingRequest.requestId)) +: newMobilityRequests
        Some(routingRequest)
      }
      .toList
    (requestList, new CAVSchedule(newMobilityRequests, cav, occupancy))
  }
  override def toString = {
    var output = s"\tcav-id:${cav.id} \n\t\t"
    schedule.foreach { i =>
      output += s"${i}\n\t\t"
    }
    output
  }
}

object HouseholdCAVScheduling {

  def computeSkim(
    plans: List[BeamPlan],
    avgSpeed: Map[BeamMode, Double]
  ): Map[BeamMode, Map[Coord, Map[Coord, Double]]] = {

    var skim: Map[BeamMode, Map[Coord, Map[Coord, Double]]] = Map(
      BeamMode.CAR     -> Map[Coord, Map[Coord, Double]](),
      BeamMode.TRANSIT -> Map[Coord, Map[Coord, Double]]()
    )

    var activitySet = Set[Coord]()
    for (plan <- plans) {
      for (act <- plan.activities) {
        activitySet += act.getCoord
      }
    }

    for (actSrc <- activitySet) {
      skim = Map(
        BeamMode.CAR     -> (skim(BeamMode.CAR) ++ Map(actSrc     -> Map[Coord, Double]())),
        BeamMode.TRANSIT -> (skim(BeamMode.TRANSIT) ++ Map(actSrc -> Map[Coord, Double]()))
      )
      for (actDst <- activitySet) {
        //TODO replace with BEAM GeoUtils
        val eucDistance: Double = CoordUtils.calcEuclideanDistance(actSrc, actDst)
        skim = Map(
          BeamMode.CAR -> (skim(BeamMode.CAR) ++ Map(
            actSrc -> (skim(BeamMode.CAR)(actSrc) ++ Map(actDst -> eucDistance / avgSpeed(BeamMode.CAR)))
          )),
          BeamMode.TRANSIT -> (skim(BeamMode.TRANSIT) ++ Map(
            actSrc -> (skim(BeamMode.TRANSIT)(actSrc) ++ Map(actDst -> eucDistance / avgSpeed(BeamMode.TRANSIT)))
          ))
        )
      }
    }
    skim
  }
}
