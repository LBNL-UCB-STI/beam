package beam.replanning

import java.nio.file.Files
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.router.Modes.BeamMode
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.utils.csv.readers.BeamCsvScenarioReader
import beam.utils.csv.writers.PlansCsvWriter
import beam.utils.scenario.generic.readers.CsvPlanElementReader
import org.matsim.api.core.v01.population.{Activity, Leg, Person, Plan}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup
import org.matsim.core.population.PopulationUtils
import org.matsim.core.scenario.ScenarioUtils
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.JavaConverters._

class ReplanningUtilSpec extends AnyWordSpec with Matchers {

  private def createTestPerson(id: String): Person = {
    PopulationUtils.getFactory.createPerson(Id.createPersonId(id))
  }

  private def createPlanWithActivitiesAndLegs(person: Person, numLegs: Int, mode: String = "car"): Plan = {
    val plan = PopulationUtils.createPlan(person)
    PopulationUtils.createAndAddActivityFromCoord(plan, "Home", new Coord(0.0, 0.0))
    for (i <- 0 until numLegs) {
      val leg = PopulationUtils.createLeg(mode)
      plan.addLeg(leg)
      PopulationUtils.createAndAddActivityFromCoord(plan, s"Act_$i", new Coord(i * 10.0, i * 10.0))
    }
    plan
  }

