package beam.router.skim

import beam.router.Modes.BeamMode
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.population.Activity
import org.matsim.core.population.PopulationUtils
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.mockito.Mockito

class ActivitySimSkimmerEventTest extends AnyFlatSpec with Matchers {

  def mockLeg(duration: Int, mode: BeamMode): EmbodiedBeamLeg = {
    val beamPath = Mockito.mock(classOf[BeamPath])
    val beamLeg = Mockito.mock(classOf[BeamLeg])
    val leg = Mockito.mock(classOf[EmbodiedBeamLeg])
    when(beamLeg.travelPath).thenReturn(beamPath)
    when(beamLeg.mode).thenReturn(mode)
    when(beamLeg.duration).thenReturn(duration)
    when(leg.beamLeg).thenReturn(beamLeg)
    when(leg.beamVehicleId).thenReturn(Id.createVehicleId("MockVechicleId"))
    leg
  }

  def mockActivity(activityType: String): Activity = {
    PopulationUtils.createActivityFromCoord(activityType, new Coord(0, 0))
  }

  def walkLeg10: EmbodiedBeamLeg = mockLeg(10, BeamMode.WALK)
  def walkLeg15: EmbodiedBeamLeg = mockLeg(15, BeamMode.WALK)
  def carLeg10: EmbodiedBeamLeg = mockLeg(10, BeamMode.CAR)
  def carLeg15: EmbodiedBeamLeg = mockLeg(15, BeamMode.CAR)
  def busLeg10: EmbodiedBeamLeg = mockLeg(10, BeamMode.BUS)
  def busLeg15: EmbodiedBeamLeg = mockLeg(15, BeamMode.BUS)
  def railLeg15: EmbodiedBeamLeg = mockLeg(15, BeamMode.SUBWAY)

  def homeActivity: Activity = mockActivity("Home")
  def workActivity: Activity = mockActivity("Work")

  "skimmer event" should "parse trip 1" in {
    val trip = new EmbodiedBeamTrip(
      IndexedSeq(walkLeg10, walkLeg15, busLeg10, walkLeg15, busLeg10, walkLeg15, carLeg10, walkLeg10)
    )
    val event = ActivitySimSkimmerEvent("o1", "d1", 10 * 60 * 60, trip, 100, 200, 10, "skimname")
    event.skimInternal.walkAccessInMinutes shouldBe 25 / 60.0
    event.skimInternal.walkEgressInMinutes shouldBe 10 / 60.0
    event.skimInternal.walkAuxiliaryInMinutes shouldBe 30 / 60.0
    event.skimInternal.totalInVehicleTimeInMinutes shouldBe 30 / 60.0
    event.key.pathType shouldBe ActivitySimPathType.WLK_LOC_DRV
  }
  "skimmer event" should "parse trip 2" in {
    val trip = new EmbodiedBeamTrip(
      IndexedSeq(carLeg15, walkLeg15, busLeg10, walkLeg15, carLeg10, walkLeg10)
    )
    val event = ActivitySimSkimmerEvent("o1", "d1", 10 * 60 * 60, trip, 100, 200, 10, "skimname")
    event.skimInternal.walkAccessInMinutes shouldBe 0 / 60.0
    event.skimInternal.walkEgressInMinutes shouldBe 10 / 60.0
    event.skimInternal.walkAuxiliaryInMinutes shouldBe 30 / 60.0
    event.skimInternal.totalInVehicleTimeInMinutes shouldBe 35 / 60.0
    event.key.pathType shouldBe ActivitySimPathType.DRV_LOC_WLK
  }
  "skimmer event" should "parse trip 3" in {
    val trip = new EmbodiedBeamTrip(
      IndexedSeq(busLeg10, walkLeg15, busLeg10, walkLeg10, busLeg10, walkLeg15, walkLeg10, walkLeg15, busLeg15)
    )
    val event = ActivitySimSkimmerEvent("o1", "d1", 10 * 60 * 60, trip, 100, 200, 10, "skimname")
    event.skimInternal.walkAccessInMinutes shouldBe 0 / 60.0
    event.skimInternal.walkEgressInMinutes shouldBe 0 / 60.0
    event.skimInternal.walkAuxiliaryInMinutes shouldBe 65 / 60.0
    event.skimInternal.totalInVehicleTimeInMinutes shouldBe 45 / 60.0
    event.key.pathType shouldBe ActivitySimPathType.WLK_LOC_WLK
  }
  "skimmer event" should "parse trip 4" in {
    val trip = new EmbodiedBeamTrip(
      IndexedSeq(walkLeg15, busLeg10, walkLeg15, walkLeg10)
    )
    val event = ActivitySimSkimmerEvent("o1", "d1", 10 * 60 * 60, trip, 100, 200, 10, "skimname")
    event.skimInternal.walkAccessInMinutes shouldBe 15 / 60.0
    event.skimInternal.walkEgressInMinutes shouldBe 25 / 60.0
    event.skimInternal.walkAuxiliaryInMinutes shouldBe 0 / 60.0
    event.skimInternal.totalInVehicleTimeInMinutes shouldBe 10 / 60.0
    event.key.pathType shouldBe ActivitySimPathType.WLK_LOC_WLK
  }
  "skimmer event" should "parse trip 5" in {
    val trip = new EmbodiedBeamTrip(
      IndexedSeq(walkLeg15, walkLeg15, carLeg10)
    )
    val event = ActivitySimSkimmerEvent("o1", "d1", 10 * 60 * 60, trip, 100, 200, 10, "skimname")
    event.skimInternal.walkAccessInMinutes shouldBe 30 / 60.0
    event.skimInternal.walkEgressInMinutes shouldBe 0 / 60.0
    event.skimInternal.walkAuxiliaryInMinutes shouldBe 0 / 60.0
    event.skimInternal.totalInVehicleTimeInMinutes shouldBe 10 / 60.0
    event.key.pathType shouldBe ActivitySimPathType.SOV
  }

