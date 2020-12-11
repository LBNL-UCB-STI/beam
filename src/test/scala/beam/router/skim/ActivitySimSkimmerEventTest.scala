package beam.router.skim

import beam.router.Modes.BeamMode
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import org.mockito.Mockito.when
import org.scalatest.{FlatSpec, Matchers}
import org.scalatestplus.mockito.MockitoSugar

class ActivitySimSkimmerEventTest extends FlatSpec with Matchers with MockitoSugar {

  def mockLeg(duration: Int, mode: BeamMode): EmbodiedBeamLeg = {
    val beamPath = mock[BeamPath]
    val beamLeg = mock[BeamLeg]
    val leg = mock[EmbodiedBeamLeg]
    when(beamLeg.travelPath).thenReturn(beamPath)
    when(beamLeg.mode).thenReturn(mode)
    when(beamLeg.duration).thenReturn(duration)
    when(leg.beamLeg).thenReturn(beamLeg)
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
    event.skimInternal.walkAccess shouldBe 25
    event.skimInternal.walkEgress shouldBe 10
    event.skimInternal.walkAuxiliary shouldBe 30
    event.skimInternal.totalInVehicleTime shouldBe 30
    event.key.pathType shouldBe ActivitySimPathType.WLK_LOC_DRV
  }
  "skimmer event" should "parse trip 2" in {
    val trip = new EmbodiedBeamTrip(
      IndexedSeq(carLeg15, walkLeg15, busLeg10, walkLeg15, carLeg10, walkLeg10)
    )
    val event = ActivitySimSkimmerEvent("o1", "d1", 10 * 60 * 60, trip, 100, 200, 10, "skimname")
    event.skimInternal.walkAccess shouldBe 0
    event.skimInternal.walkEgress shouldBe 10
    event.skimInternal.walkAuxiliary shouldBe 30
    event.skimInternal.totalInVehicleTime shouldBe 35
    event.key.pathType shouldBe ActivitySimPathType.DRV_LOC_WLK
  }
  "skimmer event" should "parse trip 3" in {
    val trip = new EmbodiedBeamTrip(
      IndexedSeq(busLeg10, walkLeg15, busLeg10, walkLeg10, busLeg10, walkLeg15, walkLeg10, walkLeg15, busLeg15)
    )
    val event = ActivitySimSkimmerEvent("o1", "d1", 10 * 60 * 60, trip, 100, 200, 10, "skimname")
    event.skimInternal.walkAccess shouldBe 0
    event.skimInternal.walkEgress shouldBe 0
    event.skimInternal.walkAuxiliary shouldBe 65
    event.skimInternal.totalInVehicleTime shouldBe 45
    event.key.pathType shouldBe ActivitySimPathType.WLK_LOC_WLK
  }
  "skimmer event" should "parse trip 4" in {
    val trip = new EmbodiedBeamTrip(
      IndexedSeq(walkLeg15, busLeg10, walkLeg15, walkLeg10)
    )
    val event = ActivitySimSkimmerEvent("o1", "d1", 10 * 60 * 60, trip, 100, 200, 10, "skimname")
    event.skimInternal.walkAccess shouldBe 15
    event.skimInternal.walkEgress shouldBe 25
    event.skimInternal.walkAuxiliary shouldBe 0
    event.skimInternal.totalInVehicleTime shouldBe 10
    event.key.pathType shouldBe ActivitySimPathType.WLK_LOC_WLK
  }
  "skimmer event" should "parse trip 5" in {
    val trip = new EmbodiedBeamTrip(
      IndexedSeq(walkLeg15, walkLeg15, carLeg10)
    )
    val event = ActivitySimSkimmerEvent("o1", "d1", 10 * 60 * 60, trip, 100, 200, 10, "skimname")
    event.skimInternal.walkAccess shouldBe 30
    event.skimInternal.walkEgress shouldBe 0
    event.skimInternal.walkAuxiliary shouldBe 0
    event.skimInternal.totalInVehicleTime shouldBe 10
    event.key.pathType shouldBe ActivitySimPathType.SOV
  }
}
