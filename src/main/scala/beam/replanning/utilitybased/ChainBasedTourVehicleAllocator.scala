package beam.replanning.utilitybased

import java.util
import java.util.Random

import beam.agentsim.agents.memberships.HouseholdMembershipAllocator
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.replanning.utilitybased.ChainBasedTourVehicleAllocator.{SubtourRecord, VehicleRecord, VehicleRecordFactory}
import beam.router.Modes
import beam.router.Modes.BeamMode
import org.apache.log4j.Logger
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population._
import org.matsim.contrib.socnetsim.sharedvehicles.VehicleRessources
import org.matsim.contrib.socnetsim.sharedvehicles.replanning.AllocateVehicleToPlansInGroupPlanAlgorithm
import org.matsim.core.population.routes.NetworkRoute
import org.matsim.core.router.TripStructureUtils._
import org.matsim.core.router.{CompositeStageActivityTypes, TripStructureUtils}
import org.matsim.core.utils.misc.Time
import org.matsim.households.Household
import org.matsim.vehicles.{Vehicle, Vehicles}

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.collection.{JavaConverters, mutable}
import scala.util.Try

case class ChainBasedTourVehicleAllocator(vehicles: Vehicles,
                                          householdMembershipAllocator: HouseholdMembershipAllocator,
                                          modes: Set[String]) extends VehicleRessources {

  import beam.agentsim.agents.memberships.Memberships.RankedGroup._

  private val log = Logger.getLogger(classOf[ChainBasedTourVehicleAllocator])

  val stageActivitytypes = new CompositeStageActivityTypes()

  private val random = new Random(3004568) // Random.org

  // Delegation
  val householdMemberships: Map[Id[Person], Household] = householdMembershipAllocator.memberships

  private val allocator = new AllocateVehicleToPlansInGroupPlanAlgorithm(random, this, JavaConverters.asJavaCollection
  (modes), true, false)

  // Delegation
  implicit val population: Population = householdMembershipAllocator.population

  /**
    * These [[Vehicle]]s cannot be assigned to other agents.
    */
  var _reservedForPerson: TrieMap[Id[Vehicle], Id[Person]] = TrieMap[Id[Vehicle], Id[Person]]()
  //
  var _householdAllocations: TrieMap[Id[Household], Seq[SubtourRecord]] = TrieMap[Id[Household], Seq[SubtourRecord]]()


  override def identifyVehiclesUsableForAgent(person: Id[Person]): util.Set[Id[Vehicle]] = {

    JavaConverters.setAsJavaSet(filterAvailableVehiclesForAgent(person).map(veh => veh.getId).toSet)
  }

  private def filterAvailableVehiclesForAgent(person:Id[Person]):Vector[Vehicle]={
    val hh = householdMemberships(person)
    // only car for now
    JavaConverters.asScalaBuffer(hh.getVehicleIds).filterNot(vehId => _reservedForPerson.contains(vehId)).map(vehId =>
      vehicles.getVehicles.get(vehId)).filter(vehicle => vehicle.getType.getDescription.equals
    ("Car") || vehicle.getType.getDescription.equals
    ("SUV")).toVector
  }

  def identifyChainBasedModesForAgent(person: Id[Person]): Vector[BeamMode] = {
    val availableVehicles = filterAvailableVehiclesForAgent(person)
    if(availableVehicles.nonEmpty){
      availableVehicles.map(_=>BeamMode.CAR)
    }else{
      Vector[BeamMode]()
    }
  }

  def allocateChainBasedModesforHouseholdMember(memberId: Id[Person]): Unit = {

    val household = householdMemberships(memberId)

    val householdPlans: Seq[Plan] = household.members.flatMap(member => JavaConverters.collectionAsScalaIterable(member.getPlans))

//    val groupPlan = new GroupPlans(new util.ArrayList[JointPlan](), JavaConverters.seqAsJavaList(householdPlans))

    val vehicularTours = getVehicularToursSortedByStartTime(householdPlans)

//    if (vehicularTours.nonEmpty) {
//      allocator.run(groupPlan)
//    }

    allocateVehicles(vehicularTours)

    processAllocation(vehicularTours)

    _householdAllocations.put(household.getId,vehicularTours)

  }


  def processAllocation(vehicularTours: Seq[SubtourRecord]): Unit = {
    for {record <- vehicularTours
         subtour = record.subtour
         trip: TripStructureUtils.Trip <- JavaConverters.collectionAsScalaIterable(subtour.getTrips)
         leg: Leg <- JavaConverters.collectionAsScalaIterable(trip.getLegsOnly) if !modes.contains(leg
      .getMode)} yield leg.getRoute.asInstanceOf[NetworkRoute].setVehicleId(record.allocatedVehicle.getOrElse
    (throw new RuntimeException("No VehicleId for record")))
  }

  @tailrec
  private def allocateVehicles(toursToAllocate: Seq[SubtourRecord]): Unit = {
    // greedy algo. Should be ok, but didn't formally prove it
    if (toursToAllocate.isEmpty) return
    val remainingSubtours = toursToAllocate.tail
    val currentSubtour = remainingSubtours.head

    val firstAvailableVehicle = currentSubtour.possibleVehicles.min((vr1:VehicleRecord, vr2:VehicleRecord) => {
      val timeComp = java.lang.Double.compare(vr1.availableFrom, vr2.availableFrom)
      if (timeComp != 0) timeComp else vr1.nAllocs - vr2.nAllocs
    })

    if (firstAvailableVehicle.availableFrom < currentSubtour.endTime) firstAvailableVehicle.availableFrom = currentSubtour.endTime
    firstAvailableVehicle.nAllocs += 1
    currentSubtour.allocatedVehicle = Some(firstAvailableVehicle.id)
    allocateVehicles(remainingSubtours)
  }


  private def getVehicularToursSortedByStartTime(householdPlans: Seq[Plan]) = {
    val vehicleRecordFactory = new VehicleRecordFactory()
    val vehicularTours =
      (for {plan: Plan <- householdPlans
            subtour: Subtour <- JavaConverters.collectionAsScalaIterable(getSubtours(plan, stageActivitytypes)) if subtour.getParent != null}
        yield {
          for {trip <- JavaConverters.collectionAsScalaIterable(subtour.getTrips) if isChainBased(trip)}
            yield {
              SubtourRecord(vehicleRecordFactory.getRecords(
                JavaConverters.collectionAsScalaIterable(identifyVehiclesUsableForAgent(plan
                  .getPerson.getId)).toVector),
                subtour)
            }
        }).flatten

    validateVehicularTours(vehicularTours).sortWith((st1, st2) => st1.startTime > st1.endTime)

  }

  private def validateVehicularTours(vehicularTours: Seq[SubtourRecord]): Seq[SubtourRecord] = {

    var homeLoc: Option[Id[Link]] = None
    for (record <- vehicularTours) {
      val s = record.subtour
      assert(s.getParent == null)
      val anchor: Option[Id[Link]] = Option(s.getTrips.get(0).getOriginActivity.getLinkId)
      if (anchor.isEmpty) throw new NullPointerException("null anchor location")
      if (homeLoc.isEmpty) homeLoc = anchor
      else if (!(homeLoc == anchor)) { // invalid
        return Seq[SubtourRecord]()
      }
    }
    vehicularTours
  }


  private def isChainBased(t: Trip): Boolean = {
    val legs = JavaConverters.collectionAsScalaIterable(t.getLegsOnly).toSeq
    if (legs.isEmpty) return false
    // XXX what to do if several legs???
    val l = legs.head
    if (!Modes.BeamMode.chainBasedModes.contains(BeamMode.withValue(l.getMode))) return false
    true
  }

}

