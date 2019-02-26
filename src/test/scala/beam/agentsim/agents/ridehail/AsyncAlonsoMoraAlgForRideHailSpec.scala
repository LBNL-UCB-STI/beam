package beam.agentsim.agents.ridehail
import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestKit, TestProbe}
import beam.agentsim.agents.planning.BeamPlan
import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail._
import beam.router.BeamSkimmer
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Coord
import org.scalatest.FunSpecLike

import scala.collection.JavaConverters._
import scala.collection.immutable.List
import scala.concurrent.Await

class AsyncAlonsoMoraAlgForRideHailSpec
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

  describe("AsyncAlonsoMoraAlgForRideHailSpec") {
    it("Creates a consistent plan") {
      implicit val skimmer: BeamSkimmer = new BeamSkimmer()
      val sc = AlonsoMoraPoolingAlgForRideHailSpec.scenario1
      val alg: AsyncAlonsoMoraAlgForRideHail =
        new AsyncAlonsoMoraAlgForRideHail(
          AlonsoMoraPoolingAlgForRideHail.demandSpatialIndex(sc._2),
          sc._1,
          Map[MobilityServiceRequestType, Int]((Pickup, 6 * 60), (Dropoff, 10 * 60)),
          maxRequestsPerVehicle = 1000
        )

      import scala.concurrent.duration._
      val assignment = Await.result(alg.greedyAssignment(), atMost = 10.minutes)
      for (row <- assignment) {
        assert(row._1.getId == "trip:[p1] -> [p4] -> " || row._1.getId == "trip:[p3] -> ")
        assert(row._2.getId == "v2" || row._2.getId == "v1")
      }

    }

    it("scales") {
      import org.matsim.core.config.ConfigUtils
      import org.matsim.core.population.io.PopulationReader
      import org.matsim.core.scenario.ScenarioUtils
      val sc = ScenarioUtils.createScenario(ConfigUtils.createConfig())
      new PopulationReader(sc).readFile("test/input/sf-light/sample/25k/population.xml.gz")
      implicit val skimmer: BeamSkimmer = new BeamSkimmer()

      import scala.collection.mutable.{ListBuffer => MListBuffer}
      val requests = MListBuffer.empty[CustomerRequest]
      sc.getPopulation.getPersons.values.asScala.map(p => BeamPlan(p.getSelectedPlan)).foreach { plan =>
        plan.trips.sliding(2).foreach {
          case Seq(prevTrip, curTrip) =>
            requests append createPersonRequest(
              makeVehPersonId(plan.getPerson.getId.toString),
              prevTrip.activity.getCoord,
              prevTrip.activity.getEndTime.toInt,
              curTrip.activity.getCoord
            )
        }
      }

      val minx = requests.map(_.pickup.activity.getCoord.getX).min
      val maxx = requests.map(_.pickup.activity.getCoord.getX).max
      val miny = requests.map(_.pickup.activity.getCoord.getY).min
      val maxy = requests.map(_.pickup.activity.getCoord.getY).max
      val rnd = new scala.util.Random
      val timeWindow = 300
      val fleetSize = 200
      val operation = "SYNC"

      val t0 = System.nanoTime()
      (28800 to 32400 by timeWindow).foreach { i =>
        println(i / 3600.0)
        val demand = requests.filter(x => x.pickup.time >= i && x.pickup.time < i + timeWindow)
        val fleet = MListBuffer.empty[VehicleAndSchedule]
        (0 to fleetSize).foreach { j =>
          fleet.append(
            createVehicleAndSchedule(
              "v" + j,
              new Coord(minx + rnd.nextDouble() * (maxx - minx), miny + rnd.nextDouble() * (maxy - miny)),
              i
            )
          )
        }

        var assignment = List.empty[(RideHailTrip, VehicleAndSchedule, Int)]
        if (demand.nonEmpty) {
          operation match {
            case "ASYNC" =>
              val alg: AsyncAlonsoMoraAlgForRideHail =
                new AsyncAlonsoMoraAlgForRideHail(
                  AlonsoMoraPoolingAlgForRideHail.demandSpatialIndex(demand.toList),
                  fleet.toList,
                  Map[MobilityServiceRequestType, Int]((Pickup, 6 * 60), (Dropoff, 10 * 60)),
                  maxRequestsPerVehicle = 100
                )
              import scala.concurrent.duration._
              assignment = Await.result(alg.greedyAssignment(), atMost = 10.minutes)
            case "SYNC" =>
              val alg: AlonsoMoraPoolingAlgForRideHail =
                new AlonsoMoraPoolingAlgForRideHail(
                  AlonsoMoraPoolingAlgForRideHail.demandSpatialIndex(demand.toList),
                  fleet.toList,
                  Map[MobilityServiceRequestType, Int]((Pickup, 6 * 60), (Dropoff, 10 * 60)),
                  maxRequestsPerVehicle = 100
                )
              val rvGraph: RVGraph = alg.pairwiseRVGraph
              val rtvGraph = alg.rTVGraph(rvGraph)
              assignment = alg.greedyAssignment(rtvGraph)
            case _ =>
          }
        }
      }
      val t1 = System.nanoTime()
      println("Elapsed time: " + (t1 - t0) / 1E9 + "s")
    }
  }

}
