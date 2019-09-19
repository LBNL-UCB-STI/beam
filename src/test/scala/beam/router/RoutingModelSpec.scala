package beam.router

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.events.SpaceTime
import beam.router.Modes.BeamMode
import beam.router.model.RoutingModel
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamLeg}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{LinkEnterEvent, LinkLeaveEvent}
import org.scalatest.{FlatSpec, Matchers}

class RoutingModelSpec extends FlatSpec with Matchers {

  it should "produce link events from a typical car leg, given a constant travel time function" in {
    def travelTime(enterTime: Int, linkId: Int) = 1000

    val leg = EmbodiedBeamLeg(
      BeamLeg(
        0,
        BeamMode.CAR,
        0,
        BeamPath(Vector(1, 2, 3, 4, 5), Vector(), None, SpaceTime.zero, SpaceTime.zero, 10.0)
      ),
      Id.createVehicleId(13),
      Id.create("Car", classOf[BeamVehicleType]),
      asDriver = true,
      0,
      unbecomeDriverOnCompletion = true
    )
    RoutingModel
      .traverseStreetLeg(leg.beamLeg, leg.beamVehicleId, travelTime)
      .toStream should contain theSameElementsAs Vector(
      new LinkLeaveEvent(0.0, Id.createVehicleId(13), Id.createLinkId(1)),
      new LinkEnterEvent(0.0, Id.createVehicleId(13), Id.createLinkId(2)),
      new LinkLeaveEvent(1000.0, Id.createVehicleId(13), Id.createLinkId(2)),
      new LinkEnterEvent(1000.0, Id.createVehicleId(13), Id.createLinkId(3)),
      new LinkLeaveEvent(2000.0, Id.createVehicleId(13), Id.createLinkId(3)),
      new LinkEnterEvent(2000.0, Id.createVehicleId(13), Id.createLinkId(4)),
      new LinkLeaveEvent(3000.0, Id.createVehicleId(13), Id.createLinkId(4)),
      new LinkEnterEvent(3000.0, Id.createVehicleId(13), Id.createLinkId(5))
    )
  }

  it should "produce link events from a typical car leg, given a travel time function with congestion later in the day" in {
    def travelTime(enterTime: Int, linkId: Int) =
      if (enterTime < 2000) 1000 else 2000

    val leg = EmbodiedBeamLeg(
      BeamLeg(
        0,
        BeamMode.CAR,
        0,
        BeamPath(Vector(1, 2, 3, 4, 5), Vector(), None, SpaceTime.zero, SpaceTime.zero, 10.0)
      ),
      Id.createVehicleId(13),
      Id.create("Car", classOf[BeamVehicleType]),
      asDriver = true,
      0,
      unbecomeDriverOnCompletion = true
    )
    RoutingModel
      .traverseStreetLeg(leg.beamLeg, leg.beamVehicleId, travelTime)
      .toStream should contain theSameElementsAs Vector(
      new LinkLeaveEvent(0.0, Id.createVehicleId(13), Id.createLinkId(1)),
      new LinkEnterEvent(0.0, Id.createVehicleId(13), Id.createLinkId(2)),
      new LinkLeaveEvent(1000.0, Id.createVehicleId(13), Id.createLinkId(2)),
      new LinkEnterEvent(1000.0, Id.createVehicleId(13), Id.createLinkId(3)),
      new LinkLeaveEvent(2000.0, Id.createVehicleId(13), Id.createLinkId(3)),
      new LinkEnterEvent(2000.0, Id.createVehicleId(13), Id.createLinkId(4)),
      new LinkLeaveEvent(4000.0, Id.createVehicleId(13), Id.createLinkId(4)),
      new LinkEnterEvent(4000.0, Id.createVehicleId(13), Id.createLinkId(5))
    )
  }

  it should "produce just one pair of link events for a leg which crosses just one node, spending no time" in {
    def travelTime(enterTime: Int, linkId: Int) = 1000

    val leg = EmbodiedBeamLeg(
      BeamLeg(
        0,
        BeamMode.CAR,
        0,
        BeamPath(Vector(1, 2), Vector(), None, SpaceTime.zero, SpaceTime.zero, 10.0)
      ),
      Id.createVehicleId(13),
      Id.create("Car", classOf[BeamVehicleType]),
      asDriver = true,
      0,
      unbecomeDriverOnCompletion = true
    )
    RoutingModel
      .traverseStreetLeg(leg.beamLeg, leg.beamVehicleId, travelTime)
      .toStream should contain theSameElementsAs Vector(
      new LinkLeaveEvent(0.0, Id.createVehicleId(13), Id.createLinkId(1)),
      new LinkEnterEvent(0.0, Id.createVehicleId(13), Id.createLinkId(2))
    )
  }

  it should "produce an empty sequence of link events from a car leg which stays on one link" in {
    def travelTime(enterTime: Int, linkId: Int) = 1000

    val leg = EmbodiedBeamLeg(
      BeamLeg(0, BeamMode.CAR, 0, BeamPath(Vector(1), Vector(), None, SpaceTime.zero, SpaceTime.zero, 10.0)),
      Id.createVehicleId(13),
      Id.create("Car", classOf[BeamVehicleType]),
      asDriver = true,
      0,
      unbecomeDriverOnCompletion = true
    )
    RoutingModel
      .traverseStreetLeg(leg.beamLeg, leg.beamVehicleId, travelTime)
      .toStream should be(
      'empty
    )
  }

  it should "produce an empty sequence of link events from a car leg which is empty" in {
    def travelTime(enterTime: Int, linkId: Int) = 1000

    val leg = EmbodiedBeamLeg(
      beamLeg = BeamLeg(0, BeamMode.CAR, 0, BeamPath(Vector(), Vector(), None, SpaceTime.zero, SpaceTime.zero, 10.0)),
      beamVehicleId = Id.createVehicleId(13),
      Id.create("Car", classOf[BeamVehicleType]),
      asDriver = true,
      cost = 0,
      unbecomeDriverOnCompletion = true
    )
    RoutingModel
      .traverseStreetLeg(leg.beamLeg, leg.beamVehicleId, travelTime)
      .toStream should be(
      'empty
    )
  }

  it should "produce travel and distance estimates from links that match router" in {
    def travelTime(enterTime: Int, linkId: Int) = 1000
    val leg = EmbodiedBeamLeg(
      beamLeg = BeamLeg(0, BeamMode.CAR, 0, BeamPath(Vector(), Vector(), None, SpaceTime.zero, SpaceTime.zero, 10.0)),
      beamVehicleId = Id.createVehicleId(13),
      Id.create("Car", classOf[BeamVehicleType]),
      asDriver = true,
      cost = 0,
      unbecomeDriverOnCompletion = true
    )
    RoutingModel.traverseStreetLeg(leg.beamLeg, leg.beamVehicleId, travelTime).toStream should be(
      'empty
    )
  }

}
