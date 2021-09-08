package beam.agentsim.planning

import beam.agentsim.agents.planning.BeamPlan
import beam.agentsim.agents.planning.Strategy.ModeChoiceStrategy
import beam.router.Modes.BeamMode.CAR
import beam.sim.BeamHelper
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.population.{Activity, Plan}
import org.matsim.core.population.PopulationUtils
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.JavaConverters._

/**
  * BeamPlanSpec
  */
class BeamPlanSpec extends AnyWordSpecLike with Matchers with BeamHelper {

  "A BeamPlan" must {

    val matsimPlanOfActivities: Plan = PopulationUtils.createPlan(null)
    PopulationUtils.createAndAddActivityFromCoord(matsimPlanOfActivities, "Home", new Coord(0.0, 0.0))
    PopulationUtils.createAndAddActivityFromCoord(matsimPlanOfActivities, "Work", new Coord(0.0, 0.0))
    PopulationUtils.createAndAddActivityFromCoord(matsimPlanOfActivities, "Shop", new Coord(0.0, 0.0))
    PopulationUtils.createAndAddActivityFromCoord(matsimPlanOfActivities, "Home", new Coord(0.0, 0.0))
    val matsimPlan: Plan = PopulationUtils.createPlan(null)
    PopulationUtils.createAndAddActivityFromCoord(matsimPlan, "Home", new Coord(0.0, 0.0))
    PopulationUtils.createAndAddLeg(matsimPlan, "car")
    PopulationUtils.createAndAddActivityFromCoord(matsimPlan, "Work", new Coord(0.0, 0.0))
    PopulationUtils.createAndAddLeg(matsimPlan, "car")
    PopulationUtils.createAndAddActivityFromCoord(matsimPlan, "Shop", new Coord(0.0, 0.0))
    PopulationUtils.createAndAddLeg(matsimPlan, "car")
    PopulationUtils.createAndAddActivityFromCoord(matsimPlan, "Home", new Coord(0.0, 0.0))
    PopulationUtils.createAndAddLeg(matsimPlan, "car")
    PopulationUtils.createAndAddActivityFromCoord(matsimPlan, "Eat", new Coord(0.0, 0.0))
    PopulationUtils.createAndAddLeg(matsimPlan, "car")
    PopulationUtils.createAndAddActivityFromCoord(matsimPlan, "Home", new Coord(0.0, 0.0))

    val strat = ModeChoiceStrategy(Some(CAR))

    "should contain the same activities and legs as the MATSimn plan used in creation" in {
      val beamPlan = BeamPlan(matsimPlan)
      beamPlan.getPlanElements.asScala
        .zip(matsimPlan.getPlanElements.asScala)
        .forall(both => both._1.equals(both._2)) should be(true)
      matsimPlan.getPlanElements.asScala
        .zip(beamPlan.getPlanElements.asScala)
        .forall(both => both._1.equals(both._2)) should be(true)
    }
    "should attach a strategy to an activity" in {
      val beamPlan = BeamPlan(matsimPlan)
      val act = beamPlan.activities.head
      beamPlan.putStrategy(act, strat)
      beamPlan.getStrategy(act, classOf[ModeChoiceStrategy]) should be(Some(strat))
    }
    "should attach a strategy to a leg" in {
      val beamPlan = BeamPlan(matsimPlan)
      val leg = beamPlan.legs.head
      beamPlan.putStrategy(leg, strat)
      beamPlan.getStrategy(leg, classOf[ModeChoiceStrategy]) should be(Some(strat))
    }
    "should attach a strategy to a trip" in {
      val beamPlan = BeamPlan(matsimPlan)
      val trip = beamPlan.trips.head
      beamPlan.putStrategy(trip, strat)
      beamPlan.getStrategy(trip, classOf[ModeChoiceStrategy]) should be(Some(strat))
    }
    "should attach a strategy to a tour" in {
      val beamPlan = BeamPlan(matsimPlan)
      val tour = beamPlan.tours.head
      beamPlan.putStrategy(tour, strat)
      beamPlan.getStrategy(tour, classOf[ModeChoiceStrategy]) should be(Some(strat))
    }
    "should attach a strategy to a trip and the trip's activity and leg" in {
      val beamPlan = BeamPlan(matsimPlan)
      val trip = beamPlan.trips.head
      beamPlan.putStrategy(trip, strat)
      beamPlan.getStrategy(trip.activity, classOf[ModeChoiceStrategy]) should be(Some(strat))
      trip.leg match {
        case Some(leg) =>
          beamPlan.getStrategy(leg, classOf[ModeChoiceStrategy]) should be(Some(strat))
        case None =>
      }
    }
    "should attach a strategy to a tour and the tour's trips, activities, and trips" in {
      val beamPlan = BeamPlan(matsimPlan)
      val tour = beamPlan.tours.head
      beamPlan.putStrategy(tour, strat)
      tour.trips.foreach { trip =>
        beamPlan.getStrategy(trip, classOf[ModeChoiceStrategy]) should be(Some(strat))
        beamPlan.getStrategy(trip.activity, classOf[ModeChoiceStrategy]) should be(Some(strat))
        trip.leg match {
          case Some(leg) =>
            beamPlan.getStrategy(leg, classOf[ModeChoiceStrategy]) should be(Some(strat))
          case None =>
        }
      }
    }
    "should return a trip or tour containing a leg" in {
      val beamPlan = BeamPlan(matsimPlan)
      val tour = beamPlan.tours(2)
      val trip = tour.trips.head
      beamPlan.getTripContaining(trip.activity) should be(trip)
      beamPlan.getTripContaining(trip.leg.get) should be(trip)
      beamPlan.getTourContaining(trip.activity) should be(tour)
    }
    "should successfully add a leg between activities of an existing matsim plan" in {
      val newLeg = PopulationUtils.createLeg("FAKE")
      val newPlan = BeamPlan.addOrReplaceLegBetweenActivities(
        matsimPlanOfActivities,
        newLeg,
        matsimPlanOfActivities.getPlanElements.get(1).asInstanceOf[Activity],
        matsimPlanOfActivities.getPlanElements.get(2).asInstanceOf[Activity]
      )
      newPlan.getPlanElements.get(2) should be(newLeg)
    }
    "should successfully replace a leg between activities of an existing matsim plan" in {
      val newLeg = PopulationUtils.createLeg("FAKE")
      val newPlan = BeamPlan.addOrReplaceLegBetweenActivities(
        matsimPlan,
        newLeg,
        matsimPlan.getPlanElements.get(2).asInstanceOf[Activity],
        matsimPlan.getPlanElements.get(4).asInstanceOf[Activity]
      )
      newPlan.getPlanElements.get(3) should be(newLeg)
    }
  }
}
