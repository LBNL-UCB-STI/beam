package beam.replanning.utilitybased

import beam.agentsim.agents.Population
import beam.agentsim.agents.choice.mode.DrivingCostDefaults._
import beam.agentsim.agents.choice.mode.TransitFareDefaults
import beam.agentsim.agents.household.HouseholdActor.AttributesOfIndividual
import beam.agentsim.agents.memberships.HouseholdMembershipAllocator
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.agentsim.agents.planning.Strategy.ModeChoiceStrategy
import beam.agentsim.agents.planning.{BeamPlan, Tour, Trip}
import beam.agentsim.agents.vehicles.BeamVehicleType.Car
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BUS, CAR, FERRY, RAIL, SUBWAY}
import beam.sim.BeamServices
import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.random.MersenneTwister
import org.apache.commons.math3.util.Pair
import org.matsim.api.core.v01.population.{Activity, Person, Plan}
import org.matsim.core.population.algorithms.PlanAlgorithm
import org.matsim.vehicles.Vehicles

import scala.collection.{JavaConverters, mutable}


class ChangeModeForTour(beamServices: BeamServices, householdMembershipAllocator: HouseholdMembershipAllocator, vehicles: Vehicles) extends PlanAlgorithm {

  val rng = new MersenneTwister(3004568) // Random.org

  val weightedRandom = new EnumeratedDistribution[BeamMode](rng, JavaConverters.bufferAsJavaList(mutable.Buffer[Pair[BeamMode, java.lang.Double]](new Pair[BeamMode, java.lang.Double](BUS, 0.8), new Pair[BeamMode, java.lang.Double](SUBWAY, 1.5), new Pair[BeamMode, java.lang.Double](FERRY, 0.05), new Pair[BeamMode, java.lang.Double](RAIL, 0.45))))

  def findChainBasedModesPerPerson(person: Person): Vector[BeamMode] = {
    // Does person have access to chain-based modes at home for this plan?
    val household = householdMembershipAllocator.memberships(person.getId)
    // For now just cars
    JavaConverters.asScalaBuffer(household.getVehicleIds).map(vehId => beamServices.vehicles(vehId)).filter(beamVehicle => beamVehicle.beamVehicleType.equals(Car)).map(_ => CAR).toVector
  }

  def findAlternativesForTour(tour: Tour, person: Person)
  : Vector[BeamMode] = {
    val res = weightedRandom.sample(1, Array())
    findChainBasedModesPerPerson(person) ++ Vector[BeamMode](res(0))
  }

  def scoreTour(tour: Tour, person: Person, modeChoiceCalculator: ModeChoiceCalculator): Map[BeamMode, Double] = {
    (for {alt <- findAlternativesForTour(tour, person)} yield {
      alt ->
        tour.trips.map(trip => trip.activity)
          .sliding(2).map(vec => {
          if (vec.size > 1) {
            val timeDist = getCostAndTimeForMode(alt, vec(0), vec(1))
            modeChoiceCalculator.utilityOf(alt, timeDist._1, timeDist._2)
          }
          else {
            0
          }
        }).sum
    }).toMap
  }

  def getCostAndTimeForMode(beamMode: BeamMode, origin: Activity, dest: Activity)
  : (Double, Double) = {
    val originCoord = origin.getCoord
    val destCoord = dest.getCoord
    val tripDistanceInMeters = beamServices.geo.distLatLon2Meters(originCoord, destCoord)
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
    val transit2AutoRatio = 1.7 // m/s

    if (beamMode.isTransit()) {
      //Assume PT speed of 10 m/s
      tripDistanceInMeters / transitSpeedDefault
    } else {
      transitSpeedDefault * transit2AutoRatio / transitSpeedDefault
    }
  }

  def rankAlternatives(beamPlan: BeamPlan,
                       attributesOfIndividual: AttributesOfIndividual): Map[Int, Map[BeamMode, Double]] = {
    val modeChoiceCalculator = beamServices.modeChoiceCalculatorFactory(attributesOfIndividual)
    beamPlan.tours.zipWithIndex.map({ case (tour, idx) =>
      idx -> scoreTour(tour, beamPlan.getPerson, modeChoiceCalculator)
    }).toMap
  }


  //  def propagateVehicleConstraints(beamPlan: BeamPlan, modeMap: Map[BeamMode, Int]): Boolean = {
  //
  //    beamPlan.tours.map(tour =>)
  //
  //  }
  //


  def addPlanStrategyForTrip(trip: Trip, beamPlan: BeamPlan, mode: BeamMode): Unit =
    beamPlan.putStrategy(trip, ModeChoiceStrategy(mode))


  override def run(plan: Plan): Unit = {
    val beamPlan = BeamPlan(plan)
    val person = beamPlan.getPerson
    val household = householdMembershipAllocator.memberships(person.getId)
    val attributesOfIndividual = AttributesOfIndividual(person, household, Population.getVehiclesFromHousehold(household, vehicles))
    val rankedAlternatives = rankAlternatives(beamPlan, attributesOfIndividual)
    rankedAlternatives.foreach({ case (tourIdx, alts) =>
      beamPlan.tours(tourIdx).trips.foreach(trip => addPlanStrategyForTrip(trip, beamPlan, alts.maxBy({ case (_, dbl) => dbl })._1))
    })
  }
}