object ChainBasedTourVehicleAllocator {


  case class VehicleRecord(id: Id[BeamVehicle], var nAllocs: Int = 0, var availableFrom: Double = Double.NegativeInfinity)


  class VehicleRecordFactory {
    final private val records = mutable.Map[Id[Vehicle], VehicleRecord]()

    def getRecords(ids: Vector[Id[Vehicle]]): Vector[VehicleRecord] =
      ids.map(id => records.getOrElseUpdate(id, VehicleRecord(id)))

  }

  case class SubtourRecord(startTime: Double, endTime: Double, possibleVehicles: Vector[VehicleRecord],
                           subtour: Subtour, var allocatedVehicle: Option[Id[Vehicle]])

  object SubtourRecord {
    def apply(possibleVehicles: Vector[VehicleRecord], subtour: Subtour): SubtourRecord = {
      val trips = JavaConverters.collectionAsScalaIterable(subtour.getTrips)
      val startTime = Try {
        trips.head.getOriginActivity.getStartTime
      }.getOrElse(throw
        new RuntimeException(s"No endTime in ${trips.head.getOriginActivity}"))
      val lastTrip = trips.toList.reverse.head
      val endTime = lastTrip.getOriginActivity.getEndTime + JavaConverters.collectionAsScalaIterable(lastTrip
        .getTripElements).map({
        case act: Activity => Option(act.getEndTime).getOrElse(throw new RuntimeException(s"could not get " +
          s"time from $act"))
        case leg: Leg => Option(leg.getRoute).filterNot(route => Time.isUndefinedTime(route
          .getTravelTime)).map(_.getTravelTime).filterNot(Time.isUndefinedTime).getOrElse(0.0)
      }).sum
      new SubtourRecord(startTime, endTime, possibleVehicles, subtour, None)
    }
  }


}





