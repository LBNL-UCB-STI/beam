package beam.agentsim.agents.ridehail
import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestKit, TestProbe}
import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail._
import beam.router.BeamSkimmer
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.FunSpecLike

import scala.concurrent.Await

class ParallelAlonsoMoraAlgForRideHailSpec
    extends TestKit(
      ActorSystem(
        name = "ParallelAlonsoMoraAlgForRideHailSpec",
        config = ConfigFactory
          .parseString("""
               akka.log-dead-letters = 10
               akka.actor.debug.fsm = true
               akka.loglevel = debug
            """)
          .withFallback(testConfig("test/input/beamville/beam.conf").resolve())
      )
    )
    with FunSpecLike {

  val probe: TestProbe = TestProbe.apply()
  implicit val mockActorRef: ActorRef = probe.ref

  describe("AlonsoMoraPoolingAlgForRideHail") {
    it("Creates a consistent plan") {
      implicit val skimmer: BeamSkimmer = new BeamSkimmer()
      val sc = AlonsoMoraPoolingAlgForRideHailSpec.scenario1
      val alg: ParallelAlonsoMoraAlgForRideHail =
        new ParallelAlonsoMoraAlgForRideHail(
          sc._2,
          sc._1,
          Map[MobilityServiceRequestType, Int]((Pickup, 6 * 60), (Dropoff, 10 * 5000 * 60)),
          radius = Int.MaxValue,
          skimmer
        )

      import scala.concurrent.duration._
      val assignment = Await.result(alg.greedyAssignment(), atMost = 10.minutes)

      for (row <- assignment) {
        assert(row._1.getId == "trip:[p1] -> [p4] -> " || row._1.getId == "trip:[p3] -> ")
        assert(row._2.getId == "v2" || row._2.getId == "v1")
      }

    }
  }

}
