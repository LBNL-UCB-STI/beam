package beam.replanning.utilitybased

import beam.agentsim.agents.memberships.HouseholdMembershipAllocator
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.replanning.utilitybased.ChainBasedTourVehicleAllocator.{SubtourRecord, VehicleRecord, VehicleRecordFactory}
import beam.router.Modes
import beam.router.Modes.BeamMode
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population._
import org.matsim.core.population.routes.{LinkNetworkRouteFactory, NetworkRoute}
import org.matsim.core.router.TripStructureUtils._
import org.matsim.core.router.{CompositeStageActivityTypes, TripStructureUtils}
import org.matsim.core.utils.misc.Time
import org.matsim.households.Household
import org.matsim.vehicles.{Vehicle, Vehicles}

import scala.collection.{mutable, JavaConverters}
import scala.util.Try

case class ChainBasedTourVehicleAllocator(
  vehicles: Vehicles,
  householdMembershipAllocator: HouseholdMembershipAllocator,
  modes: Set[String]
) {

  import beam.agentsim.agents.memberships.Memberships.RankedGroup._

  val stageActivitytypes = new CompositeStageActivityTypes()

  val linkNetworkRouteFactory = new LinkNetworkRouteFactory()

  // Delegation
  val householdMemberships: Map[Id[Person], Household] = householdMembershipAllocator.memberships

  // Delegation
  implicit val population: Population = householdMembershipAllocator.population

  def identifyVehiclesUsableForAgent(person: Id[Person]): Vector[Id[Vehicle]] = {
    filterAvailableVehiclesForAgent(person).map(veh => veh.getId)
  }

  private def filterAvailableVehiclesForAgent(person: Id[Person]): Vector[Vehicle] = {
    // only car for now
    householdMembershipAllocator
      .lookupVehicleForRankedPerson(person)
      .map(vehId => vehicles.getVehicles.get(vehId))
      .filter(
        vehicle =>
          vehicle.getType.getDescription.equals("Car") || vehicle.getType.getDescription
            .equals("SUV")
      )
      .toVector
  }

  def identifyChainBasedModesForAgent(person: Id[Person]): Vector[BeamMode] = {
    val availableVehicles = filterAvailableVehiclesForAgent(person)
    if (availableVehicles.nonEmpty) {
      availableVehicles.map(_ => BeamMode.CAR)
    } else {
      Vector[BeamMode]()
    }
  }

  def allocateChainBasedModesforHouseholdMember(
    memberId: Id[Person],
    subtour: Subtour,
    plan: Plan
  ): Unit = {

    val household = householdMemberships(memberId)

    val householdPlans: Seq[Plan] =
      household.members.flatMap(member => JavaConverters.collectionAsScalaIterable(member.getPlans))

    val vehicularTours: Option[SubtourRecord] =
      getVehicularToursSortedByStartTime(householdPlans).find(rec => rec.subtour == subtour)

    vehicularTours foreach { vt =>
      if (allocateVehicles(vt)) {
        //TODO: turn back on when using intra-household choice
//        processAllocation(vt, plan)
      }
    }
  }

  private def processAllocation(record: SubtourRecord, plan: Plan): Unit = {
    val subtour = record.subtour
    for {
      trip: TripStructureUtils.Trip <- JavaConverters.collectionAsScalaIterable(subtour.getTrips)
      leg: Leg                      <- JavaConverters.collectionAsScalaIterable(trip.getLegsOnly)
    } yield {
      if (leg.getRoute == null) {
        val currentTrip = TripStructureUtils.findCurrentTrip(leg, plan, stageActivitytypes)
        leg.setRoute(
          linkNetworkRouteFactory.createRoute(
            currentTrip.getOriginActivity.getLinkId,
            currentTrip.getDestinationActivity.getLinkId
          )
        )
      }
      val allocatedVehicle = record.allocatedVehicle.getOrElse {
        throw new RuntimeException("No vehicle allocated for subtour!")
      }
      leg.getRoute.asInstanceOf[NetworkRoute].setVehicleId(allocatedVehicle)
      leg.setMode("car")
    }
  }

  private def allocateVehicles(currentSubtour: SubtourRecord): Boolean = {
    if (currentSubtour.possibleVehicles.nonEmpty) {
      val firstAvailableVehicle =
        currentSubtour.possibleVehicles.min((vr1: VehicleRecord, vr2: VehicleRecord) => {
          val timeComp = java.lang.Double.compare(vr1.availableFrom, vr2.availableFrom)
          if (timeComp != 0) timeComp else vr1.nAllocs - vr2.nAllocs
        })
      if (firstAvailableVehicle.availableFrom < currentSubtour.endTime)
        firstAvailableVehicle.availableFrom = currentSubtour.endTime
      firstAvailableVehicle.nAllocs += 1
      currentSubtour.allocatedVehicle = Some(firstAvailableVehicle.id)
      true
    } else { false }
  }

  private def getVehicularToursSortedByStartTime(householdPlans: Seq[Plan]) = {
    val vehicleRecordFactory = new VehicleRecordFactory()
    val vehicularTours =
      (for {
        plan: Plan <- householdPlans
        subtour: Subtour <- JavaConverters.collectionAsScalaIterable(
          getSubtours(plan, stageActivitytypes)
        )
      } yield {
        for { _ <- JavaConverters.collectionAsScalaIterable(subtour.getTrips) } yield {
          val usableVehicles = identifyVehiclesUsableForAgent(plan.getPerson.getId)
          val vehicleRecords = vehicleRecordFactory.getRecords(usableVehicles)
          SubtourRecord(vehicleRecords, subtour)
        }
      }).flatten.toVector

    validateVehicularTours(vehicularTours).sortBy(_.startTime) //.sortWith((st1, st2) => st1.startTime > st1.endTime)
  }

  private def validateVehicularTours(vehicularTours: Vector[SubtourRecord]): Seq[SubtourRecord] = {
    import scala.util.control.Breaks._
    var homeLoc: Option[Id[Link]] = None
    var returnVal: Option[Seq[SubtourRecord]] = None
    breakable {
      for (record <- vehicularTours) {
        val s = record.subtour
        val anchor: Option[Id[Link]] = Option(s.getTrips.get(0).getOriginActivity.getLinkId)
        if (anchor.isEmpty) throw new NullPointerException("null anchor location")
        if (homeLoc.isEmpty) homeLoc = anchor
        else if (!(homeLoc == anchor)) { // invalid
          returnVal = Some(Seq[SubtourRecord]())
          break
        }
      }
    }
    returnVal.getOrElse(vehicularTours)
  }

  private def isChainBased(t: Trip): Boolean = {
    val legs = JavaConverters.collectionAsScalaIterable(t.getLegsOnly).toSeq
    if (legs.isEmpty) false
    else {
      // XXX what to do if several legs???
      val l = legs.head
      if (!Modes.BeamMode.chainBasedModes.map(mode => mode.matsimMode).contains(l.getMode)) false
      else true
    }
  }

}

