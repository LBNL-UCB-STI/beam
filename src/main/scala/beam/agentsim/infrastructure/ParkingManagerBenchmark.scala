package beam.agentsim.infrastructure

import akka.actor.ActorSystem
import akka.util.Timeout
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.utils.csv.CsvWriter
import beam.utils.{BeamConfigUtils, FileUtils, ProfilingUtils}
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.StrictLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.population.io.PopulationReader
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.core.utils.collections.QuadTree

import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.util.Random

class ParkingManagerBenchmark(
  val possibleParkingLocations: Array[(Coord, String)],
  val parkingNetwork: ParkingNetwork
)(implicit
  val actorSystem: ActorSystem,
  val ec: ExecutionContext
) extends StrictLogging {
  implicit val timeout: Timeout = Timeout(10, TimeUnit.HOURS)

  logger.info(s"possibleParkingLocations: ${possibleParkingLocations.length}")

  def benchmark(): List[ParkingInquiryResponse] = {
    val parkingResponses =
      ProfilingUtils.timed(s"Computed ${possibleParkingLocations.length} parking locations", x => println(x)) {
        possibleParkingLocations.flatMap { case (coord, actType) =>
          parkingNetwork.processParkingInquiry(
            ParkingInquiry.init(
              SpaceTime(coord, 0),
              actType,
              triggerId = -1L
            )
          )
        }.toList
      }
    logger.info(s"parkingResponses: ${parkingResponses.length}")
    parkingResponses
  }

}

object ParkingManagerBenchmark extends StrictLogging {

  val pathToPlans: String = "data_files/sfbay-smartbaseline/0.plans.xml.gz"
  //  val pathToPlans: String = "https://beam-outputs.s3.us-east-2.amazonaws.com/parallel_parking_manager/0.plans.xml.gz"

//  val pathToTazParking: String = "production/sfbay/gemini/infrastructure/4a_output_2022_Apr_13_pubClust_withFees_aggregated.csv"
  val pathToTazParking: String =
    "production/sfbay/gemini/infrastructure/gemini-base-scenario-3-charging-no-household-infra16.csv"
  //  val pathToTazParking: String =
  //    "https://beam-outputs.s3.us-east-2.amazonaws.com/parallel_parking_manager/taz-parking-unlimited-fast-limited-l2-150-baseline.csv.gz"

  val pathToTAZ: String = "production/sfbay/taz-centers.csv"
  //  val pathToTAZ: String = "https://beam-outputs.s3.us-east-2.amazonaws.com/parallel_parking_manager/taz-centers.csv.gz"

  val pathToNetwork: String = "production/sfbay/r5-simple-no-local/physsim-network.xml"
  //  val pathToNetwork: String =
  //    "https://beam-outputs.s3.us-east-2.amazonaws.com/parallel_parking_manager/outputNetwork.xml.gz"

  val parkingStallCountScalingFactor: Double = 1.0
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

  val nTimes: Int = 1
  val fractionToBench: Double = 1.0

