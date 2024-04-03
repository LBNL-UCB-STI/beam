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

  def mockLeg(
    durationInSeconds: Int,
    mode: BeamMode,
    isRideHail: Boolean = false,
    vehicleId: String = "MockVechicleId",
    startTime: Int = 0,
    endTime: Int = 0
  ): EmbodiedBeamLeg = {
    val beamPath = Mockito.mock(classOf[BeamPath])
    val beamLeg = Mockito.mock(classOf[BeamLeg])
    val leg = Mockito.mock(classOf[EmbodiedBeamLeg])
    when(beamPath.distanceInM).thenReturn(durationInSeconds * 15.0)
    when(beamLeg.travelPath).thenReturn(beamPath)
    when(beamLeg.mode).thenReturn(mode)
    when(beamLeg.duration).thenReturn(durationInSeconds)
    when(beamLeg.startTime).thenReturn(startTime)
    when(beamLeg.endTime).thenReturn(endTime)
    when(leg.beamLeg).thenReturn(beamLeg)
    when(leg.isRideHail).thenReturn(isRideHail)
    when(leg.beamVehicleId).thenReturn(Id.createVehicleId(vehicleId))
    leg
  }

  def mockActivity(activityType: String): Activity = {
    PopulationUtils.createActivityFromCoord(activityType, new Coord(0, 0))
  }

  def walkLeg10: EmbodiedBeamLeg = mockLeg(10, BeamMode.WALK, startTime = 215, endTime = 225)
  def walkLeg15: EmbodiedBeamLeg = mockLeg(15, BeamMode.WALK, startTime = 100, endTime = 115)
  def carLeg10: EmbodiedBeamLeg = mockLeg(10, BeamMode.CAR)
  def carLeg15: EmbodiedBeamLeg = mockLeg(15, BeamMode.CAR)
  def busLeg10: EmbodiedBeamLeg = mockLeg(10, BeamMode.BUS, startTime = 175, endTime = 185)
  def busLeg15: EmbodiedBeamLeg = mockLeg(15, BeamMode.BUS)
  def railLeg15: EmbodiedBeamLeg = mockLeg(15, BeamMode.SUBWAY, startTime = 200, endTime = 215)

  def rideHailLeg10: EmbodiedBeamLeg = mockLeg(
    600,
    BeamMode.CAR,
    isRideHail = true,
    vehicleId = "rideHailVehicle-1@GlobalRHM",
    startTime = 10 * 60 * 60 - 600,
    endTime = 10 * 60 * 60
  )

  def waitLeg(startTime: Int, endTime: Int): EmbodiedBeamLeg =
    mockLeg(durationInSeconds = 0, BeamMode.WALK, vehicleId = "", startTime = startTime, endTime = endTime)

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
    event.skimInternal.waitInitialInMinutes shouldBe 60 / 60.0
    event.skimInternal.waitAuxiliaryInMinutes shouldBe 15 / 60.0
    event.key.pathType shouldBe ActivitySimPathType.WLK_HVY_WLK
  }

  "skimmer event" should "parse trip 7" in {
    val l1 = waitLeg(startTime = 10 * 60 * 60 - 360 - 600, endTime = 10 * 60 * 60 - 360 - 600)
    val l2 = rideHailLeg10
    val l3 = waitLeg(startTime = 10 * 60 * 60, endTime = 10 * 60 * 60)
    val trip = new EmbodiedBeamTrip(
      IndexedSeq(
        l1,
        l2,
        l3
      )
    )
    val event = ActivitySimSkimmerEvent("o1", "d1", 10 * 60 * 60, trip, 100, 200, 10, "skimname")
    event.skimInternal.walkAccessInMinutes shouldBe 0
    event.skimInternal.walkEgressInMinutes shouldBe 0
    event.skimInternal.walkAuxiliaryInMinutes shouldBe 0
    event.skimInternal.totalInVehicleTimeInMinutes shouldBe 10
    event.skimInternal.waitInitialInMinutes shouldBe 6.0
    event.skimInternal.driveDistanceInMeters shouldBe 9000.0
    event.key.pathType shouldBe ActivitySimPathType.TNC_SINGLE
    event.key.fleet shouldBe Some("GlobalRHM")
  }

  "skimmer event" should "parse trip 8" in {
    val l1 = waitLeg(startTime = 10 * 60 * 60 - 360 - 600, endTime = 10 * 60 * 60 - 360 - 600)
    val l2 = rideHailLeg10
    val l3 = waitLeg(10 * 60 * 60, 10 * 60 * 60)
    val l4 = mockLeg(150, BeamMode.SUBWAY, startTime = 10 * 60 * 60)
    val l5 = mockLeg(120, BeamMode.WALK, startTime = 10 * 60 * 60 + 150, endTime = 10 * 60 * 60 + 150 + 120)
    val trip = new EmbodiedBeamTrip(
      IndexedSeq(
        l1,
        l2,
        l3,
        l4,
        l5
      )
    )
    val event = ActivitySimSkimmerEvent("o1", "d1", 10 * 60 * 60, trip, 100, 200, 10, "skimname")
    event.skimInternal.walkAccessInMinutes shouldBe 0
    event.skimInternal.walkEgressInMinutes shouldBe 2.0
    event.skimInternal.walkAuxiliaryInMinutes shouldBe 0
    event.skimInternal.totalInVehicleTimeInMinutes shouldBe 12.5
    event.skimInternal.waitInitialInMinutes shouldBe 6.0
    event.skimInternal.driveDistanceInMeters shouldBe 9000.0
    event.skimInternal.keyInVehicleTimeInMinutes shouldBe 2.5
    event.key.pathType shouldBe ActivitySimPathType.TNC_SINGLE_TRANSIT
    event.key.fleet shouldBe Some("GlobalRHM")
  }

  "skimmer event" should "parse failed trip 1" in {
    val pathType = ActivitySimPathType.determineActivitySimPathTypesFromBeamMode(Some(BeamMode.CAR), Some(homeActivity))
    pathType.length should be > 1
    val event = ActivitySimSkimmerFailedTripEvent(
      "o1",
      "d1",
      10 * 60 * 60,
      pathType.filter { path => path == ActivitySimPathType.SOV }.head,
      None,
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
      ActivitySimPathType.determineActivitySimPathTypesFromBeamMode(Some(BeamMode.WALK_TRANSIT), Some(homeActivity))
    pathType.length should be > 1
    val event = ActivitySimSkimmerFailedTripEvent(
      "o1",
      "d1",
      10 * 60 * 60,
      pathType.filter { path => path == ActivitySimPathType.WLK_LOC_WLK }.head,
      None,
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
      ActivitySimPathType.determineActivitySimPathTypesFromBeamMode(Some(BeamMode.DRIVE_TRANSIT), Some(homeActivity))
    pathType.length should be > 1
    // If we're starting a DRIVE_TRANSIT trip at home, we assume the car is used for access but not egress
    pathType.count(_ == ActivitySimPathType.WLK_LOC_DRV) shouldBe 0
    val event = ActivitySimSkimmerFailedTripEvent(
      "o1",
      "d1",
      10 * 60 * 60,
      pathType.filter { path => path == ActivitySimPathType.DRV_LOC_WLK }.head,
      None,
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
      ActivitySimPathType.determineActivitySimPathTypesFromBeamMode(Some(BeamMode.DRIVE_TRANSIT), Some(workActivity))
    pathType.length should be > 1
    // If we're starting a DRIVE_TRANSIT trip at work, we assume the car is used for egress but not access
    pathType.count(_ == ActivitySimPathType.DRV_LOC_WLK) shouldBe 0
    val event = ActivitySimSkimmerFailedTripEvent(
      "o1",
      "d1",
      10 * 60 * 60,
      pathType.filter { path => path == ActivitySimPathType.WLK_LOC_DRV }.head,
      None,
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