object ChainBasedTourVehicleAllocator {

  case class VehicleRecord(
    id: Id[BeamVehicle],
    var nAllocs: Int = 0,
    var availableFrom: Double = Double.NegativeInfinity
  )

  class VehicleRecordFactory {
    final private val records = mutable.Map[Id[Vehicle], VehicleRecord]()

    def getRecords(ids: Vector[Id[Vehicle]]): Vector[VehicleRecord] =
      ids.map(id => records.getOrElseUpdate(id, VehicleRecord(id)))

  }

  case class SubtourRecord(
    startTime: Double,
    endTime: Double,
    possibleVehicles: Vector[VehicleRecord],
    subtour: Subtour,
    var allocatedVehicle: Option[Id[Vehicle]]
  )

  object SubtourRecord {

    def apply(possibleVehicles: Vector[VehicleRecord], subtour: Subtour): SubtourRecord = {
      val trips = JavaConverters.collectionAsScalaIterable(subtour.getTrips)
      val startTime = Try {
        trips.head.getOriginActivity.getStartTime
      }.getOrElse(throw new RuntimeException(s"No endTime in ${trips.head.getOriginActivity}"))
      val lastTrip = trips.toList.reverse.head
      val endTime = lastTrip.getOriginActivity.getEndTime + JavaConverters
        .collectionAsScalaIterable(lastTrip.getTripElements)
        .map({
          case act: Activity =>
            Option(act.getEndTime).getOrElse(
              throw new RuntimeException(s"could not get time from $act")
            )
          case leg: Leg =>
            Option(leg)
              .flatMap(leg => Option(leg.getRoute))
              .filterNot(
                route =>
                  Time.isUndefinedTime(Try {
                    route.getTravelTime
                  }.getOrElse(Double.NegativeInfinity))
              )
              .map(_.getTravelTime)
              .filterNot(Time.isUndefinedTime)
              .getOrElse(0.0)
        })
        .sum
      new SubtourRecord(startTime, endTime, possibleVehicles, subtour, None)
    }
  }
}
