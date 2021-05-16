package beam.router.r5

import beam.sim.config.BeamConfig.Beam.Physsim.Network
import beam.sim.config.BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties
import beam.sim.config.BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class NetworkCoordinatorTest extends AnyFunSuite with Matchers {
  private val highwayType: Network.OverwriteRoadTypeProperties = Network.OverwriteRoadTypeProperties(
    enabled = true,
    livingStreet = OverwriteRoadTypeProperties.LivingStreet(capacity = Some(1), lanes = Some(2), speed = Some(3)),
    minor = Minor(capacity = Some(4), lanes = Some(5), speed = Some(6)),
    motorway = Motorway(capacity = Some(7), lanes = Some(8), speed = Some(9)),
    motorwayLink = MotorwayLink(capacity = Some(10), lanes = Some(11), speed = Some(12)),
    primary = Primary(capacity = Some(13), lanes = Some(14), speed = Some(15)),
    primaryLink = PrimaryLink(capacity = Some(16), lanes = Some(17), speed = Some(18)),
    residential = Residential(capacity = Some(19), lanes = Some(20), speed = Some(21)),
    secondary = Secondary(capacity = Some(22), lanes = Some(23), speed = Some(24)),
    secondaryLink = SecondaryLink(capacity = Some(25), lanes = Some(26), speed = Some(27)),
    tertiary = Tertiary(capacity = Some(28), lanes = Some(29), speed = Some(30)),
    tertiaryLink = TertiaryLink(capacity = Some(31), lanes = Some(32), speed = Some(33)),
    trunk = Trunk(capacity = Some(34), lanes = Some(35), speed = Some(36)),
    trunkLink = TrunkLink(capacity = Some(37), lanes = Some(38), speed = Some(39)),
    unclassified = Unclassified(capacity = Some(40), lanes = Some(41), speed = Some(42))
  )

  test("getSpeeds should work properly") {
    // Check that in case when value is not provided
    val whenMissing =
      NetworkCoordinator.getSpeeds(highwayType.copy(livingStreet = highwayType.livingStreet.copy(speed = None)))
    assert(Option(whenMissing.get(HighwayType.LivingStreet)).isEmpty)

    val speeds = NetworkCoordinator.getSpeeds(highwayType)
    speeds.get(HighwayType.LivingStreet) shouldBe 3
    speeds.get(HighwayType.Minor) shouldBe 6
    speeds.get(HighwayType.Motorway) shouldBe 9
    speeds.get(HighwayType.MotorwayLink) shouldBe 12
    speeds.get(HighwayType.Primary) shouldBe 15
    speeds.get(HighwayType.PrimaryLink) shouldBe 18
    speeds.get(HighwayType.Residential) shouldBe 21
    speeds.get(HighwayType.Secondary) shouldBe 24
    speeds.get(HighwayType.SecondaryLink) shouldBe 27
    speeds.get(HighwayType.Tertiary) shouldBe 30
    speeds.get(HighwayType.TertiaryLink) shouldBe 33
    speeds.get(HighwayType.Trunk) shouldBe 36
    speeds.get(HighwayType.TrunkLink) shouldBe 39
    speeds.get(HighwayType.Unclassified) shouldBe 42
  }

  test("getCapacities should work properly") {
    // Check that in case when value is not provided
    val whenMissing =
      NetworkCoordinator.getCapacities(highwayType.copy(livingStreet = highwayType.livingStreet.copy(capacity = None)))
    assert(Option(whenMissing.get(HighwayType.LivingStreet)).isEmpty)

    val capacities = NetworkCoordinator.getCapacities(highwayType)
    capacities.get(HighwayType.LivingStreet) shouldBe 1
    capacities.get(HighwayType.Minor) shouldBe 4
    capacities.get(HighwayType.Motorway) shouldBe 7
    capacities.get(HighwayType.MotorwayLink) shouldBe 10
    capacities.get(HighwayType.Primary) shouldBe 13
    capacities.get(HighwayType.PrimaryLink) shouldBe 16
    capacities.get(HighwayType.Residential) shouldBe 19
    capacities.get(HighwayType.Secondary) shouldBe 22
    capacities.get(HighwayType.SecondaryLink) shouldBe 25
    capacities.get(HighwayType.Tertiary) shouldBe 28
    capacities.get(HighwayType.TertiaryLink) shouldBe 31
    capacities.get(HighwayType.Trunk) shouldBe 34
    capacities.get(HighwayType.TrunkLink) shouldBe 37
    capacities.get(HighwayType.Unclassified) shouldBe 40
  }

  test("getLanes should work properly") {
    // Check that in case when value is not provided
    val whenMissing =
      NetworkCoordinator.getLanes(highwayType.copy(livingStreet = highwayType.livingStreet.copy(lanes = None)))
    assert(Option(whenMissing.get(HighwayType.LivingStreet)).isEmpty)

    val lanes = NetworkCoordinator.getLanes(highwayType)
    lanes.get(HighwayType.LivingStreet) shouldBe 2
    lanes.get(HighwayType.Minor) shouldBe 5
    lanes.get(HighwayType.Motorway) shouldBe 8
    lanes.get(HighwayType.MotorwayLink) shouldBe 11
    lanes.get(HighwayType.Primary) shouldBe 14
    lanes.get(HighwayType.PrimaryLink) shouldBe 17
    lanes.get(HighwayType.Residential) shouldBe 20
    lanes.get(HighwayType.Secondary) shouldBe 23
    lanes.get(HighwayType.SecondaryLink) shouldBe 26
    lanes.get(HighwayType.Tertiary) shouldBe 29
    lanes.get(HighwayType.TertiaryLink) shouldBe 32
    lanes.get(HighwayType.Trunk) shouldBe 35
    lanes.get(HighwayType.TrunkLink) shouldBe 38
    lanes.get(HighwayType.Unclassified) shouldBe 41
  }
}