  "skimmer event" should "parse trip 6" in {
    val trip = new EmbodiedBeamTrip(
      IndexedSeq(walkLeg15, busLeg10, railLeg15, walkLeg10)
    )
    val event = ActivitySimSkimmerEvent("o1", "d1", 10 * 60 * 60, trip, 100, 200, 10, "skimname")
    event.skimInternal.walkAccessInMinutes shouldBe 15 / 60.0
    event.skimInternal.walkEgressInMinutes shouldBe 10 / 60.0
    event.skimInternal.walkAuxiliaryInMinutes shouldBe 0 / 60.0
    event.skimInternal.totalInVehicleTimeInMinutes shouldBe 25 / 60.0
    event.skimInternal.keyInVehicleTimeInMinutes shouldBe 15 / 60.0
    event.key.pathType shouldBe ActivitySimPathType.WLK_HVY_WLK
  }

  "skimmer event" should "parse failed trip 1" in {
    val pathType = ActivitySimPathType.determineActivitySimPathTypesFromBeamMode(Some(BeamMode.CAR), homeActivity)
    pathType.length should be > 1
    val event = ActivitySimSkimmerFailedTripEvent(
      "o1",
      "d1",
      10 * 60 * 60,
      pathType.filter { path => path == ActivitySimPathType.SOV }.head,
      0,
      "skimname"
    )
    event.getSkimmerInternal.walkAccessInMinutes shouldBe 0.0
    event.getSkimmerInternal.walkEgressInMinutes shouldBe 0.0
    event.getSkimmerInternal.walkAuxiliaryInMinutes shouldBe 0.0
    event.getSkimmerInternal.totalInVehicleTimeInMinutes shouldBe 0.0
    event.getSkimmerInternal.keyInVehicleTimeInMinutes shouldBe 0.0
    event.getSkimmerInternal.failedTrips shouldBe 1
    event.getSkimmerInternal.observations shouldBe 0
    event.getKey.pathType shouldBe ActivitySimPathType.SOV
  }

