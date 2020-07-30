package beam.agentsim.infrastructure

import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Random

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.pattern._
import akka.util.Timeout
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ZoneSearchTree
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.utils.{BeamConfigUtils, FileUtils, ProfilingUtils}
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.StrictLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.population.Activity
import org.matsim.core.config.ConfigUtils
import org.matsim.core.population.io.PopulationReader
import org.matsim.core.scenario.ScenarioUtils

class ParkingManagerBenchmark(val possibleParkingLocations: Array[(Coord, String)], val parkingManagerActor: ActorRef)(
  implicit val actorSystem: ActorSystem,
  val ec: ExecutionContext
) extends StrictLogging {
  implicit val timeout: Timeout = Timeout(10, TimeUnit.HOURS)

  logger.info(s"possibleParkingLocations: ${possibleParkingLocations.length}")

  def benchmark(): List[ParkingInquiryResponse] = {
    val parkingResponses =
      ProfilingUtils.timed(s"Computed ${possibleParkingLocations.length} parking locations", x => println(x)) {
        val responseFutures = possibleParkingLocations.map {
          case (coord, actType) =>
            parkingManagerActor.ask(ParkingInquiry(coord, actType)).mapTo[ParkingInquiryResponse]
        }.toList
        Await.result(Future.sequence(responseFutures), timeout.duration)
      }
    logger.info(s"parkingResponses: ${parkingResponses.length}")
    parkingResponses
  }

}

object ParkingManagerBenchmark extends StrictLogging {

//  val pathToPlans: String = "D:/Work/beam/ParallelJDEQSim/sfbay-smart-base/0.plans.xml.gz"
  val pathToPlans: String = "https://beam-outputs.s3.us-east-2.amazonaws.com/parallel_parking_manager/0.plans.xml.gz"

//  val pathToParking: String = "D:/Work/beam/ParallelJDEQSim/sfbay-smart-base/parking/taz-parking-unlimited-fast-limited-l2-150-baseline.csv"
  val pathToParking: String =
    "https://beam-outputs.s3.us-east-2.amazonaws.com/parallel_parking_manager/taz-parking-unlimited-fast-limited-l2-150-baseline.csv.gz"

//  val pathToTAZ: String = "D:/Work/beam/ParallelJDEQSim/sfbay-smart-base/taz-centers.csv"
  val pathToTAZ: String = "https://beam-outputs.s3.us-east-2.amazonaws.com/parallel_parking_manager/taz-centers.csv.gz"

//  val pathToNetwork: String = "D:/Work/beam/ParallelJDEQSim/sfbay-smart-base/outputNetwork.xml.gz"
  val pathToNetwork: String =
    "https://beam-outputs.s3.us-east-2.amazonaws.com/parallel_parking_manager/outputNetwork.xml.gz"

  val parkingStallCountScalingFactor: Double = 0.13
  val parkingCostScalingFactor: Double = 1.0

  val typeSafeConfig: Config = ConfigFactory
    .parseString(
      """
        |beam.agentsim.agents.parking.minSearchRadius = 250.00
        |beam.agentsim.agents.parking.maxSearchRadius = 8046.72
        |beam.agentsim.agents.parking.mulitnomialLogit.params.rangeAnxietyMultiplier = -0.5
        |beam.agentsim.agents.parking.mulitnomialLogit.params.distanceMultiplier = -0.086
        |beam.agentsim.agents.parking.mulitnomialLogit.params.parkingPriceMultiplier = -0.5
        |beam.agentsim.agents.parking.mulitnomialLogit.params.homeActivityPrefersResidentialParkingMultiplier = 2.0
        |
        |parallel-parking-manager-dispatcher {
        |  executor = "thread-pool-executor"
        |  thread-pool-executor {
        |    keep-alive-time = 120s
        |    core-pool-size-max = 64
        |  }
        |  throughput = 10
        |  type = Dispatcher
        |}
       """.stripMargin
    )
    .withFallback(BeamConfigUtils.parseFileSubstitutingInputDirectory("test/input/beamville/beam.conf"))
    .resolve()

  implicit val actorSystem: ActorSystem = ActorSystem("ParkingManagerBenchmark", typeSafeConfig)

  val seed: Int = 42

  val nTimes: Int = 5
  val fractionToBench: Double = 0.3

