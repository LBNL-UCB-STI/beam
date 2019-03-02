package beam.agentsim.agents.household
import beam.agentsim.agents._
import beam.agentsim.agents.planning.{BeamPlan, Trip}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{EmbodyWithCurrentTravelTime, RoutingRequest}
import beam.router.{BeamRouter, BeamSkimmer, RouteHistory}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.CAV
import beam.sim.BeamServices
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Id
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle

import scala.collection.immutable.{List, Map}
import scala.collection.mutable
import scala.util.control.Breaks._

class FastHouseholdCAVScheduling(
  val household: Household,
  val householdVehicles: List[BeamVehicle],
  val timeWindow: Map[MobilityRequestTrait, Int],
  val stopSearchAfterXSolutions: Int = 100,
  val limitCavToXPersons: Int = 3,
  val beamServices: Option[BeamServices] = None
)(implicit val population: org.matsim.api.core.v01.population.Population) {

  val skimmer: BeamSkimmer = new BeamSkimmer(beamServices)
  import beam.agentsim.agents.memberships.Memberships.RankedGroup._

  import scala.collection.mutable.{ListBuffer => MListBuffer}
  private val householdPlans = household.members
    .take(limitCavToXPersons)
    .map(
      person => BeamPlan(person.getSelectedPlan)
    )

  // *******
  // Convert to CAV SCHEDULE
  // *******
  def getAllFeasibleCAVSchedules: List[List[CAVSchedule]] = {
    val output = mutable.ListBuffer[List[CAVSchedule]]()
    getAllFeasibleSchedules.foreach { x =>
      output.append(x.schedulesMap.map(y => CAVSchedule(y._2, y._1, 0)).toList)
    }
    output.toList
  }

  def getKBestCAVSchedules(k: Int): List[List[CAVSchedule]] = {
    List(getKBestSchedules(k).head.schedulesMap.map(y => CAVSchedule(y._2, y._1, 0)).toList)
  }

  def getBestCAVScheduleWithLongestChain: List[List[CAVSchedule]] = {
    List(getBestScheduleWithLongestChain.head.schedulesMap.map(y => CAVSchedule(y._2, y._1, 0)).toList)
  }
  // *******

  // get k lowest scored schedules
  def getKBestSchedules(k: Int): List[HouseholdSchedule] = {
    getAllFeasibleSchedules.sortBy(_.householdScheduleCost.totalTravelTime).take(k)
  }

  // ***
  def getBestScheduleWithLongestChain: List[HouseholdSchedule] = {
    val mapRank = getAllFeasibleSchedules.map(x => x -> x.schedulesMap.foldLeft(0)((a, b) => a + b._2.size)).toMap

    var output = List.empty[HouseholdSchedule]
    if (mapRank.nonEmpty) {
      val maxRank = mapRank.maxBy(_._2)._2
      output = mapRank
        .withFilter(_._2 == maxRank)
        .map(x => x._1)
        .toList
        .sortBy(_.householdScheduleCost.totalTravelTime)
        .take(1)
    }
    output
  }

  def getAllFeasibleSchedules: List[HouseholdSchedule] = {
    val householdTrips = HouseholdTrips.get(householdPlans, householdVehicles.size, timeWindow, skimmer)
    val cavVehicles = householdVehicles.filter(_.beamVehicleType.automationLevel > 3)
    val householdSchedules = mutable.ListBuffer.empty[HouseholdSchedule]
    if (householdTrips.nonEmpty && cavVehicles.nonEmpty) {
      val emptySchedule =
        HouseholdSchedule(cavVehicles.map(_ -> List(householdTrips.get.homePickup)).toMap, householdTrips.get)
      householdSchedules.append(emptySchedule)
      breakable {
        householdTrips.get.requests.foreach { requests =>
          val HouseholdSchedulesToAppend = mutable.ListBuffer.empty[HouseholdSchedule]
          val HouseholdSchedulesToDelete = mutable.ListBuffer.empty[HouseholdSchedule]
          householdSchedules.foreach { hhSchedule =>
            val newHouseholdSchedules = hhSchedule.check(requests)
            HouseholdSchedulesToAppend.appendAll(newHouseholdSchedules)
          }
          householdSchedules.--=(HouseholdSchedulesToDelete)
          householdSchedules.appendAll(HouseholdSchedulesToAppend)
          if (householdSchedules.size >= stopSearchAfterXSolutions) {
            break
          }
        }
      }
      householdSchedules.-=(emptySchedule)
    }
    householdSchedules.toList
  }

  case class HouseholdSchedule(
    schedulesMap: Map[BeamVehicle, List[MobilityRequest]],
    householdScheduleCost: HouseholdTrips
  ) {

    def check(requests: List[MobilityRequest]): List[HouseholdSchedule] = {
      val outHouseholdSchedule = MListBuffer.empty[HouseholdSchedule]
      breakable {
        for ((cav, schedule) <- schedulesMap.toArray.sortBy(_._2.size)(Ordering[Int].reverse)) {
          getScheduleOrNone(cav, schedule ++ requests, timeWindow) match {
            case Some(s) =>
              outHouseholdSchedule.append(s)
              break
            case None => // try the next cav
          }
        }
      }
      outHouseholdSchedule.toList
    }

    private def getScheduleOrNone(
      cav: BeamVehicle,
      requests: List[MobilityRequest],
      timeWindow: Map[MobilityRequestTrait, Int]
    ): Option[HouseholdSchedule] = {
      val sortedRequests = requests.filter(_.tag != Relocation).sortBy(_.time)
      val startRequest = sortedRequests.head
      var occupancy = 0

      val newHouseholdSchedule = MListBuffer(startRequest.copy())
      var newHouseholdScheduleCost = householdScheduleCost.copy()

      sortedRequests.drop(1).foreach { curReq =>
        if (occupancy > cav.beamVehicleType.seatingCapacity)
          return None
        val prevReq = newHouseholdSchedule.last
        val metric = skimmer.getTimeDistanceAndCost(
          prevReq.activity.getCoord,
          curReq.activity.getCoord,
          prevReq.time,
          BeamMode.CAR,
          BeamVehicleType.defaultCarBeamVehicleType.id
        )
        var serviceTime = prevReq.serviceTime + metric.timeAndCost.time.get
        val ubTime = curReq.time + timeWindow(curReq.tag)
        val lbTime = curReq.time - timeWindow(curReq.tag)
        if (curReq.isPickup) {
          if (serviceTime > ubTime || (occupancy != 0 && serviceTime < lbTime))
            return None
          else if (serviceTime >= lbTime && serviceTime <= ubTime) {
            serviceTime = if (serviceTime < curReq.time) curReq.time else serviceTime
          } else if (serviceTime < lbTime) {
            val relocationRequest = curReq.copy(
              person = None,
              time = prevReq.serviceTime,
              defaultMode = BeamMode.CAV,
              tag = Relocation,
              serviceTime = prevReq.serviceTime
            )
            newHouseholdSchedule.append(relocationRequest)
            serviceTime = curReq.time
          }
          newHouseholdSchedule.append(curReq.copy(serviceTime = serviceTime))
          occupancy += 1
        } else if (curReq.isDropoff) {
          if (serviceTime < lbTime || serviceTime > ubTime) {
            return None
          }
          val pickupReq = newHouseholdSchedule.filter(x => x.isPickup && x.person == curReq.person).last
          val cavTripTravelTime = serviceTime - pickupReq.time // it includes the waiting time
          val newTotalTravelTime = newHouseholdScheduleCost.totalTravelTime -
          newHouseholdScheduleCost.tripTravelTime(curReq.trip) + cavTripTravelTime
          if (newTotalTravelTime > newHouseholdScheduleCost.defaultTravelTime)
            return None
          newHouseholdScheduleCost = newHouseholdScheduleCost.copy(
            tripTravelTime = newHouseholdScheduleCost.tripTravelTime + (pickupReq.trip -> cavTripTravelTime),
            totalTravelTime = newTotalTravelTime
          )
          newHouseholdSchedule.append(curReq.copy(serviceTime = serviceTime, pickupRequest = Some(pickupReq)))
          occupancy -= 1
        }
      }

      Some(
        HouseholdSchedule(
          this.schedulesMap.filterKeys(_ != cav) + (cav -> newHouseholdSchedule.toList),
          newHouseholdScheduleCost
        )
      )
    }

    case class RouteOrEmbodyRequest(routeReq: Option[RoutingRequest], embodyReq: Option[EmbodyWithCurrentTravelTime])

    def toRoutingRequests(
      beamServices: BeamServices,
      transportNetwork: TransportNetwork,
      routeHistory: RouteHistory,
      cav: BeamVehicle
    ): (List[Option[RouteOrEmbodyRequest]], List[MobilityRequest]) = {
      var newMobilityRequests = List[MobilityRequest]()
      val requestList = (schedulesMap(cav).reverse :+ schedulesMap(cav).head).tail
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
                newMobilityRequests = newMobilityRequests :+ orig.copy(
                  routingRequestId = Some(routingRequest.requestId)
                )
                Some(RouteOrEmbodyRequest(Some(routingRequest), None))
            }
          }
        }
        .toList
      (requestList, newMobilityRequests)
    }

    override def toString: String = {
      schedulesMap.toSet
        .foldLeft(new StringBuilder) {
          case (output, (cav, schedules)) =>
            output.append(s"cavid: ${cav.id}\n")
            val outputBis = schedules.foldLeft(output) {
              case (outputBisBis, schedule) =>
                outputBisBis.append(s"\t$schedule\n")
            }
            outputBis
        }
        .insert(
          0,
          s"\nhid:None | tt:${householdScheduleCost.totalTravelTime} | tt0:${householdScheduleCost.defaultTravelTime}.\n"
        )
        .toString
    }
  }
}

case class HouseholdTrips(
  requests: List[List[MobilityRequest]],
  homePickup: MobilityRequest,
  defaultTravelTime: Int,
  tripTravelTime: Map[Trip, Int],
  totalTravelTime: Int
) {
  override def toString: String = requests.toString

}

object HouseholdTrips {

  def get(
    householdPlans: Seq[BeamPlan],
    householdNbOfVehicles: Int,
    timeWindow: Map[MobilityRequestTrait, Int],
    skim: BeamSkimmer
  ): Option[HouseholdTrips] = {
    val (requests, firstPickupOfTheDay, tripTravelTime, totTravelTime) =
      HouseholdTripsHelper.getListOfPickupsDropoffs(householdPlans, householdNbOfVehicles, skim)
    // Sum(tDi - tPi) <= Sum(tauDi - tauPi) + (alpha + beta)|R|/2
    val sumTimeWindows = requests.size * timeWindow.foldLeft(0)(_ + _._2)

    // adding a time window to the total travel time
    firstPickupOfTheDay map (
      homePickup =>
        HouseholdTrips(
          requests,
          homePickup.copy(person = None, tag = Init),
          totTravelTime + sumTimeWindows,
          tripTravelTime.toMap,
          totTravelTime
        )
    )
  }
}
