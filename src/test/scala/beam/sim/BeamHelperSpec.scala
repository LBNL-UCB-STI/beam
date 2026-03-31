package beam.sim

import akka.cluster.{Member, MemberStatus}
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.mockito.Mockito.{mock, when}
import org.scalatest.AppendedClues.convertToClueful
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/**
  * @author Dmitry Openkov
  */
class BeamHelperSpec extends AnyWordSpecLike with Matchers {
  "shouldAwaitRemoteWorkers" should {
    "be enabled for clustered masters without a local worker" in {
      val cfg = ConfigFactory
        .parseString("""
            |beam.cluster.enabled = true
            |beam.cluster.clusterType = master
            |beam.useLocalWorker = false
            |""".stripMargin)
        .withFallback(testConfig("test/input/beamville/beam.conf"))
        .resolve()

      BeamHelper.shouldAwaitRemoteWorkers(beam.sim.config.BeamConfig(cfg)) shouldBe true
    }

    "be disabled for workers and non-clustered runs" in {
      val workerCfg = ConfigFactory
        .parseString("""
            |beam.cluster.enabled = true
            |beam.cluster.clusterType = worker
            |beam.useLocalWorker = false
            |""".stripMargin)
        .withFallback(testConfig("test/input/beamville/beam.conf"))
        .resolve()

      val localCfg = ConfigFactory
        .parseString("""
            |beam.cluster.enabled = false
            |beam.useLocalWorker = true
            |""".stripMargin)
        .withFallback(testConfig("test/input/beamville/beam.conf"))
        .resolve()

      BeamHelper.shouldAwaitRemoteWorkers(beam.sim.config.BeamConfig(workerCfg)) shouldBe false
      BeamHelper.shouldAwaitRemoteWorkers(beam.sim.config.BeamConfig(localCfg)) shouldBe false
    }
  }

  "hasExpectedRemoteWorkersUp" should {
    "require the configured number of compute members to be Up" in {
      val cfg = ConfigFactory
        .parseString("""
            |beam.cluster.enabled = true
            |beam.cluster.clusterType = master
            |beam.cluster.expectedWorkerNodes = 2
            |beam.useLocalWorker = false
            |""".stripMargin)
        .withFallback(testConfig("test/input/beamville/beam.conf"))
        .resolve()

      val member1 = mock(classOf[Member])
      when(member1.hasRole("compute")).thenReturn(true)
      when(member1.status).thenReturn(MemberStatus.Up)

      val member2 = mock(classOf[Member])
      when(member2.hasRole("compute")).thenReturn(true)
      when(member2.status).thenReturn(MemberStatus.Up)

      val joining = mock(classOf[Member])
      when(joining.hasRole("compute")).thenReturn(true)
      when(joining.status).thenReturn(MemberStatus.Joining)

      BeamHelper.hasExpectedRemoteWorkersUp(beam.sim.config.BeamConfig(cfg), Seq(member1)) shouldBe false
      BeamHelper.hasExpectedRemoteWorkersUp(beam.sim.config.BeamConfig(cfg), Seq(member1, joining)) shouldBe false
      BeamHelper.hasExpectedRemoteWorkersUp(beam.sim.config.BeamConfig(cfg), Seq(member1, member2)) shouldBe true
    }
  }

  "upComputeMemberAddresses" should {
    "return only compute members that are Up" in {
      val member1 = mock(classOf[Member])
      when(member1.hasRole("compute")).thenReturn(true)
      when(member1.status).thenReturn(MemberStatus.Up)
      when(member1.address).thenReturn(akka.actor.Address("akka", "ClusterSystem", "127.0.0.1", 25521))

      val member2 = mock(classOf[Member])
      when(member2.hasRole("compute")).thenReturn(true)
      when(member2.status).thenReturn(MemberStatus.Joining)
      when(member2.address).thenReturn(akka.actor.Address("akka", "ClusterSystem", "127.0.0.1", 25522))

      val member3 = mock(classOf[Member])
      when(member3.hasRole("compute")).thenReturn(false)
      when(member3.status).thenReturn(MemberStatus.Up)
      when(member3.address).thenReturn(akka.actor.Address("akka", "ClusterSystem", "127.0.0.1", 25523))

      BeamHelper.upComputeMemberAddresses(Seq(member1, member2, member3)).toSeq shouldBe Seq(member1.address)
    }
  }

  "updateConfigToCurrentVersion" when {
    "config doesn't contain a root RH config parameter" should {
      "not update the first rideHail manager with that parameter value" in {
        val cfg = ConfigFactory
          .parseString("beam.cfg.copyRideHailToFirstManager = true")
          .withFallback(testConfig("test/input/beamville/beam.conf"))
        val result = BeamHelper.updateConfigToCurrentVersion(cfg)
        val manager = result.getConfigList("beam.agentsim.agents.rideHail.managers").get(0)
        manager.getDouble("initialization.procedural.fractionOfInitialVehicleFleet") shouldBe 0.5 withClue
        "beam.conf doesn't contain beam.agentsim.agents.rideHail.initialization.procedural.fractionOfInitialVehicleFleet" +
        " because of that the value defined in beam.agentsim.agents.rideHail.managers shouldn't be overwritten"
      }
    }
    "config contains a root RH config parameter" should {
      "update the first rideHail manager with that parameter value" in {
        val cfg = ConfigFactory
          .parseString("beam.cfg.copyRideHailToFirstManager=true")
          .withFallback(testConfig("test/input/beamville/beam-urbansimv2_1person.conf"))
        val result = BeamHelper.updateConfigToCurrentVersion(cfg)
        val manager = result.getConfigList("beam.agentsim.agents.rideHail.managers").get(0)
        manager.getDouble("initialization.procedural.fractionOfInitialVehicleFleet") shouldBe 0.0001 withClue
        """beam-urbansimv2_1person.conf contains
          beam.agentsim.agents.rideHail.initialization.procedural.fractionOfInitialVehicleFleet = 0.0001
          and the value 'fractionOfInitialVehicleFleet' of the first manager should be overwritten"""
      }
    }
    "config contains a root RH config parameter but we don't say to copy it" should {
      "not update the first rideHail manager with that parameter value" in {
        val cfg = testConfig("test/input/beamville/beam-urbansimv2_1person.conf")
        val result = BeamHelper.updateConfigToCurrentVersion(cfg)
        val manager = result.getConfigList("beam.agentsim.agents.rideHail.managers").get(0)
        manager.getDouble("initialization.procedural.fractionOfInitialVehicleFleet") shouldBe 0.5 withClue
        """our config doesn't contain beam.cfg.copyRideHailToFirstManager=true
          because of that the value 'fractionOfInitialVehicleFleet' of the first manager should not be overwritten"""
      }
    }
    "config contains old rideHail configuration" should {
      "update the first rideHail manager with that parameter value" in {
        val cfg = testConfig("test/input/sf-light/sf-light-0.5k.conf")
        val result = BeamHelper.updateConfigToCurrentVersion(cfg)
        val manager = result.getConfigList("beam.agentsim.agents.rideHail.managers").get(0)
        manager.getDouble("defaultBaseCost") shouldBe 1.8 withClue
        """This config doesn't have beam.agentsim.agents.rideHail.managers defined
          and updateConfigToCurrentVersion should create it"""
      }
    }
  }
}