  def main(args: Array[String]): Unit = {
    implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

    try {
      val scenario = readScenario(pathToPlans)
      logger.info(s"scenario contains ${scenario.getPopulation.getPersons.size()} people")

      val tazTreeMap = TAZTreeMap.fromCsv(pathToTAZ)
      logger.info(s"TAZTreeMap size: ${tazTreeMap.getTAZs.size}")

      val (zones, searchTree: ZoneSearchTree[TAZ]) = ZonalParkingManager.loadParkingZones(
        pathToParking,
        "",
        parkingStallCountScalingFactor,
        parkingCostScalingFactor,
        new Random(seed)
      )
      logger.info(s"Number of zones: ${zones.length}")
      logger.info(s"SearchTree size: ${searchTree.size}")
      val network = NetworkUtilsExtensions.readNetwork(pathToNetwork)
      logger.info(s"Network contains ${network.getLinks.size()} links")

      val boundingBox: Envelope = getNetworkBoundingBox(network)
      logger.info(s"Bounding box: $boundingBox")

      val geoUtils = new GeoUtils {
        override def localCRS: String = "epsg:26910"
      }

      val beamConfg = BeamConfig(typeSafeConfig)

      val activities: Iterable[Activity] = scenario.getPopulation.getPersons.values.asScala.flatMap { p =>
        p.getSelectedPlan.getPlanElements.asScala.collect { case act: Activity => act }
      }
      val allActivityLocations: Array[(Coord, String)] = activities.map(act => (act.getCoord, act.getType)).toArray

      def runBench(activityLocations: Array[(Coord, String)], isParallel: Boolean): List[ParkingInquiryResponse] = {
        // This is important! because `ParkingZone` is mutable class
        val copyOfZones = zones.map(_.makeCopy())
        val parkingManagerActor = if (isParallel) {
          actorSystem.actorOf(
            ParallelParkingManager.props(beamConfg, tazTreeMap, copyOfZones, searchTree, 32, geoUtils, 42, boundingBox)
          )
        } else {
          actorSystem.actorOf(
            Props(
              ZonalParkingManager(
                beamConfg,
                tazTreeMap,
                copyOfZones,
                searchTree,
                TAZ.EmergencyTAZId,
                geoUtils,
                new Random(seed),
                boundingBox
              )
            )
          )
        }

        val bench = new ParkingManagerBenchmark(activityLocations, parkingManagerActor)
        val result = bench.benchmark()
        parkingManagerActor ! PoisonPill
        result
      }

      def benchmark(
        isParallel: Boolean,
        nTimes: Int,
        parkingLocations: immutable.IndexedSeq[Array[(Coord, String)]]
      ): String = {
        val start = System.currentTimeMillis()
        (1 to nTimes).zip(parkingLocations).map {
          case (_, parkingLocation) =>
            runBench(parkingLocation, isParallel = isParallel).size
        }
        val end = System.currentTimeMillis()
        val diff = end - start
        val what: String = if (isParallel) "ParallelParkingManager" else "ZonalParkingManager"
        s"$what for $nTimes tests took $diff ms, AVG per test: ${diff.toDouble / nTimes} ms"
      }

      val nToTake = (allActivityLocations.length * fractionToBench).toInt
      logger.info(
        s"nTimes: $nTimes, allActivityLocations: ${allActivityLocations.length}. fractionToBench: $fractionToBench which is $nToTake activities"
      )

      val rnd = new Random(seed)
      val parkingLocations = (1 to nTimes).map { _ =>
        rnd.shuffle(allActivityLocations.toList).take(nToTake).toArray
      }

      val seqResult = benchmark(isParallel = false, nTimes, parkingLocations)
      val parResult = benchmark(isParallel = true, nTimes, parkingLocations)

      logger.info("#####################################################################")
      logger.info(seqResult)
      logger.info(parResult)
      logger.info("#####################################################################")

      // analyzeResult(parallelParkingResponses, sequentialParkingResponses)
    } finally {
      actorSystem.terminate()
    }
  }

  private def groupedByTaz(parkingResponses: Seq[ParkingInquiryResponse]): Map[Id[TAZ], Seq[ParkingInquiryResponse]] = {
    parkingResponses
      .groupBy { x =>
        x.stall.tazId
      }
      .map { case (tazId, xs) => (tazId, xs) }
  }

  private def getNetworkBoundingBox(network: Network): Envelope = {
    val firstCoord = network.getLinks.values().iterator().next().getCoord
    val envelope = new Envelope(firstCoord.getX, firstCoord.getX, firstCoord.getY, firstCoord.getY)
    network.getLinks.values().asScala.foreach { link =>
      envelope.expandToInclude(link.getCoord.getX, link.getCoord.getY)
    }
    envelope
  }

  private def readScenario(path: String): Scenario = {
    val scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
    new PopulationReader(scenario).parse(FileUtils.getInputStream(path))
    scenario
  }

  private def analyzeResult(
    parallelParkingResponses: Map[Id[TAZ], Seq[ParkingInquiryResponse]],
    sequentialParkingResponses: Map[Id[TAZ], Seq[ParkingInquiryResponse]]
  ): Unit = {
    val keyDiff = parallelParkingResponses.keySet.diff(sequentialParkingResponses.keySet)
    if (keyDiff.nonEmpty) {
      logger.warn(s"Key diff: $keyDiff")
    }
    val keysInBoth = parallelParkingResponses.keySet.union(sequentialParkingResponses.keySet)
    keysInBoth.foreach { tazId =>
      val maybePar = parallelParkingResponses.get(tazId)
      val maybeSeq = sequentialParkingResponses.get(tazId)
      (maybePar, maybeSeq) match {
        case (Some(parResp), Some(seqResp)) =>
          if (seqResp.size != parResp.size) {
            logger.info(s"""Size for $tazId is not equal (Seq[${seqResp.size}] != Par[${parResp.size}])""".stripMargin)
          }

        case _ =>
      }

    }
  }
}
