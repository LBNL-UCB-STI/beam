package beam.agentsim.agents.household
import akka.actor.ActorRef
import beam.agentsim.agents._
import beam.agentsim.agents.household.CAVSchedule.RouteOrEmbodyRequest
import beam.agentsim.agents.planning.{BeamPlan, Trip}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, PersonIdWithActorRef}
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{EmbodyWithCurrentTravelTime, RoutingRequest}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.CAV
import beam.router.{BeamRouter, BeamSkimmer, Modes, RouteHistory}
import beam.sim.BeamServices
import beam.utils.logging.ExponentialLoggerWrapperImpl
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Leg, Person}
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.immutable.{List, Map}
import scala.collection.mutable
import scala.util.control.Breaks._

class FastHouseholdCAVScheduling(
  val household: Household,
  val householdVehicles: List[BeamVehicle],
  val skimmer: BeamSkimmer,
  val beamServices: Option[BeamServices] = None
)(implicit val population: org.matsim.api.core.v01.population.Population) {

  var waitingTimeInSec: Int = 5 * 60
  var delayToArrivalInSec: Int = waitingTimeInSec + waitingTimeInSec
  var stopSearchAfterXSolutions: Int = 100
  var limitCavToXPersons: Int = 3

  import scala.collection.mutable.{ListBuffer => MListBuffer}

  def getKLowestSumOfDelaysSchedules(k: Int): List[List[CAVSchedule]] = {
    getAllFeasibleSchedules
      .sortBy(_.householdScheduleCost.sumOfDelays.foldLeft(0)(_ + _._2))
      .groupBy(_.householdScheduleCost.sumOfDelays.foldLeft(0)(_ + _._2))
      .mapValues(_.maxBy(_.schedulesMap.foldLeft(0)((a, b) => a + b._2.schedule.size)))
      .toList
      .sortBy(_._1)
      .map(_._2)
      .take(k)
      .map(_.schedulesMap.values.filter(_.schedule.size > 1).toList)
  }

  // get k lowest scored schedules
  def getKBestSchedules(k: Int): List[List[CAVSchedule]] = {
    getAllFeasibleSchedules
      .sortBy(_.householdScheduleCost.totalTravelTime)
      .groupBy(_.householdScheduleCost.totalTravelTime)
      .mapValues(_.maxBy(_.schedulesMap.foldLeft(0)((a, b) => a + b._2.schedule.size)))
      .toList
      .sortBy(_._1)
      .map(_._2)
      .take(k)
      .map(_.schedulesMap.values.filter(_.schedule.size > 1).toList)
  }

  // ***
  def getBestProductiveSchedule: List[CAVSchedule] = {
    val mapRank =
      getAllFeasibleSchedules.map(x => x -> x.schedulesMap.foldLeft(0)((a, b) => a + b._2.schedule.size)).toMap
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
    output.headOption.map(_.schedulesMap.values.filter(_.schedule.size > 1).toList).getOrElse(List.empty)
  }

  def getAllFeasibleSchedules: List[HouseholdSchedule] = {
    HouseholdTrips.get(
      household,
      householdVehicles,
      householdVehicles.size,
      skimmer,
      waitingTimeInSec,
      delayToArrivalInSec,
      limitCavToXPersons
    ) match {
      case Some(householdTrips) if householdTrips.cavVehicles.nonEmpty =>
        val householdSchedules = mutable.ListBuffer.empty[HouseholdSchedule]
        val emptySchedule =
          HouseholdSchedule(
            householdTrips.cavVehicles.map(cav => cav -> CAVSchedule(List(householdTrips.homePickup), cav, 0)).toMap,
            householdTrips
          )
        householdSchedules.append(emptySchedule)
        breakable {
          householdTrips.requests.foreach { requests =>
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
        //        beam.sandbox.CavRun
        //          .perfSchedules(householdSchedules.map(x => (x.schedulesMap.values.toList, x.householdScheduleCost)).toList)
        householdSchedules.toList
      case _ => List.empty[HouseholdSchedule]
    }
  }

  case class HouseholdSchedule(
    schedulesMap: Map[BeamVehicle, CAVSchedule],
    householdScheduleCost: HouseholdTrips
  ) {

    def check(requests: List[MobilityRequest]): List[HouseholdSchedule] = {
      val outHouseholdSchedule = MListBuffer.empty[HouseholdSchedule]
      breakable {
        for ((cav, cavSchedule) <- schedulesMap.toArray.sortBy(_._2.schedule.size)(Ordering[Int].reverse)) {
          // prioritizing CAVs with high usage
          getScheduleOrNone(cav, cavSchedule, requests) match {
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
      cavSchedule: CAVSchedule,
      requests: List[MobilityRequest]
    ): Option[HouseholdSchedule] = {
      if (cavSchedule.occupancy >= cav.beamVehicleType.seatingCapacity)
        return None

      val sortedRequests =
        (cavSchedule.schedule ++ requests).filter(_.tag != Relocation).sortBy(_.baselineNonPooledTime)
      val startRequest = sortedRequests.head
      val newHouseholdSchedule = MListBuffer(startRequest.copy())
      var newHouseholdScheduleCost = householdScheduleCost.copy()
      var newOccupancy: Int = cavSchedule.occupancy

      sortedRequests.drop(1).foreach { curReq =>
        val prevReq = newHouseholdSchedule.last
        val metric = skimmer.getTimeDistanceAndCost(
          prevReq.activity.getCoord,
          curReq.activity.getCoord,
          prevReq.baselineNonPooledTime,
          BeamMode.CAR,
          cav.beamVehicleType.id
        )
        var serviceTime = prevReq.serviceTime + metric.time
        val ubTime = curReq.upperBoundTime
        val lbTime = curReq.baselineNonPooledTime - (curReq.upperBoundTime - curReq.baselineNonPooledTime)
        if (curReq.isPickup) {
          if (serviceTime > ubTime || (newOccupancy != 0 && serviceTime < lbTime))
            return None
          else if (serviceTime >= lbTime && serviceTime <= ubTime) {
            serviceTime = if (serviceTime < curReq.baselineNonPooledTime) curReq.baselineNonPooledTime else serviceTime
          } else if (serviceTime < lbTime) {
            val relocationRequest = curReq.copy(
              person = None,
              baselineNonPooledTime = prevReq.serviceTime,
              defaultMode = BeamMode.CAV,
              tag = Relocation,
              serviceTime = prevReq.serviceTime
            )
            newHouseholdSchedule.append(relocationRequest)
            serviceTime = curReq.baselineNonPooledTime
          }
          newOccupancy += 1
          newHouseholdSchedule.append(curReq.copy(serviceTime = serviceTime, vehicleOccupancy = Some(newOccupancy)))
        } else if (curReq.isDropoff) {
          if (serviceTime < lbTime || serviceTime > ubTime) {
            return None
          }
          val index = newHouseholdSchedule.lastIndexWhere(_.trip == curReq.trip)
          val pickupReq = newHouseholdSchedule.apply(index)
          newOccupancy -= 1
          newHouseholdSchedule.append(
            curReq
              .copy(serviceTime = serviceTime, pickupRequest = Some(pickupReq), vehicleOccupancy = Some(newOccupancy))
          )
          // it includes the waiting time
          val cavTripTravelTime = computeSharedTravelTime(newHouseholdSchedule.slice(index, newHouseholdSchedule.size))
          val newTotalTravelTime = newHouseholdScheduleCost.totalTravelTime -
          newHouseholdScheduleCost.tripTravelTime(curReq.trip) + cavTripTravelTime
          if (newTotalTravelTime > newHouseholdScheduleCost.baseTotalTravelTime)
            return None
          val sumOfDelays = (pickupReq.serviceTime - pickupReq.baselineNonPooledTime) + (serviceTime - curReq.baselineNonPooledTime)
          newHouseholdScheduleCost = newHouseholdScheduleCost.copy(
            tripTravelTime = newHouseholdScheduleCost.tripTravelTime + (curReq.trip -> cavTripTravelTime),
            totalTravelTime = newTotalTravelTime,
            sumOfDelays = newHouseholdScheduleCost.sumOfDelays +
            (curReq.person.get.personId -> (sumOfDelays + newHouseholdScheduleCost
              .sumOfDelays(curReq.person.get.personId)))
          )
        }
      }
      Some(
        HouseholdSchedule(
          this.schedulesMap.filterKeys(_ != cav) + (cav -> CAVSchedule(newHouseholdSchedule.toList, cav, newOccupancy)),
          newHouseholdScheduleCost
        )
      )
    }

    private def computeSharedTravelTime(requestsSeq: MListBuffer[MobilityRequest]): Int = {
      val waitTime = requestsSeq.head.serviceTime - requestsSeq.head.baselineNonPooledTime
      requestsSeq.filter(x => x.isPickup || x.isDropoff).sliding(2).foldLeft(waitTime) {
        case (acc, Seq(prevReq, nextReq)) =>
          acc + ((nextReq.serviceTime - prevReq.serviceTime) / prevReq.vehicleOccupancy.getOrElse(1))
      }
    }

    override def toString: String = {
      schedulesMap.toSet
        .foldLeft(new StringBuilder) {
          case (output, (cav, schedules)) =>
            output.append(s"cavid: ${cav.id}\n")
            val outputBis = schedules.schedule.foldLeft(output) {
              case (outputBisBis, schedule) =>
                outputBisBis.append(s"\t$schedule\n")
            }
            outputBis
        }
        .insert(
          0,
          s"\nhid:None | tt:${householdScheduleCost.totalTravelTime} | base-tt:${householdScheduleCost.baseTotalTravelTime}.\n"
        )
        .toString
    }
  }
}

case class CAVSchedule(schedule: List[MobilityRequest], cav: BeamVehicle, occupancy: Int) {

  def toRoutingRequests(
    beamServices: BeamServices,
    transportNetwork: TransportNetwork,
    routeHistory: RouteHistory
  ): (List[Option[RouteOrEmbodyRequest]], CAVSchedule) = {
    var newMobilityRequests = List[MobilityRequest]()
    val requestList = (schedule.tail :+ schedule.head)
      .sliding(2)
      .map { wayPoints =>
        val orig = wayPoints(0)
        val dest = wayPoints(1)
        val origin = SpaceTime(orig.activity.getCoord, Math.round(orig.baselineNonPooledTime))
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
          routeHistory.getRoute(origLink, destLink, orig.baselineNonPooledTime) match {
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
                withTransit = false,
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
    (requestList, CAVSchedule(newMobilityRequests, cav, occupancy))
  }

}

object CAVSchedule {
  case class RouteOrEmbodyRequest(routeReq: Option[RoutingRequest], embodyReq: Option[EmbodyWithCurrentTravelTime])
}

case class HouseholdTrips(
  household: Household,
  requests: List[List[MobilityRequest]],
  cavVehicles: List[BeamVehicle],
  homePickup: MobilityRequest,
  baseTotalTravelTime: Int,
  tripTravelTime: Map[Trip, Int],
  totalTravelTime: Int,
  sumOfDelays: Map[Id[Person], Int]
) {
  override def toString: String = requests.toString
}

object HouseholdTrips {

  def get(
    household: Household,
    householdVehicles: List[BeamVehicle],
    householdNbOfVehicles: Int,
    skim: BeamSkimmer,
    waitingTimeInSec: Int,
    delayToArrivalInSec: Int,
    limitCavToXPersons: Int
  )(implicit population: org.matsim.api.core.v01.population.Population): Option[HouseholdTrips] = {
    import beam.agentsim.agents.memberships.Memberships.RankedGroup._
    val householdPlans = household.members
      .take(limitCavToXPersons)
      .map(
        person => BeamPlan(person.getSelectedPlan)
      )
    val cavVehicles = householdVehicles.filter(_.beamVehicleType.automationLevel > 3)
    val vehicleTypeForSkimmer = cavVehicles.head.beamVehicleType // FIXME I need _one_ vehicleType here, but there could be more..
    val (requests, firstPickupOfTheDay, tripTravelTime, totTravelTime) =
      HouseholdTripsHelper.getListOfPickupsDropoffs(
        householdPlans,
        householdNbOfVehicles,
        skim,
        vehicleTypeForSkimmer,
        waitingTimeInSec,
        delayToArrivalInSec
      )
    firstPickupOfTheDay map (
      homePickup =>
        HouseholdTrips(
          household,
          requests,
          cavVehicles,
          homePickup.copy(person = None, tag = Init),
          totTravelTime,
          tripTravelTime.toMap,
          totTravelTime,
          household.getMemberIds.asScala.map(_ -> 0).toMap
        )
    )
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
    skim: BeamSkimmer,
    beamVehicleType: BeamVehicleType,
    waitingTimeInSec: Int,
    delayToArrivalInSec: Int
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
              val (pickup, dropoff, travelTime) =
                getPickupAndDropoff(
                  plan,
                  curTrip,
                  prevTrip,
                  counter,
                  skim,
                  beamVehicleType,
                  waitingTimeInSec,
                  delayToArrivalInSec
                )
              if (firstPickupOfTheDay.isEmpty || firstPickupOfTheDay.get.baselineNonPooledTime > pickup.baselineNonPooledTime)
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
    skimmer: BeamSkimmer,
    beamVehicleType: BeamVehicleType,
    waitingTimeInSec: Int,
    delayToArrivalInSec: Int
  ): (MobilityRequest, MobilityRequest, Int) = {
    val legTrip = curTrip.leg
    val defaultMode = getDefaultMode(legTrip, counter)
    val skim = skimmer
      .getTimeDistanceAndCost(
        prevTrip.activity.getCoord,
        curTrip.activity.getCoord,
        0,
        defaultMode,
        beamVehicleType.id
      )

    val startTime = prevTrip.activity.getEndTime.toInt
    val arrivalTime = startTime + skim.time

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
      PersonIdWithActorRef(plan.getPerson.getId, ActorRef.noSender)

    val pickup = MobilityRequest(
      Some(vehiclePersonId),
      prevTrip.activity,
      startTime,
      curTrip,
      defaultMode,
      Pickup,
      startTime,
      startTime + waitingTimeInSec,
      0
    )
    val dropoff = MobilityRequest(
      Some(vehiclePersonId),
      curTrip.activity,
      arrivalTime,
      curTrip,
      defaultMode,
      Dropoff,
      arrivalTime,
      arrivalTime + delayToArrivalInSec,
      skim.distance.toInt,
      pickupRequest = Some(pickup)
    )
    (pickup, dropoff, skim.time)
  }
}
