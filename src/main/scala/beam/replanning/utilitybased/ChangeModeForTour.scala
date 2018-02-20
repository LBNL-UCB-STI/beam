package beam.replanning.utilitybased

import java.util
import java.util.Collections

import beam.agentsim.agents.Population
import beam.agentsim.agents.choice.mode.DrivingCostDefaults._
import beam.agentsim.agents.choice.mode.TransitFareDefaults
import beam.agentsim.agents.household.HouseholdActor.AttributesOfIndividual
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BUS, CAR, DRIVE_TRANSIT, FERRY, RAIL, SUBWAY, WALK_TRANSIT}
import beam.sim.BeamServices
import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.random.MersenneTwister
import org.apache.commons.math3.util.Pair
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population._
import org.matsim.core.population.algorithms.PlanAlgorithm
import org.matsim.core.router.TripStructureUtils.Subtour
import org.matsim.core.router.{CompositeStageActivityTypes, TripRouter, TripStructureUtils}

import scala.collection.JavaConverters._
import scala.collection.{JavaConverters, mutable}

class ChangeModeForTour(beamServices: BeamServices,
                        chainBasedTourVehicleAllocator: ChainBasedTourVehicleAllocator) extends PlanAlgorithm {

  val rng = new MersenneTwister(3004568) // Random.org

  val weightedRandom = new EnumeratedDistribution[BeamMode](rng, JavaConverters.bufferAsJavaList(mutable.Buffer[Pair[BeamMode, java.lang.Double]](new Pair[BeamMode, java.lang.Double](BUS, 0.8), new Pair[BeamMode, java.lang.Double](SUBWAY, 0.15), new Pair[BeamMode, java.lang.Double](FERRY, 0.005), new Pair[BeamMode, java.lang.Double](RAIL, 0.045))))

  val stageActivitytypes = new CompositeStageActivityTypes()


  def findAlternativesForTour(tour: Subtour, person: Person)
  : Vector[BeamMode] = {
    val res = weightedRandom.sample(1, Array())
    chainBasedTourVehicleAllocator.identifyChainBasedModesForAgent(person.getId) ++ Vector[BeamMode](res(0))
  }

  def scoreTour(tour: Subtour, person: Person, modeChoiceCalculator: ModeChoiceCalculator): Map[BeamMode, Double] = {
    val alternativesForTour = findAlternativesForTour(tour, person)
    (for {alt <- alternativesForTour} yield {
      alt -> JavaConverters.collectionAsScalaIterable(tour.getTrips).map(trip => {
        val timeDist = getCostAndTimeForMode(alt, trip.getOriginActivity, trip.getDestinationActivity)
        if (alt.isTransit()) {
          modeChoiceCalculator.utilityOf(
            if (alternativesForTour.contains(CAR)) DRIVE_TRANSIT
            else WALK_TRANSIT, timeDist._1, timeDist._2, numTransfers = rng.nextInt(4) + 1)
        } else {
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
    val transit2AutoRatio = 1.1 // car is 1.7 times faster than transit

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


  //  def propagateVehicleConstraints(subtour: Subtour, chainBasedModes: Vector[BeamMode]): Boolean = {
  //    chainBasedTourVehicleAllocator
  //
  //  }


  def changeModeForTour(subtour: Subtour, plan: Plan, mode: BeamMode): Unit = {
    val trips = JavaConverters.collectionAsScalaIterable(subtour.getTrips)

    val legs = trips.flatMap(trip => JavaConverters
      .collectionAsScalaIterable(trip
        .getLegsOnly))

    if (legs.isEmpty) {
      for {trip <- trips} yield {
        insertEmptyTrip(plan, trip.getOriginActivity, trip.getDestinationActivity, mode.toString,
          chainBasedTourVehicleAllocator.population.getFactory)
      }
    }

    if (mode.isTransit()) {
      legs.foreach(leg => leg.setMode(WALK_TRANSIT.value))
    } else {
      chainBasedTourVehicleAllocator.allocateChainBasedModesforHouseholdMember(plan.getPerson.getId)
    }
  }

  def insertEmptyTrip(plan: Plan, fromActivity: Activity, toActivity: Activity, mainMode: String,
                      pf: PopulationFactory): Unit = {
    val list: util.List[Leg] = Collections.singletonList(pf.createLeg(mainMode))
    TripRouter.insertTrip(plan, fromActivity, list, toActivity)
  }

  def addTripsBetweenActivities(plan: Plan): Unit = {
    val activities = JavaConverters.collectionAsScalaIterable(TripStructureUtils.getActivities(plan,
      stageActivitytypes)).toIndexedSeq
    activities.sliding(2).foreach(acts => insertEmptyTrip(plan, acts(0), acts(1), "car",
      chainBasedTourVehicleAllocator.population.getFactory))
  }

  override def run(plan: Plan): Unit = {
    maybeFixPlans(plan)
    val person = plan.getPerson
    val household = chainBasedTourVehicleAllocator.householdMemberships(person.getId)
    val householdVehicles = Population.getVehiclesFromHousehold(household, chainBasedTourVehicleAllocator.vehicles)
    val attributesOfIndividual = AttributesOfIndividual(person, household, householdVehicles)
    val rankedAlternatives = rankAlternatives(plan, attributesOfIndividual)
    val tours: Seq[Subtour] = JavaConverters.collectionAsScalaIterable(TripStructureUtils.getSubtours(plan,
      stageActivitytypes)).toIndexedSeq
    rankedAlternatives.foreach({ case (tourIdx, alts) =>
      val subtour: Subtour = tours(tourIdx)
      changeModeForTour(subtour, plan, alts.maxBy({ case (_, dbl) => dbl })._1)
    })
  }

  private def maybeFixPlans(plan: Plan): Unit = {
    if (JavaConverters.collectionAsScalaIterable(TripStructureUtils.getLegs(plan)).isEmpty) {
      addTripsBetweenActivities(plan)
    }
    plan.getPlanElements.asScala.filter(pe =>
      !pe.isInstanceOf[Activity] && pe.asInstanceOf[Activity].getLinkId == null).foreach(act =>
      act.asInstanceOf[Activity].setLinkId(Id.createLinkId("dummy")))

  }
}