  "ReplanningUtil" when {
    "makeExperiencedMobSimCompatible" should {
      "skip replacing selectedPlan when beamRealizedUpdated is true" in {
        val person = createTestPerson("p1")
        val selectedPlan = createPlanWithActivitiesAndLegs(person, 1, "car")
        selectedPlan.setScore(100.0)
        selectedPlan.getAttributes.putAttribute("beamRealizedUpdated", true)
        person.addPlan(selectedPlan)
        person.setSelectedPlan(selectedPlan)

        val experiencedPlan = createPlanWithActivitiesAndLegs(person, 1, "walk")
        experiencedPlan.setScore(50.0)
        selectedPlan.getCustomAttributes.put(PlanCalcScoreConfigGroup.EXPERIENCED_PLAN_KEY, experiencedPlan)

        ReplanningUtil.makeExperiencedMobSimCompatible(person)

        person.getSelectedPlan shouldBe selectedPlan
        person.getSelectedPlan.getScore shouldBe 100.0
        val leg = person.getSelectedPlan.getPlanElements.asScala.collectFirst { case l: Leg => l }.get
        leg.getMode shouldBe "car"
      }

      "skip replacing selectedPlan when leg count mismatches to avoid multimodal corruption" in {
        val person = createTestPerson("p2")
        val selectedPlan = createPlanWithActivitiesAndLegs(person, 1, "car")
        selectedPlan.setScore(80.0)
        person.addPlan(selectedPlan)
        person.setSelectedPlan(selectedPlan)

        // Experienced plan has 2 legs (e.g. walk + transit)
        val experiencedPlan = createPlanWithActivitiesAndLegs(person, 2, "walk")
        experiencedPlan.setScore(40.0)
        selectedPlan.getCustomAttributes.put(PlanCalcScoreConfigGroup.EXPERIENCED_PLAN_KEY, experiencedPlan)

        ReplanningUtil.makeExperiencedMobSimCompatible(person)

        person.getSelectedPlan shouldBe selectedPlan
        person.getSelectedPlan.getScore shouldBe 80.0
      }

      "preserve score, standard custom leg attributes, and arbitrary leg/plan attributes when replacing selectedPlan" in {
        val person = createTestPerson("p3")
        val selectedPlan = createPlanWithActivitiesAndLegs(person, 1, "car")
        selectedPlan.setScore(95.5)
        selectedPlan.getAttributes.putAttribute("custom_plan_attr", "my_custom_val")
        person.addPlan(selectedPlan)
        person.setSelectedPlan(selectedPlan)

        val selectedLeg = selectedPlan.getPlanElements.asScala.collectFirst { case l: Leg => l }.get
        selectedLeg.getAttributes.putAttribute("trip_id", "trip_100")
        selectedLeg.getAttributes.putAttribute("tour_id", "tour_200")
        selectedLeg.getAttributes.putAttribute("trip_dur_min", 25.0)
        selectedLeg.getAttributes.putAttribute("trip_cost_dollars", 3.75)
        selectedLeg.getAttributes.putAttribute("vehicles", "beamCar1")
        selectedLeg.getAttributes.putAttribute("custom_leg_attr", "my_custom_leg_val")

        val experiencedPlan = createPlanWithActivitiesAndLegs(person, 1, "car")
        experiencedPlan.setScore(null.asInstanceOf[java.lang.Double])
        selectedPlan.getCustomAttributes.put(PlanCalcScoreConfigGroup.EXPERIENCED_PLAN_KEY, experiencedPlan)

        ReplanningUtil.makeExperiencedMobSimCompatible(person)

        person.getSelectedPlan shouldBe experiencedPlan
        experiencedPlan.getScore shouldBe 95.5
        experiencedPlan.getAttributes.getAttribute("custom_plan_attr") shouldBe "my_custom_val"
        experiencedPlan.getAttributes.getAttribute("modality-style") shouldBe ""
        experiencedPlan.getAttributes.getAttribute("scores") shouldBe ""

        val expLeg = experiencedPlan.getPlanElements.asScala.collectFirst { case l: Leg => l }.get
        expLeg.getAttributes.getAttribute("trip_id") shouldBe "trip_100"
        expLeg.getAttributes.getAttribute("tour_id") shouldBe "tour_200"
        expLeg.getAttributes.getAttribute("trip_dur_min") shouldBe 25.0
        expLeg.getAttributes.getAttribute("trip_cost_dollars") shouldBe 3.75
        expLeg.getAttributes.getAttribute("vehicles") shouldBe "beamCar1"
        expLeg.getAttributes.getAttribute("custom_leg_attr") shouldBe "my_custom_leg_val"
      }
    }

    "addBeamTripsToPlanWithOnlyActivities" should {
      "preserve plan-level attributes and score from originalPlan to newPlan" in {
        val person = createTestPerson("p4")
        val originalPlan = PopulationUtils.createPlan(person)
        PopulationUtils.createAndAddActivityFromCoord(originalPlan, "Home", new Coord(0.0, 0.0))
        PopulationUtils.createAndAddActivityFromCoord(originalPlan, "Work", new Coord(10.0, 10.0))
        originalPlan.setScore(77.5)
        originalPlan.getAttributes.putAttribute("modality-style", "active")
        originalPlan.getAttributes.putAttribute("beamRealizedUpdated", true)

        val dummyBeamLeg = BeamLeg(
          startTime = 3600,
          mode = BeamMode.CAR,
          duration = 600,
          travelPath = BeamPath.empty
        )
        val dummyEmbodiedLeg = EmbodiedBeamLeg(
          beamLeg = dummyBeamLeg,
          beamVehicleId = Id.createVehicleId("veh1"),
          beamVehicleTypeId = Id.create("CAR", classOf[BeamVehicleType]),
          asDriver = true,
          cost = 2.0,
          unbecomeDriverOnCompletion = true
        )
        val dummyTrip = EmbodiedBeamTrip(legs = Vector(dummyEmbodiedLeg))

        val newPlan = ReplanningUtil.addBeamTripsToPlanWithOnlyActivities(originalPlan, Vector(dummyTrip))

        newPlan.getScore shouldBe 77.5
        newPlan.getAttributes.getAttribute("modality-style") shouldBe "active"
        newPlan.getAttributes.getAttribute("beamRealizedUpdated") shouldBe true
        newPlan.getPlanElements should have size 3
        val leg = newPlan.getPlanElements.get(1).asInstanceOf[Leg]
        leg.getMode shouldBe "car"
        leg.getDepartureTime.seconds shouldBe 3600.0
        leg.getTravelTime.seconds shouldBe 600.0
        leg.getAttributes.getAttribute("vehicles") shouldBe "veh1"
      }

      "handle multi-leg transit trip (walk -> bus -> walk) as single plan leg with walk_transit mode" in {
        val person = createTestPerson("p5")
        val originalPlan = PopulationUtils.createPlan(person)
        PopulationUtils.createAndAddActivityFromCoord(originalPlan, "Home", new Coord(0.0, 0.0))
        PopulationUtils.createAndAddActivityFromCoord(originalPlan, "Work", new Coord(10.0, 10.0))

        val walk1 = EmbodiedBeamLeg(
          beamLeg = BeamLeg(startTime = 28800, mode = BeamMode.WALK, duration = 300, travelPath = BeamPath.empty),
          beamVehicleId = Id.createVehicleId("body_p5"),
          beamVehicleTypeId = Id.create("BODY", classOf[BeamVehicleType]),
          asDriver = true,
          cost = 0.0,
          unbecomeDriverOnCompletion = false
        )
        val busLeg = EmbodiedBeamLeg(
          beamLeg = BeamLeg(startTime = 29100, mode = BeamMode.BUS, duration = 1200, travelPath = BeamPath.empty),
          beamVehicleId = Id.createVehicleId("bus_42"),
          beamVehicleTypeId = Id.create("BUS", classOf[BeamVehicleType]),
          asDriver = false,
          cost = 2.50,
          unbecomeDriverOnCompletion = false
        )
        val walk2 = EmbodiedBeamLeg(
          beamLeg = BeamLeg(startTime = 30300, mode = BeamMode.WALK, duration = 300, travelPath = BeamPath.empty),
          beamVehicleId = Id.createVehicleId("body_p5"),
          beamVehicleTypeId = Id.create("BODY", classOf[BeamVehicleType]),
          asDriver = true,
          cost = 0.0,
          unbecomeDriverOnCompletion = true
        )
        val transitTrip = EmbodiedBeamTrip(legs = Vector(walk1, busLeg, walk2))
        transitTrip.tripClassifier shouldBe BeamMode.WALK_TRANSIT
        transitTrip.totalTravelTimeInSecs shouldBe 1800

        val newPlan = ReplanningUtil.addBeamTripsToPlanWithOnlyActivities(originalPlan, Vector(transitTrip))

        newPlan.getPlanElements should have size 3
        val leg = newPlan.getPlanElements.get(1).asInstanceOf[Leg]
        leg.getMode shouldBe "walk_transit"
        leg.getDepartureTime.seconds shouldBe 28800.0
        leg.getTravelTime.seconds shouldBe 1800.0
        leg.getAttributes.getAttribute("vehicles").toString should include("bus_42")
      }
    }

    "in-place realized leg updates" should {
      "update leg mode, departure time, travel time, and vehicles from multimodal transit trip and tag beamRealizedUpdated" in {
        val person = createTestPerson("p6")
        val plan = createPlanWithActivitiesAndLegs(person, 1, "car")
        plan.setScore(50.0)
        person.addPlan(plan)
        person.setSelectedPlan(plan)

        val plannedLeg = plan.getPlanElements.asScala.collectFirst { case l: Leg => l }.get
        plannedLeg.getAttributes.putAttribute("trip_id", "trip_99")
        plannedLeg.getAttributes.putAttribute("tour_id", "tour_99")
        plannedLeg.getAttributes.putAttribute("trip_dur_min", 30.0)
        plannedLeg.getAttributes.putAttribute("trip_cost_dollars", 2.50)

        val walk1 = EmbodiedBeamLeg(
          beamLeg = BeamLeg(startTime = 28800, mode = BeamMode.WALK, duration = 300, travelPath = BeamPath.empty),
          beamVehicleId = Id.createVehicleId("body_p6"),
          beamVehicleTypeId = Id.create("BODY", classOf[BeamVehicleType]),
          asDriver = true,
          cost = 0.0,
          unbecomeDriverOnCompletion = false
        )
        val busLeg = EmbodiedBeamLeg(
          beamLeg = BeamLeg(startTime = 29100, mode = BeamMode.BUS, duration = 1200, travelPath = BeamPath.empty),
          beamVehicleId = Id.createVehicleId("bus_77"),
          beamVehicleTypeId = Id.create("BUS", classOf[BeamVehicleType]),
          asDriver = false,
          cost = 2.50,
          unbecomeDriverOnCompletion = false
        )
        val walk2 = EmbodiedBeamLeg(
          beamLeg = BeamLeg(startTime = 30300, mode = BeamMode.WALK, duration = 300, travelPath = BeamPath.empty),
          beamVehicleId = Id.createVehicleId("body_p6"),
          beamVehicleTypeId = Id.create("BODY", classOf[BeamVehicleType]),
          asDriver = true,
          cost = 0.0,
          unbecomeDriverOnCompletion = true
        )
        val realizedTrip = EmbodiedBeamTrip(legs = Vector(walk1, busLeg, walk2))
        val trips = Vector(realizedTrip)
        val effectivePersonLegs = plan.getPlanElements.asScala.collect { case leg: Leg => leg }

        // Execute in-place leg update logic matching BeamScoringFunctionFactory.finish()
        if (trips.nonEmpty) {
          trips.zip(effectivePersonLegs).foreach { case (trip, leg) =>
            leg.setMode(trip.tripClassifier.value)
            leg.setTravelTime(trip.totalTravelTimeInSecs.toDouble)
            trip.legs.headOption.foreach { firstBeamLeg =>
              leg.setDepartureTime(firstBeamLeg.beamLeg.startTime.toDouble)
            }
            leg.getAttributes.putAttribute("vehicles", trip.vehiclesInTrip.mkString(","))
          }
          person.getSelectedPlan.getAttributes.putAttribute("beamRealizedUpdated", true)
        }

        plannedLeg.getMode shouldBe "walk_transit"
        plannedLeg.getDepartureTime.seconds shouldBe 28800.0
        plannedLeg.getTravelTime.seconds shouldBe 1800.0
        plannedLeg.getAttributes.getAttribute("vehicles").toString should include("bus_77")
        plannedLeg.getAttributes.getAttribute("trip_id") shouldBe "trip_99"
        plannedLeg.getAttributes.getAttribute("tour_id") shouldBe "tour_99"
        plannedLeg.getAttributes.getAttribute("trip_dur_min") shouldBe 30.0
        plannedLeg.getAttributes.getAttribute("trip_cost_dollars") shouldBe 2.50
        person.getSelectedPlan.getAttributes.getAttribute("beamRealizedUpdated") shouldBe true

        // With beamRealizedUpdated = true, makeExperiencedMobSimCompatible should skip replacing plan
        val brokenExperiencedPlan = createPlanWithActivitiesAndLegs(person, 3, "walk")
        person.getSelectedPlan.getCustomAttributes
          .put(PlanCalcScoreConfigGroup.EXPERIENCED_PLAN_KEY, brokenExperiencedPlan)

        ReplanningUtil.makeExperiencedMobSimCompatible(person)
        person.getSelectedPlan shouldBe plan
        person.getSelectedPlan.getPlanElements.asScala.collect { case l: Leg => l } should have size 1
      }
    }

    "plans CSV round-trip" should {
      "serialize and deserialize plans with empty strings, NaNs, and unexecuted plans without NumberFormatException" in {
        val scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
        val person = scenario.getPopulation.getFactory.createPerson(Id.createPersonId("agent_1"))

        // Plan 0: executed, selected, with valid score and leg attributes
        val plan0 = scenario.getPopulation.getFactory.createPlan()
        plan0.setScore(123.45)
        val act0 = scenario.getPopulation.getFactory.createActivityFromCoord("Home", new Coord(100.0, 200.0))
        act0.setEndTime(28800.0)
        plan0.addActivity(act0)
        val leg0 = scenario.getPopulation.getFactory.createLeg("car")
        leg0.setDepartureTime(28800.0)
        leg0.setTravelTime(1800.0)
        leg0.getAttributes.putAttribute("trip_id", "501")
        leg0.getAttributes.putAttribute("tour_id", "601")
        leg0.getAttributes.putAttribute("trip_dur_min", 25.0)
        leg0.getAttributes.putAttribute("trip_cost_dollars", 4.50)
        plan0.addLeg(leg0)
        val act1 = scenario.getPopulation.getFactory.createActivityFromCoord("Work", new Coord(300.0, 400.0))
        plan0.addActivity(act1)

        // Plan 1: unexecuted alternative, score = Double.NaN
        val plan1 = scenario.getPopulation.getFactory.createPlan()
        plan1.setScore(Double.NaN)
        val act10 = scenario.getPopulation.getFactory.createActivityFromCoord("Home", new Coord(100.0, 200.0))
        act10.setEndTime(29000.0)
        plan1.addActivity(act10)
        val leg1 = scenario.getPopulation.getFactory.createLeg("walk")
        leg1.setDepartureTime(29000.0)
        leg1.setTravelTime(3600.0)
        leg1.getAttributes.putAttribute("trip_id", "502")
        leg1.getAttributes.putAttribute("tour_id", "601")
        plan1.addLeg(leg1)
        val act11 = scenario.getPopulation.getFactory.createActivityFromCoord("Work", new Coord(300.0, 400.0))
        plan1.addActivity(act11)

        person.addPlan(plan0)
        person.addPlan(plan1)
        person.setSelectedPlan(plan0)
        scenario.getPopulation.addPerson(person)

        val tempFile = Files.createTempFile("test_plans", ".csv").toFile
        tempFile.deleteOnExit()

        // 1. Write plans to CSV
        PlansCsvWriter.toCsv(scenario, tempFile.getAbsolutePath)

        // 2. Read with CsvPlanElementReader
        val csvElements = CsvPlanElementReader.read(tempFile.getAbsolutePath)
        csvElements.length shouldBe 6 // 3 elements per plan * 2 plans

        val plan0Leg =
          csvElements.find(e => e.planIndex == 0 && e.planElementType == beam.utils.scenario.PlanElement.Leg).get
        plan0Leg.tripId shouldBe "501"
        plan0Leg.tourId shouldBe "601"
        plan0Leg.planScore shouldBe 123.45
        plan0Leg.planSelected shouldBe true
        plan0Leg.legMode shouldBe Some("car")
        plan0Leg.legDepartureTime shouldBe Some("28800.0")
        plan0Leg.legTravelTime shouldBe Some("1800.0")
        plan0Leg.legExpectedTravelTime shouldBe Some(25.0)
        plan0Leg.legExpectedCost shouldBe Some(4.5)

        val plan1Leg =
          csvElements.find(e => e.planIndex == 1 && e.planElementType == beam.utils.scenario.PlanElement.Leg).get
        plan1Leg.tripId shouldBe "502"
        plan1Leg.tourId shouldBe "601"
        plan1Leg.planScore.isNaN shouldBe true
        plan1Leg.planSelected shouldBe false
        plan1Leg.legMode shouldBe Some("walk")
        plan1Leg.legExpectedTravelTime shouldBe None
        plan1Leg.legExpectedCost shouldBe None

        // 3. Read with BeamCsvScenarioReader
        val scenarioElements = BeamCsvScenarioReader.readPlansFile(tempFile.getAbsolutePath)
        scenarioElements.length shouldBe 6

        val scPlan0Leg =
          scenarioElements.find(e => e.planIndex == 0 && e.planElementType == beam.utils.scenario.PlanElement.Leg).get
        scPlan0Leg.tripId shouldBe "501"
        scPlan0Leg.tourId shouldBe "601"
        scPlan0Leg.planScore shouldBe 123.45
        scPlan0Leg.legExpectedTravelTime shouldBe Some(25.0)
        scPlan0Leg.legExpectedCost shouldBe Some(4.5)

        val scPlan1Leg =
          scenarioElements.find(e => e.planIndex == 1 && e.planElementType == beam.utils.scenario.PlanElement.Leg).get
        scPlan1Leg.planScore.isNaN shouldBe true
        scPlan1Leg.planSelected shouldBe false
        scPlan1Leg.legExpectedTravelTime shouldBe None
        scPlan1Leg.legExpectedCost shouldBe None
      }
    }
  }
}
