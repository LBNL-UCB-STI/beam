package beam.agentsim.agents.household
import beam.agentsim.agents._
import beam.agentsim.agents.planning.{BeamPlan, Trip}
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.router.BeamSkimmer
import beam.router.Modes.BeamMode
import org.matsim.households.Household

import scala.collection.immutable.{List, Map}
import scala.collection.mutable
import scala.util.control.Breaks._

class AdvancedHouseholdCAVScheduling(
  val household: Household,
  val householdVehicles: List[BeamVehicle],
  val timeWindow: Map[MobilityRequestTrait, Int],
  val stopSearchAfterXSolutions: Int = 100,
  val limitCavToXPersons: Int = 3
)(implicit val population: org.matsim.api.core.v01.population.Population, implicit val skimmer: BeamSkimmer) {
  import beam.agentsim.agents.memberships.Memberships.RankedGroup._

  import scala.collection.mutable.{ListBuffer => MListBuffer}
  private val householdPlans = household.members
    .take(limitCavToXPersons)
    .map(
      person => BeamPlan(person.getSelectedPlan)
    )

  def getAllFeasibleSchedules: List[HouseholdSchedule] = {
    val householdTrips = HouseholdTrips.get(householdPlans, householdVehicles.size, timeWindow, skimmer)
    val cavVehicles = householdVehicles.filter(_.beamVehicleType.automationLevel > 3)
    val householdSchedules = mutable.ListBuffer.empty[HouseholdSchedule]
    if (householdTrips.nonEmpty && cavVehicles.nonEmpty) {
      val emptySchedules = cavVehicles.map(_ -> List(householdTrips.get.homePickup)).toMap
      householdSchedules.append(HouseholdSchedule(emptySchedules, householdTrips.get))
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
    )(
      implicit skimmer: BeamSkimmer
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