  def main(args: Array[String]): Unit = {
    implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

    def loadZones(
      quadTree: QuadTree[TAZ],
      pathToParking: String,
      beamConfig: BeamConfig
    ): Map[Id[ParkingZoneId], ParkingZone] = {
      logger.info("Start loading parking zones from {}", pathToParking)
      val zones = InfrastructureUtils.loadStalls(
        pathToParking,
        IndexedSeq.empty,
        quadTree,
        parkingStallCountScalingFactor,
        parkingCostScalingFactor,
        seed,
        beamConfig,
        None
      )
      logger.info(s"Number of zones: ${zones.size}")
      logger.info(s"Number of parking stalls: ${zones.map(_._2.stallsAvailable.toLong).sum}")
      zones
    }

    try {
      val scenario = readScenario(pathToPlans)
      logger.info(s"scenario contains ${scenario.getPopulation.getPersons.size()} people")

      val tazTreeMap = TAZTreeMap.fromCsv(pathToTAZ)
      logger.info(s"TAZTreeMap size: ${tazTreeMap.getTAZs.size}")

      val network = NetworkUtilsExtensions.readNetwork(pathToNetwork)
      logger.info(s"Network contains ${network.getLinks.size()} links")

      val boundingBox: Envelope = getNetworkBoundingBox(network)
      logger.info(s"Bounding box: $boundingBox")

      val geoUtils = new GeoUtils {
        override def localCRS: String = "epsg:26910"
        override def distUTMInMeters(x: Coord, y: Coord): Double = 0.0
      }

      val beamConfig = BeamConfig(typeSafeConfig)

      val activities: Iterable[Activity] = scenario.getPopulation.getPersons.values.asScala.flatMap { p =>
        p.getSelectedPlan.getPlanElements.asScala.collect { case act: Activity => act }
      }
      val allActivityLocations: Array[(Coord, String)] = activities.map(act => (act.getCoord, act.getType)).toArray

      def createZonalParkingManager(isLink: Boolean): ParkingNetwork = {
        val zones = loadZones(tazTreeMap.tazQuadTree, pathToTazParking, beamConfig)
        val parkingNetwork = ZonalParkingManager(
          zones,
          tazTreeMap.tazQuadTree,
          tazTreeMap.idToTAZMapping,
          boundingBox,
          beamConfig,
          geoUtils.distUTMInMeters(_, _)
        )
        parkingNetwork
      }

      def runBench(activityLocations: Array[(Coord, String)], managerType: String): List[ParkingInquiryResponse] = {
        // This is important! because `ParkingZone` is mutable class
        val parkingNetwork = managerType match {
          case "parallel" =>
            val zones = loadZones(tazTreeMap.tazQuadTree, pathToTazParking, beamConfig)
            val parkingNetwork =
              ParallelParkingManager.init(
                zones,
                beamConfig,
                tazTreeMap,
                geoUtils.distUTMInMeters,
                boundingBox,
                6,
                42
              )
            parkingNetwork
          case "zonal" =>
            createZonalParkingManager(isLink = false)
          case "hierarchical" =>
            val zones = loadZones(tazTreeMap.tazQuadTree, pathToTazParking, beamConfig)
            val parkingNetwork = HierarchicalParkingManager.init(
              zones,
              tazTreeMap,
              geoUtils.distUTMInMeters,
              beamConfig.beam.agentsim.agents.parking.minSearchRadius,
              beamConfig.beam.agentsim.agents.parking.maxSearchRadius,
              boundingBox,
              seed,
              beamConfig.beam.agentsim.agents.parking.mulitnomialLogit,
              beamConfig.beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds,
              checkThatNumberOfStallsMatch = true
            )
            parkingNetwork
        }

        val bench = new ParkingManagerBenchmark(activityLocations, parkingNetwork)
        val result = bench.benchmark()
        result
      }

      def benchmark(
        managerType: String,
        nTimes: Int,
        parkingLocations: immutable.IndexedSeq[Array[(Coord, String)]]
      ): (String, immutable.IndexedSeq[List[ParkingInquiryResponse]]) = {
        val start = System.currentTimeMillis()
        val responses = (1 to nTimes).zip(parkingLocations).map { case (_, parkingLocation) =>
          runBench(parkingLocation, managerType)
        }
        val end = System.currentTimeMillis()
        val diff = end - start
        val what: String = s"$managerType manager"
        (s"$what for $nTimes tests took $diff ms, AVG per test: ${diff.toDouble / nTimes} ms", responses)
      }

      val nToTake = (allActivityLocations.length * fractionToBench).toInt
      logger.info(
        s"nTimes: $nTimes, allActivityLocations: ${allActivityLocations.length}. fractionToBench: $fractionToBench which is $nToTake activities"
      )

      val rnd = new Random(seed)
      val parkingLocations = (1 to nTimes).map { _ =>
        rnd.shuffle(allActivityLocations.toList).take(nToTake).toArray
      }
      CsvWriter("./parking_inquiries.csv.gz", "activity-type", "x", "y")
        .writeAllAndClose(parkingLocations.flatten.map { case (coord, actType) =>
          List(actType, coord.getX, coord.getY)
        })
      logger.info("activities written")

      val (result, responses) = benchmark("hierarchical", nTimes, parkingLocations)
      val (zonalResult, zonalResponses) = benchmark("zonal", nTimes, parkingLocations)

      logger.info("#####################################################################")
      logger.info(result)
      logger.info(zonalResult)
      logger.info("#####################################################################")

      writeToCsv(responses, "./test_parking.csv")
      writeToCsv(zonalResponses, "./zonal_parking.csv")

      analyzeResult(responses.head.groupBy(_.stall.tazId), zonalResponses.head.groupBy(_.stall.tazId))
    } finally {
      actorSystem.terminate()
    }
  }

  private def writeToCsv(zonalResponses: Seq[List[ParkingInquiryResponse]], path: String): Unit = {
    new CsvWriter(path, "geo_id", "x", "y")
      .writeAllAndClose(
        zonalResponses
          .flatMap(_.map(resp => List(resp.stall.tazId, resp.stall.locationUTM.getX, resp.stall.locationUTM.getY)))
      )
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
    analizedParkingResponses: Map[Id[TAZ], Seq[ParkingInquiryResponse]],
    benchParkingResponses: Map[Id[TAZ], Seq[ParkingInquiryResponse]]
  ): Unit = {
    val keyDiff = analizedParkingResponses.keySet.diff(benchParkingResponses.keySet)
    if (keyDiff.nonEmpty) {
      logger.warn(s"Key diff: $keyDiff")
    }
    val keysInBoth = analizedParkingResponses.keySet.intersect(benchParkingResponses.keySet)
    val dataSet = keysInBoth.toSeq.map { tazId =>
      val resp = analizedParkingResponses(tazId)
      val benchResp = benchParkingResponses(tazId)
      IndexedSeq(tazId, resp.size, benchResp.size)
    }
    CsvWriter("./parking_manager_benchmark.csv", "taz_id", "resp", "bench")
      .writeAllAndClose(dataSet)
  }
}
