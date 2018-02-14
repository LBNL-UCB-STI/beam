package beam.replanning.utilitybased

import java.util
import java.util.Collections

import beam.agentsim.agents.Population
import beam.agentsim.agents.choice.mode.DrivingCostDefaults._
import beam.agentsim.agents.choice.mode.TransitFareDefaults
import beam.agentsim.agents.household.HouseholdActor.AttributesOfIndividual
import beam.agentsim.agents.memberships.HouseholdMembershipAllocator
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.agentsim.agents.vehicles.BeamVehicleType.Car
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BUS, CAR, DRIVE_TRANSIT, FERRY, RAIL, SUBWAY, WALK_TRANSIT}
import beam.sim.BeamServices
import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.random.MersenneTwister
import org.apache.commons.math3.util.Pair
import org.matsim.api.core.v01.population._
import org.matsim.core.population.algorithms.PlanAlgorithm
import org.matsim.core.router.TripStructureUtils.{Subtour, Trip}
import org.matsim.core.router.{CompositeStageActivityTypes, TripRouter, TripStructureUtils}
import org.matsim.vehicles.Vehicles

import scala.collection.{JavaConverters, mutable}


class ChangeModeForTour(beamServices: BeamServices, householdMembershipAllocator: HouseholdMembershipAllocator, vehicles: Vehicles) extends PlanAlgorithm {

  val rng = new MersenneTwister(3004568) // Random.org

  val weightedRandom = new EnumeratedDistribution[BeamMode](rng, JavaConverters.bufferAsJavaList(mutable.Buffer[Pair[BeamMode, java.lang.Double]](new Pair[BeamMode, java.lang.Double](BUS, 0.8), new Pair[BeamMode, java.lang.Double](SUBWAY, 0.15), new Pair[BeamMode, java.lang.Double](FERRY, 0.005), new Pair[BeamMode, java.lang.Double](RAIL, 0.045))))

  val stageActivitytypes = new CompositeStageActivityTypes()

  def findChainBasedModesPerPerson(person: Person): Vector[BeamMode] = {
    // Does person have access to chain-based modes at home for this plan?
    val household = householdMembershipAllocator.memberships(person.getId)
    // For now just cars
    JavaConverters.asScalaBuffer(household.getVehicleIds).map(vehId => beamServices.vehicles(vehId)).filter(beamVehicle => beamVehicle.beamVehicleType.equals(Car)).map(_ => CAR).toVector
  }

  def findAlternativesForTour(tour: Subtour, person: Person)
  : Vector[BeamMode] = {
    val res = weightedRandom.sample(1, Array())
    findChainBasedModesPerPerson(person) ++ Vector[BeamMode](res(0))
  }

  def scoreTour(tour: Subtour, person: Person, modeChoiceCalculator: ModeChoiceCalculator): Map[BeamMode, Double] = {
    val alternativesForTour = findAlternativesForTour(tour, person)
    (for {alt <- alternativesForTour} yield {
      alt -> JavaConverters.collectionAsScalaIterable(tour.getTrips).map(trip => {
        val timeDist = getCostAndTimeForMode(alt, trip.getOriginActivity, trip.getDestinationActivity)
        if(alt.isTransit()){
            modeChoiceCalculator.utilityOf(if(alternativesForTour.contains(CAR)) DRIVE_TRANSIT else WALK_TRANSIT, timeDist._1, timeDist._2, numTransfers = rng.nextInt(4) + 1)
        }else{
          modeChoiceCalculator.utilityOf(alt, timeDist._1, timeDist._2)
        }
      }).sum
    }).toMap
  }

  def getCostAndTimeForMode(beamMode: BeamMode, origin: Activity, dest: Activity)
  : (Double, Double) = {
    val originCoord = origin.getCoord
    val destCoord = dest.getCoord
    val tripDistanceInMeters = beamServices.geo.distLatLon2Meters(beamServices.geo.utm2Wgs(originCoord), beamServices
      .geo.utm2Wgs(destCoord))
    val cost = defaultCostPerMode(beamMode, tripDistanceInMeters)
    val time = defaultTimeScalingPerMode(beamMode, tripDistanceInMeters)
    (cost, time)
  }

