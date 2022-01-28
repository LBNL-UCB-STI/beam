package beam.router.skim

import beam.router.Modes.BeamMode
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import org.matsim.api.core.v01.Id
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

  def walkLeg10: EmbodiedBeamLeg = mockLeg(10, BeamMode.WALK)
  def walkLeg15: EmbodiedBeamLeg = mockLeg(15, BeamMode.WALK)
  def carLeg10: EmbodiedBeamLeg = mockLeg(10, BeamMode.CAR)
  def carLeg15: EmbodiedBeamLeg = mockLeg(15, BeamMode.CAR)
  def busLeg10: EmbodiedBeamLeg = mockLeg(10, BeamMode.BUS)
  def busLeg15: EmbodiedBeamLeg = mockLeg(15, BeamMode.BUS)

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
}
