package beam.replanning.utilitybased

import java.util
import java.util.Collections

import beam.agentsim.agents.Population
import beam.agentsim.agents.choice.mode.TransitFareDefaults
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.vehicles.FuelType.Gasoline
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BUS, CAR, DRIVE_TRANSIT, FERRY, RAIL, RIDE_HAIL, SUBWAY, WALK, WALK_TRANSIT}
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes, PopulationAdjustment}
import beam.sim.{BeamScenario, BeamServices}
import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.random.MersenneTwister
import org.apache.commons.math3.util.Pair
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population._
import org.matsim.core.population.PersonUtils
import org.matsim.core.population.algorithms.PlanAlgorithm
import org.matsim.core.router.TripStructureUtils.Subtour
import org.matsim.core.router.{CompositeStageActivityTypes, TripRouter, TripStructureUtils}

import scala.collection.JavaConverters._
import scala.collection.{mutable, JavaConverters}
import scala.util.Random

class ChangeModeForTour(
  beamServices: BeamServices,
  chainBasedTourVehicleAllocator: ChainBasedTourVehicleAllocator,
  beamScenario: BeamScenario
) extends PlanAlgorithm {

  // should this Random use a fixed seed from beamConfig ?
  val rng = new MersenneTwister(3004568) // Random.org
  val random = new Random(3004568)

  val weightedRandom = new EnumeratedDistribution[BeamMode](
    rng,
    JavaConverters.bufferAsJavaList(
      mutable.Buffer[Pair[BeamMode, java.lang.Double]](
        new Pair[BeamMode, java.lang.Double](BUS, 0.8),
        new Pair[BeamMode, java.lang.Double](SUBWAY, 0.15),
        new Pair[BeamMode, java.lang.Double](FERRY, 0.005),
        new Pair[BeamMode, java.lang.Double](RAIL, 0.045)
      )
    )
  )

  private val rideHailConfig =
    beamServices.beamConfig.beam.agentsim.agents.rideHail

  val DefaultRideHailCostPerMile: Double = rideHailConfig.defaultCostPerMile
  val DefaultRideHailCostPerMinute: Double = rideHailConfig.defaultCostPerMinute

  val stageActivityTypes = new CompositeStageActivityTypes()

  def findAlternativesForTour(person: Person): Vector[BeamMode] = {
    val res = weightedRandom.sample(1, Array())
    chainBasedTourVehicleAllocator.identifyChainBasedModesForAgent(person.getId) ++ Vector[
      BeamMode
    ](res(0)) ++
    Vector[BeamMode](WALK, RIDE_HAIL)
  }

  def scoreTour(
    tour: Subtour,
    person: Person,
    modeChoiceCalculator: ModeChoiceCalculator
  ): Map[BeamMode, Double] = {
    val alternativesForTour = findAlternativesForTour(person)
    (for { alt <- alternativesForTour } yield {
      alt -> JavaConverters
        .collectionAsScalaIterable(tour.getTrips)
        .map(trip => {
          val timeDist =
            getCostAndTimeForMode(alt, trip.getOriginActivity, trip.getDestinationActivity)
          if (alt.isTransit) {
            modeChoiceCalculator.utilityOf(
              if (alternativesForTour.contains(CAR)) DRIVE_TRANSIT
              else WALK_TRANSIT,
              timeDist._1,
              timeDist._2,
              numTransfers = rng.nextInt(4) + 1
            )
          } else {
            modeChoiceCalculator.utilityOf(alt, timeDist._1, timeDist._2)
          }
        })
        .sum
    }).toMap
  }

  def getCostAndTimeForMode(
    beamMode: BeamMode,
    origin: Activity,
    dest: Activity
  ): (Double, Double) = {
    val originCoord = origin.getCoord
    val destCoord = dest.getCoord
    // Why not just `beamServices.geo.distUTMInMeters(originCoord, destCoord)`??
    val tripDistanceInMeters = beamServices.geo
      .distLatLon2Meters(beamServices.geo.utm2Wgs(originCoord), beamServices.geo.utm2Wgs(destCoord))
    val distanceCost = distanceScaling(beamMode, tripDistanceInMeters)
    val timeCost = timeScaling(beamMode, tripDistanceInMeters)
    (distanceCost, timeCost)
  }

  def distanceScaling(beamMode: BeamMode, distance: Double): Double = {
    beamMode match {
      case BeamMode.CAR =>
        //FIXME: Use fuel type of vehicle
        beamScenario.fuelTypePrices(Gasoline) * distance
      case WALK => distance * 6 // MATSim Default
      case RIDE_HAIL =>
        distance * DefaultRideHailCostPerMile * (1 / 1609.34) // 1 mile = 1609.34
      case a: BeamMode if a.isTransit =>
        TransitFareDefaults.faresByMode(beamMode)
    }
  }

  def timeScaling(beamMode: BeamMode, tripDistanceInMeters: Double): Double = {
    val transitSpeedDefault = 10 // m/s
    val transit2AutoRatio = 1.7 // car is 1.7 times faster than transit

    beamMode match {
      case BeamMode.CAR =>
        tripDistanceInMeters / (transitSpeedDefault * transit2AutoRatio)
      case WALK =>
        tripDistanceInMeters / 1.4 // 1.4 m/s beeline walk (typical default)
      case RIDE_HAIL =>
        tripDistanceInMeters / (transitSpeedDefault * transit2AutoRatio) * DefaultRideHailCostPerMinute
      case a: BeamMode if a.isTransit =>
        tripDistanceInMeters / transitSpeedDefault
    }
  }

  def rankAlternatives(
    plan: Plan,
    attributesOfIndividual: AttributesOfIndividual
  ): Map[Int, Map[BeamMode, Double]] = {
    val modeChoiceCalculator =
      beamServices.modeChoiceCalculatorFactory(attributesOfIndividual)
    val subTours = JavaConverters.collectionAsScalaIterable(
      TripStructureUtils.getSubtours(plan, stageActivityTypes)
    )
    subTours.zipWithIndex
      .map({ case (tour, idx) =>
        idx -> scoreTour(tour, plan.getPerson, modeChoiceCalculator)
      })
      .toMap
  }

  def changeModeForTour(subtour: Subtour, plan: Plan, mode: BeamMode): Unit = {
    val trips = JavaConverters.collectionAsScalaIterable(subtour.getTrips)

    val legs = trips.flatMap(trip =>
      JavaConverters
        .collectionAsScalaIterable(trip.getLegsOnly)
    )

    if (legs.isEmpty) {
      for { trip <- trips } yield {
        insertEmptyTrip(
          plan,
          trip.getOriginActivity,
          trip.getDestinationActivity,
          mode.toString,
          chainBasedTourVehicleAllocator.population.getFactory
        )
      }
    }

    if (mode.isTransit) {
      legs.foreach(leg => leg.setMode(WALK_TRANSIT.value))
    } else {
      chainBasedTourVehicleAllocator.allocateChainBasedModesforHouseholdMember(
        plan.getPerson.getId,
        subtour
      )
    }
  }

  private def scrubRoutes(plan: Plan): Unit = {
    plan.getPlanElements.forEach {
      case leg: Leg =>
        leg.setRoute(null)
      case _ =>
    }
  }

  def insertEmptyTrip(
    plan: Plan,
    fromActivity: Activity,
    toActivity: Activity,
    mainMode: String,
    pf: PopulationFactory
  ): Unit = {
    val list: util.List[Leg] = Collections.singletonList(pf.createLeg(mainMode))
    TripRouter.insertTrip(plan, fromActivity, list, toActivity)
  }

  def addTripsBetweenActivities(plan: Plan): Unit = {
    val activities = JavaConverters
      .collectionAsScalaIterable(TripStructureUtils.getActivities(plan, stageActivityTypes))
      .toIndexedSeq
    activities
      .sliding(2)
      .foreach(acts =>
        insertEmptyTrip(
          plan,
          acts(0),
          acts(1),
          "car",
          chainBasedTourVehicleAllocator.population.getFactory
        )
      )
  }

  override def run(plan: Plan): Unit = {
    maybeFixPlans(plan)
    val person = plan.getPerson
    val household =
      chainBasedTourVehicleAllocator.householdMemberships(person.getId)

    val householdVehicles =
      Population.getVehiclesFromHousehold(household, beamScenario)

    val modalityStyle =
      Option(person.getSelectedPlan.getAttributes.getAttribute("modality-style"))
        .map(_.asInstanceOf[String])

    val valueOfTime: Double =
      beamServices.matsimServices.getScenario.getPopulation.getPersonAttributes
        .getAttribute(person.getId.toString, "valueOfTime") match {
        case null =>
          beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.defaultValueOfTime
        case specifiedVot =>
          specifiedVot.asInstanceOf[Double]
      }

    val availableModes: Seq[BeamMode] = PopulationAdjustment
      .getBeamAttributes(beamServices.matsimServices.getScenario.getPopulation, person.getId.toString)
      .availableModes

    val income = Option(
      beamServices.matsimServices.getScenario.getPopulation.getPersonAttributes
        .getAttribute(person.getId.toString, "income")
        .asInstanceOf[Int]
    )

    val attributesOfIndividual =
      AttributesOfIndividual(
        HouseholdAttributes(household, householdVehicles),
        modalityStyle,
        PersonUtils.getSex(person).equalsIgnoreCase("M"),
        availableModes,
        valueOfTime,
        Option(PersonUtils.getAge(person)),
        income.map { x =>
          x
        }
      )

    person.getCustomAttributes.put("beam-attributes", attributesOfIndividual)

    val rankedAlternatives = rankAlternatives(plan, attributesOfIndividual)

    val tours: Seq[Subtour] = JavaConverters
      .collectionAsScalaIterable(TripStructureUtils.getSubtours(plan, stageActivityTypes))
      .toIndexedSeq

    rankedAlternatives.foreach({ case (tourIdx, alts) =>
      val denom = Math.abs(alts.values.map(Math.exp).sum)
      val altIter = alts.map { x =>
        new Pair[BeamMode, java.lang.Double](x._1, Math.exp(x._2) / denom)
      }
      val dist = new EnumeratedDistribution[BeamMode](
        rng,
        JavaConverters.bufferAsJavaList(altIter.toBuffer)
      )
      val choice = dist.sample()
      val subtour: Subtour = tours(tourIdx)
      changeModeForTour(subtour, plan, choice)
    })

    scrubRoutes(plan)

  }

  private def maybeFixPlans(plan: Plan): Unit = {
    if (
      JavaConverters
        .collectionAsScalaIterable(TripStructureUtils.getLegs(plan))
        .isEmpty
    ) {
      addTripsBetweenActivities(plan)
    }
    plan.getPlanElements.asScala.foreach {
      case act: Activity if act.getLinkId == null =>
        act.setLinkId(Id.createLinkId("dummy"))
      case _ =>
    }
  }
}