  def defaultCostPerMode(beamMode: BeamMode, distance: Double): Double = {
    if (beamMode.isTransit()) {
      TransitFareDefaults.faresByMode(beamMode)
    } else {
      distance * (DEFAULT_LITERS_PER_METER / DEFAULT_LITERS_PER_GALLON) * DEFAULT_PRICE_PER_GALLON
    }
  }

  def defaultTimeScalingPerMode(beamMode: BeamMode, tripDistanceInMeters: Double): Double = {
    val transitSpeedDefault = 10 // m/s
    val transit2AutoRatio = 1.7 // car is 1.7 times faster than transit

    if (beamMode.isTransit()) {
      //Assume PT speed of 10 m/s
      tripDistanceInMeters / transitSpeedDefault
    } else {
      tripDistanceInMeters / (transitSpeedDefault * transit2AutoRatio)
    }
  }

  def rankAlternatives(plan: Plan,
                       attributesOfIndividual: AttributesOfIndividual): Map[Int, Map[BeamMode, Double]] = {
    val modeChoiceCalculator = beamServices.modeChoiceCalculatorFactory(attributesOfIndividual)

    val subtours = JavaConverters.collectionAsScalaIterable(TripStructureUtils.getSubtours(plan, stageActivitytypes))

    subtours.zipWithIndex.map({ case (tour, idx) =>
      idx -> scoreTour(tour, plan.getPerson, modeChoiceCalculator)
    }).toMap
  }


  //  def propagateVehicleConstraints(beamPlan: BeamPlan, modeMap: Map[BeamMode, Int]): Boolean = {
  //
  //    beamPlan.tours.map(tour =>)
  //
  //  }
  //


  def changeModeForTrip(trip: Trip, plan: Plan, mode: BeamMode): Unit = {
    val legs = JavaConverters.collectionAsScalaIterable(trip.getLegsOnly)
    if (legs.isEmpty) {
      insertEmptyTrip(plan,trip.getOriginActivity,trip.getDestinationActivity,mode.toString,
        householdMembershipAllocator.population.getFactory)
    } else {
      legs.foreach(leg => leg.setMode(mode.value))
    }
  }

  def insertEmptyTrip(plan: Plan, fromActivity: Activity, toActivity: Activity, mainMode: String,
                      pf: PopulationFactory):Unit = {
    val list: util.List[Leg] = Collections.singletonList(pf.createLeg(mainMode))
    TripRouter.insertTrip(plan, fromActivity, list, toActivity)
  }

  def addTripsBetweenActivities(plan:Plan):Unit={
    val activities = JavaConverters.collectionAsScalaIterable(TripStructureUtils.getActivities(plan,
      stageActivitytypes)).toIndexedSeq
    activities.sliding(2).foreach(acts=>    insertEmptyTrip(plan,acts(0),acts(1), "car",
      householdMembershipAllocator.population.getFactory))
  }

  override def run(plan: Plan): Unit = {
    if(JavaConverters.collectionAsScalaIterable(TripStructureUtils.getLegs(plan)).isEmpty){addTripsBetweenActivities(plan)}
    val person = plan.getPerson
    val household = householdMembershipAllocator.memberships(person.getId)
    val attributesOfIndividual = AttributesOfIndividual(person, household, Population.getVehiclesFromHousehold(household, vehicles))
    val rankedAlternatives = rankAlternatives(plan, attributesOfIndividual)
    val tours: Iterable[Subtour] = JavaConverters.collectionAsScalaIterable(TripStructureUtils.getSubtours(plan, stageActivitytypes))
    rankedAlternatives.foreach({ case (tourIdx, alts) =>
      val subtour: Subtour = tours.toIndexedSeq(tourIdx)
      val trips = JavaConverters.collectionAsScalaIterable(subtour.getTrips)
      trips.foreach(trip => changeModeForTrip(trip, plan, alts.maxBy({ case (_, dbl) => dbl })._1))
    })
  }
}

