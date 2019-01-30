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
  tag: MobilityServiceRequestType,
  deltaTime: Double,
  nextActivity: Option[Activity] = None,
  routingRequestId: Option[Int] = None
                                 ) {
  def this(req: MobilityServiceRequest, deltaTime: Double) =
    this(req.person, req.activity, req.time, req.trip, req.tag, deltaTime)
  override def toString =
    s"$tag{ ${person match {
      case Some(x) => x.toString
      case None    => "NA"
    }}|${activity.getType}|${(time / 3600).toInt}:${((time % 3600) / 60).toInt}:${(time % 60).toInt}|delta:$deltaTime }"
}

case class HouseholdTrips(
  requests: List[MobilityServiceRequest],
  tripTravelTime: Map[Trip, Double],
  totalTravelTime: Double
) {
  override def toString = s"${requests}"
}

object HouseholdTrips {

  def getInstance(
    householdPlans: List[BeamPlan],
    householdNbOfVehicles: Int,
    skim: Map[BeamMode, Map[Coord, Map[Coord, Double]]]
  ): HouseholdTrips = {
    var requests = List[MobilityServiceRequest]()
    var cost: Double = 0
    var tripCost: Map[Trip, Double] = Map[Trip, Double]()

    householdPlans.foreach { plan =>
      var counter = householdNbOfVehicles
      var usedCar = false

      // iterating over trips and (1) compute cost (2) get pick up requests (3) get dropoff requests
      plan.trips.sliding(2).foreach { tripTuple =>
        val startActivity = tripTuple(0).activity
        val endActivity = tripTuple(1).activity
        val legTrip = tripTuple(1).leg
        val defaultMode = getDefaultMode(legTrip, counter)
        val defaultTravelTime = skim(defaultMode)(startActivity.getCoord)(endActivity.getCoord)

        defaultMode match {
          case BeamMode.CAR => usedCar = true
          case _            => None
        }

        // keep trip travel time in memory
        tripCost += (tripTuple(1) -> defaultTravelTime)

        // increment cost
        cost += defaultTravelTime

        // identifying pickups
        requests = new MobilityServiceRequest(
          Some(plan.getPerson.getId),
          startActivity,
          startActivity.getEndTime,
          tripTuple(1),
          Pickup,
          0.0
        ) :: requests

        // identifying dropoffs
        requests = new MobilityServiceRequest(
          Some(plan.getPerson.getId),
          endActivity,
          startActivity.getEndTime + skim(defaultMode)(startActivity.getCoord)(endActivity.getCoord),
          tripTuple(1),
          Dropoff,
          0.0
        ) :: requests
      }

      // one less available vehicle if it is used by a member of the household
      if (usedCar) counter -= 1
    }

    requests = requests.sortWith(_.time < _.time)
    HouseholdTrips(requests, tripCost, cost)
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
  val cavVehicles: List[BeamVehicle],
  val timeWindow: Double,
  val skim: Map[BeamMode, Map[Coord, Map[Coord, Double]]]
) {
  import beam.agentsim.agents.memberships.Memberships.RankedGroup._
  private implicit val pop: org.matsim.api.core.v01.population.Population = population
  private val householdPlans = household.members.map(person => BeamPlan(person.getSelectedPlan)).toList
  private var feasibleSchedules: List[CAVFleetSchedule] = List[CAVFleetSchedule]()

  // extract potential household CAV requests from plans
  private val householdRequests: HouseholdTrips = HouseholdTrips.getInstance(householdPlans, cavVehicles.size, skim)

  // **
  class CAVFleetSchedule(val cavFleetSchedule: List[CAVSchedule], val householdTrips: HouseholdTrips) {
    var feasible: Boolean = true

    def check(request: MobilityServiceRequest,skim:  Map[BeamMode, Map[Coord, Map[Coord, Double]]], timeWindow: Double): List[CAVFleetSchedule] = {
      var newHouseholdSchedule = List[CAVFleetSchedule]()
      for (cavSchedule <- cavFleetSchedule) {
        val result = cavSchedule.check(request, householdTrips, skim, timeWindow)
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
      var output = s"Household Schedule - COST: ${householdTrips.totalTravelTime}.\n"
      cavFleetSchedule.foreach { i =>
        output += s"${i}\n"
      }
      output
    }
  }

  // ******
  // get k lowest scored schedules
  def getKBestSchedules(k: Int): List[CAVFleetSchedule] = {

    // deploy the householdFleet or set up the initial household schedule
    var emptyFleetSchedule = List[CAVSchedule]()
    cavVehicles.foreach(
      veh => // initial Cost and Occupancy
        emptyFleetSchedule = new CAVSchedule(
          List[MobilityServiceRequest](
            new MobilityServiceRequest(
              None,
              householdRequests.requests.head.activity,
              householdRequests.requests.head.time - 1,
              householdRequests.requests.head.trip,
              Init,
              1
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
        householdSchedulesToAdd ++= schedule.check(request,skim,timeWindow)
        if (!schedule.feasible) {
          householdSchedulesToDelete = schedule :: householdSchedulesToDelete
        }
      }
      feasibleSchedules = householdSchedulesToAdd ++ feasibleSchedules.diff(householdSchedulesToDelete)
    }
    return feasibleSchedules.sortWith(_.householdTrips.totalTravelTime < _.householdTrips.totalTravelTime).take(k)
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
             skim:  Map[BeamMode, Map[Coord, Map[Coord, Double]]], timeWindow: Double
           ): (Option[CAVSchedule], HouseholdTrips) = {
    val travelTime = skim(BeamMode.CAR)(schedule.head.activity.getCoord)(request.activity.getCoord)
    val arrivalTime = schedule.head.time + schedule.head.deltaTime + travelTime
    val newDeltaTime = arrivalTime - request.time
    var newCavSchedule: Option[CAVSchedule] = None
    var newHouseholdTrips: HouseholdTrips = householdTrips
    request.tag match {
      case Pickup =>
        if (occupancy == 0 && newDeltaTime < -1 * timeWindow) {
          val relocationRequest = new MobilityServiceRequest(
            None,
            request.activity,
            request.time - 1,
            request.trip,
            Relocation,
            newDeltaTime
          )
          newCavSchedule = Some(
            new CAVSchedule(
              new MobilityServiceRequest(request, 0) :: relocationRequest :: schedule,
              cav,
              occupancy + 1
            )
          )
        } else if (occupancy < cav.beamVehicleType.seatingCapacity && math.abs(newDeltaTime) <= timeWindow) {
          newCavSchedule = Some(
            new CAVSchedule(new MobilityServiceRequest(request, newDeltaTime) :: schedule, cav, occupancy + 1)
          )
        } else {
          // Dead End, Not going down this branch
        }
      case Dropoff =>
        val index = schedule.indexWhere(_.person == request.person)
        if (index < 0 || schedule(index).tag != Pickup) {
          // Dead End, Not going down this branch
        } else if (math.abs(newDeltaTime) > timeWindow) {
          // Current Schedule unfeasible, to be marked for removal
          feasible = false
        } else {
          val cavTripTravelTime = request.time + request.deltaTime - schedule(index).time
          val newTotalTravelTime: Double = householdTrips.totalTravelTime - householdTrips.tripTravelTime(
            request.trip
          ) + cavTripTravelTime
          if (newTotalTravelTime < householdTrips.totalTravelTime) {
            newCavSchedule = Some(
              new CAVSchedule(new MobilityServiceRequest(request, newDeltaTime) :: schedule, cav, occupancy - 1)
            )
            newHouseholdTrips = new HouseholdTrips(
              List[MobilityServiceRequest](),
              householdTrips.tripTravelTime + (request.trip -> cavTripTravelTime),
              newTotalTravelTime
            )
          }
          feasible = false
        }
      case _ => // No Action
    }
    (newCavSchedule, newHouseholdTrips)
  }
  def toRoutingRequests(beamServices: BeamServices): (List[Option[RoutingRequest]], CAVSchedule) = {
    var newMobilityRequests = List[MobilityServiceRequest]()
    var requestList = schedule.sliding(2).filter(_.size>1).map{ wayPoints =>
      val orig = wayPoints(0)
      val dest = wayPoints(1)
      if(beamServices.geo.distUTMInMeters(orig.activity.getCoord,dest.activity.getCoord) < 50){
        // We ignore this in favor of creating a dummy car leg
        None
      }else{
        val origin = SpaceTime(orig.activity.getCoord,math.round(orig.activity.getEndTime).toInt)
        val routingRequest = RoutingRequest(
          orig.activity.getCoord,
          dest.activity.getCoord,
          origin.time,
          IndexedSeq(),
          IndexedSeq(StreetVehicle(Id.create(cav.id.toString,classOf[Vehicle]), cav.beamVehicleType.id,origin,CAV,true))
        )
        newMobilityRequests = newMobilityRequests :+ orig.copy(routingRequestId = Some(routingRequest.requestId))
        Some(routingRequest)
      }
    }.toList
    (requestList, new CAVSchedule(newMobilityRequests, cav, occupancy))
  }
  override def toString = {
    var output = s"\tcav-id:${cav.id} \n\t\t"
    schedule.sortWith(_.time < _.time).foreach { i =>
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