  "skimmer event" should "parse failed trip 2" in {
    val pathType =
      ActivitySimPathType.determineActivitySimPathTypesFromBeamMode(Some(BeamMode.WALK_TRANSIT), homeActivity)
    pathType.length should be > 1
    val event = ActivitySimSkimmerFailedTripEvent(
      "o1",
      "d1",
      10 * 60 * 60,
      pathType.filter { path => path == ActivitySimPathType.WLK_LOC_WLK }.head,
      0,
      "skimname"
    )
    event.getSkimmerInternal.walkAccessInMinutes shouldBe 0.0
    event.getSkimmerInternal.walkEgressInMinutes shouldBe 0.0
    event.getSkimmerInternal.walkAuxiliaryInMinutes shouldBe 0.0
    event.getSkimmerInternal.totalInVehicleTimeInMinutes shouldBe 0.0
    event.getSkimmerInternal.keyInVehicleTimeInMinutes shouldBe 0.0
    event.getSkimmerInternal.failedTrips shouldBe 1
    event.getSkimmerInternal.observations shouldBe 0
    event.getKey.pathType shouldBe ActivitySimPathType.WLK_LOC_WLK
  }

  "skimmer event" should "order access legs correctly in trip 3" in {
    val pathType =
      ActivitySimPathType.determineActivitySimPathTypesFromBeamMode(Some(BeamMode.DRIVE_TRANSIT), homeActivity)
    pathType.length should be > 1
    // If we're starting a DRIVE_TRANSIT trip at home, we assume the car is used for access but not egress
    pathType.count(_ == ActivitySimPathType.WLK_LOC_DRV) shouldBe 0
    val event = ActivitySimSkimmerFailedTripEvent(
      "o1",
      "d1",
      10 * 60 * 60,
      pathType.filter { path => path == ActivitySimPathType.DRV_LOC_WLK }.head,
      0,
      "skimname"
    )
    event.getSkimmerInternal.walkAccessInMinutes shouldBe 0.0
    event.getSkimmerInternal.walkEgressInMinutes shouldBe 0.0
    event.getSkimmerInternal.walkAuxiliaryInMinutes shouldBe 0.0
    event.getSkimmerInternal.totalInVehicleTimeInMinutes shouldBe 0.0
    event.getSkimmerInternal.keyInVehicleTimeInMinutes shouldBe 0.0
    event.getSkimmerInternal.failedTrips shouldBe 1
    event.getSkimmerInternal.observations shouldBe 0
    event.getKey.pathType shouldBe ActivitySimPathType.DRV_LOC_WLK
  }

  "skimmer event" should "order access legs correctly in trip 4" in {
    val pathType =
      ActivitySimPathType.determineActivitySimPathTypesFromBeamMode(Some(BeamMode.DRIVE_TRANSIT), workActivity)
    pathType.length should be > 1
    // If we're starting a DRIVE_TRANSIT trip at work, we assume the car is used for egress but not access
    pathType.count(_ == ActivitySimPathType.DRV_LOC_WLK) shouldBe 0
    val event = ActivitySimSkimmerFailedTripEvent(
      "o1",
      "d1",
      10 * 60 * 60,
      pathType.filter { path => path == ActivitySimPathType.WLK_LOC_DRV }.head,
      0,
      "skimname"
    )
    event.getSkimmerInternal.walkAccessInMinutes shouldBe 0.0
    event.getSkimmerInternal.walkEgressInMinutes shouldBe 0.0
    event.getSkimmerInternal.walkAuxiliaryInMinutes shouldBe 0.0
    event.getSkimmerInternal.totalInVehicleTimeInMinutes shouldBe 0.0
    event.getSkimmerInternal.keyInVehicleTimeInMinutes shouldBe 0.0
    event.getSkimmerInternal.failedTrips shouldBe 1
    event.getSkimmerInternal.observations shouldBe 0
    event.getKey.pathType shouldBe ActivitySimPathType.WLK_LOC_DRV
  }
}
