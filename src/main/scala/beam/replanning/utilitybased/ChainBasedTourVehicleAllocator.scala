package beam.replanning.utilitybased

import java.util

import beam.agentsim.agents.memberships.HouseholdMembershipAllocator
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.router.Modes
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.CAR
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Leg, Person, Plan, Population}
import org.matsim.core.router.TripStructureUtils.Subtour
import org.matsim.core.router.{CompositeStageActivityTypes, TripStructureUtils}
import org.matsim.households.Household
import org.matsim.vehicles.{Vehicle, Vehicles}

import scala.collection.{JavaConverters, mutable}

case class ChainBasedTourVehicleAllocator(vehicles: Vehicles, householdMembershipAllocator: HouseholdMembershipAllocator) {

  import beam.agentsim.agents.memberships.Memberships.RankedGroup._


  val stageActivitytypes = new CompositeStageActivityTypes()

  // Delegation
  val householdMemberships: Map[Id[Person], Household] = householdMembershipAllocator.memberships

  // Delegation
  implicit val population: Population = householdMembershipAllocator.population


  def findChainBasedModesPerPerson(personId: Id[Person]): Vector[BeamMode] = {
    // Does person have access to chain-based modes at home for this plan?
    val household = householdMemberships(personId)
    // For now just cars
    JavaConverters.asScalaBuffer(household.getVehicleIds).map(vehId => vehicles.getVehicles.get(vehId)).filter(vehicle => vehicle.getType.getDescription.equals("Car") || vehicle.getType.getDescription.equals("SUV")).map(_ => CAR).toVector
  }



  private def getVehicularToursSortedByStartTime(household: Household) = {
    val householdPlans: Seq[Plan] = household.members.flatMap(member => JavaConverters.collectionAsScalaIterable(member.getPlans))
    val vehicularTrips = for {plan: Plan <- householdPlans
                              subtour: Subtour <- JavaConverters.collectionAsScalaIterable(TripStructureUtils.getSubtours(plan,
                                stageActivitytypes)) if
                              subtour.getParent != null} yield {
      val isFirstTrip: Boolean = true
      for {trip <- JavaConverters.collectionAsScalaIterable(subtour.getTrips)} {
        if (isFirstTrip && isChainBased(trip)) {
          trip
        }
      }
    }

  }


  private def isChainBased(t: TripStructureUtils.Trip): Boolean = {
    val legs: util.List[Leg] = t.getLegsOnly
    if (legs.isEmpty) return false
    // XXX what to do if several legs???
    val l: Leg = legs.get(0)
    if (!Modes.BeamMode.chainBasedModes.contains(BeamMode.withValue(l.getMode))) return false
    true
  }

}

object ChainBasedTourVehicleAllocator {

  final private val records = mutable.Map[Id[Vehicle], VehicleRecord]()


  case class VehicleRecord(id: Id[BeamVehicle], var nAllocs: Int = 0, var availableFrom: Double = Double.NegativeInfinity)

  case class SubtourRecord(startTime: Double, endTime: Double, possibleVehicles: Vector[VehicleRecord],
                           subtour: Subtour, allocatedVehicle: Id[Vehicle])

//  object SubtourRecord {
//    def apply(possibleVehicles: Vector[VehicleRecord], subtour: Subtour, allocatedVehicle: Id[Vehicle]): SubtourRecord = {
//      val trips: Iterable[TripStructureUtils.Trip] = JavaConverters.collectionAsScalaIterable(subtour.getTrips)
//
//    }
//  }
//
//  def getRecords(ids: util.Collection[Id[Vehicle]]): Seq[VehicleRecord] =
//    JavaConverters.collectionAsScalaIterable(ids).map(id => records.getOrElseUpdate(id, VehicleRecord(id))).toList
//

}





