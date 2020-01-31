package beam.sim

import java.io.{BufferedWriter, FileWriter}

import akka.actor.ActorRef
import beam.agentsim.agents.ridehail.RHMatchingToolkit.{CustomerRequest, RideHailTrip, VehicleAndSchedule}
import beam.agentsim.agents.ridehail.{
  AlonsoMoraPoolingAlgForRideHail,
  AsyncAlonsoMoraAlgForRideHail,
  RHMatchingToolkit,
  VehicleCentricMatchingForRideHail
}
import beam.agentsim.agents.vehicles.{BeamVehicleType, PersonIdWithActorRef}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.{BeamConfigUtils, FileUtils}
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.AbstractModule
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.io.IOUtils

import scala.collection.JavaConverters._
import scala.collection.immutable.List
import scala.concurrent.duration._
import scala.concurrent.{Await, TimeoutException}
import scala.util.Random

object MatchingPerformance extends BeamHelper {

  val testOutputDir = "output/test/"

  val configFileName = "test/input/beamville/beam.conf"
  val configLocation = ConfigFactory.parseString("config=" + configFileName)

  def testConfig(conf: String) =
    BeamConfigUtils
      .parseFileSubstitutingInputDirectory(conf)
      .withValue("beam.outputs.baseOutputDirectory", ConfigValueFactory.fromAnyRef(testOutputDir))
      .withFallback(configLocation)

  def runTest(startTime: Int, numRequestsPerVehicle: Int, durInMin: Int, nbVehicles: Int, algorithm: String): Unit = {

    val config = ConfigFactory
      .parseString(s"""
                      |beam.outputs.events.fileOutputFormats = xml
                      |beam.physsim.skipPhysSim = true
                      |beam.agentsim.lastIteration = 0
                      |beam.agentsim.agents.rideHail.allocationManager.alonsoMora.waitingTimeInSec = 1800
                      |beam.agentsim.agents.rideHail.allocationManager.alonsoMora.excessRideTimeAsFraction= 0.80
                      |beam.agentsim.agents.rideHail.allocationManager.alonsoMora.numRequestsPerVehicle = $numRequestsPerVehicle
                      |beam.router.skim = {
                      |  keepKLatestSkims = 1
                      |  writeSkimsInterval = 1
                      |  writeAggregatedSkimsInterval = 1
                      |  travel-time-skimmer {
                      |    name = "travel-time-skimmer"
                      |    fileBaseName = "skimsTravelTimeObservedVsSimulated"
                      |  }
                      |  origin_destination_skimmer {
                      |    name = "od-skimmer"
                      |    fileBaseName = "skimsOD"
                      |    writeAllModeSkimsForPeakNonPeakPeriodsInterval = 0
                      |    writeFullSkimsInterval = 0
                      |  }
                      |  taz-skimmer {
                      |    name = "taz-skimmer"
                      |    fileBaseName = "skimsTAZ"
                      |  }
                      |}
        """.stripMargin)
      .withFallback(testConfig("test/input/sf-light/sf-light-25k.conf"))
      .resolve()

    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    val beamConfig = BeamConfig(config)
    implicit val beamScenario = loadScenario(beamConfig)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]

    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, beamConfig, scenario, beamScenario))
        }
      }
    )
    implicit val services = injector.getInstance(classOf[BeamServices])
    implicit val actorRef = ActorRef.noSender

    logger.info("Loading jniortools ...")
    try {
      System.loadLibrary("jniortools")
    } catch {
      case e: UnsatisfiedLinkError =>
        logger.error("jniortools failed to load.\n" + e)
    }

    val (spatialPoolCustomerReqs, availVehicles) =
      buildSpatialPoolCustomerReqs(startTime, durInMin, nbVehicles, services, actorRef)

    logger.info(
      s"START (requests: ${spatialPoolCustomerReqs.size}, vehicles: ${availVehicles.size}, rpv: $numRequestsPerVehicle, algorithm: $algorithm)"
    )
    val assignments = algorithm match {
      case "optimal" => alonsoMoraPoolingAlgForRideHail(spatialPoolCustomerReqs, availVehicles, services)
      case "greedy"  => asyncAlonsoMoraAlgForRideHail(spatialPoolCustomerReqs, availVehicles, services)
      case "fast"    => vehicleCentricMatchingForRideHail(spatialPoolCustomerReqs, availVehicles, services)
      case _ =>
        logger.info("no algorithm selected")
        List.empty[RideHailTrip]
    }
    logger.info("Sum Of Delays: " + assignments.map(_.sumOfDelays).sum)
    logger.info("END")

  }

  private def vehicleCentricMatchingForRideHail(
    demand: QuadTree[CustomerRequest],
    supply: List[VehicleAndSchedule],
    services: BeamServices
  ): List[RideHailTrip] = {
    val alg1 = new VehicleCentricMatchingForRideHail(demand, supply, services)
    var assignments = List.empty[RideHailTrip]
    var elapsed = -1.0
    try {
      val t0 = System.nanoTime()
      assignments = Await.result(alg1.matchAndAssign(0), atMost = 1440.minutes)
      val t1 = System.nanoTime()
      elapsed = (t1 - t0) / 1E9
      val pool_1p = 1 * assignments.count(_.requests.size == 1)
      val pool_2p = 2 * assignments.count(_.requests.size == 2)
      val pool_3p = 3 * assignments.count(_.requests.size == 3)
      val pool_4p = 4 * assignments.count(_.requests.size == 4)
      val pool_xp = pool_2p + pool_3p + pool_4p
      logger.info(s"-------> pool share: ${pool_xp.toDouble / (pool_xp + pool_1p)}")
      logger.info(s"-------> $pool_1p pool_1p, $pool_2p pool_2p, $pool_3p pool_3p, $pool_4p pool_4p")
      val veh_pool = assignments.count(_.requests.size > 1)
      val veh_solo = assignments.count(_.requests.size == 1)
      logger.info(s"-------> ${veh_pool + veh_solo} vehicles")
    } catch {
      case _: TimeoutException =>
        logger.info("VehicleCentricMatchingForRideHail: TIMEOUT")
    }
    logger.info(s"Runtime: $elapsed")
    assignments
  }

  private def alonsoMoraPoolingAlgForRideHail(
    demand: QuadTree[CustomerRequest],
    supply: List[VehicleAndSchedule],
    services: BeamServices
  ): List[RideHailTrip] = {
    val alg3 = new AlonsoMoraPoolingAlgForRideHail(demand, supply, services)
    var assignments = List.empty[RideHailTrip]
    var elapsed = -1.0
    try {
      val t0 = System.nanoTime()
      assignments = Await.result(alg3.matchAndAssign(0), atMost = 1440.minutes)
      val t1 = System.nanoTime()
      elapsed = (t1 - t0) / 1E9
      val pool_1p = 1 * assignments.count(_.requests.size == 1)
      val pool_2p = 2 * assignments.count(_.requests.size == 2)
      val pool_3p = 3 * assignments.count(_.requests.size == 3)
      val pool_4p = 4 * assignments.count(_.requests.size == 4)
      val pool_xp = pool_2p + pool_3p + pool_4p
      logger.info(s"-------> pool share: ${pool_xp.toDouble / (pool_xp + pool_1p)}")
      logger.info(s"-------> $pool_1p pool_1p, $pool_2p pool_2p, $pool_3p pool_3p, $pool_4p pool_4p")
      val veh_pool = assignments.count(_.requests.size > 1)
      val veh_solo = assignments.count(_.requests.size == 1)
      logger.info(s"-------> ${veh_pool + veh_solo} vehicles")
    } catch {
      case _: TimeoutException =>
        logger.info("AlonsoMoraPoolingAlgForRideHail: TIMEOUT")
    }
    logger.info(s"Runtime: $elapsed")
    assignments
  }

  private def asyncAlonsoMoraAlgForRideHail(
    demand: QuadTree[CustomerRequest],
    supply: List[VehicleAndSchedule],
    services: BeamServices
  ): List[RideHailTrip] = {
    val alg2 = new AsyncAlonsoMoraAlgForRideHail(demand, supply, services)
    var assignments = List.empty[RideHailTrip]
    var elapsed = -1.0
    try {
      val t0 = System.nanoTime()
      assignments = Await.result(alg2.matchAndAssign(0), atMost = 1440.minutes)
      val t1 = System.nanoTime()
      elapsed = (t1 - t0) / 1E9
      val pool_1p = 1 * assignments.count(_.requests.size == 1)
      val pool_2p = 2 * assignments.count(_.requests.size == 2)
      val pool_3p = 3 * assignments.count(_.requests.size == 3)
      val pool_4p = 4 * assignments.count(_.requests.size == 4)
      val pool_xp = pool_2p + pool_3p + pool_4p
      logger.info(s"-------> pool share: ${pool_xp.toDouble / (pool_xp + pool_1p)}")
      logger.info(s"-------> $pool_1p pool_1p, $pool_2p pool_2p, $pool_3p pool_3p, $pool_4p pool_4p")
      val veh_pool = assignments.count(_.requests.size > 1)
      val veh_solo = assignments.count(_.requests.size == 1)
      logger.info(s"-------> ${veh_pool + veh_solo} vehicles")
    } catch {
      case _: TimeoutException =>
        logger.info("AsyncAlonsoMoraAlgForRideHail: TIMEOUT")
    }
    logger.info(s"Runtime: $elapsed")
    assignments
  }

  private def buildSpatialPoolCustomerReqs(
    starTime: Int,
    durInMin: Int,
    nbVehicles: Int,
    services: BeamServices,
    actorRef: ActorRef
  ): (QuadTree[CustomerRequest], List[VehicleAndSchedule]) = {
    val heading1 = "person,pickup.x,pickup.y,time,dropoff.x,dropoff.y"
    val heading2 = "vehicle,type,x,y,time"
    val suffix = s"${durInMin}min${nbVehicles}veh"
    val endTime = starTime + durInMin * 60
    val buffer = 5000
    var sample: Option[(QuadTree[CustomerRequest], List[VehicleAndSchedule])] = None
    try {
      val requests: Array[CustomerRequest] = IOUtils
        .getBufferedReader(s"test/input/sf-light/requests_$suffix.csv")
        .lines()
        .toArray
        .drop(1)
        .map { line =>
          val row = line.asInstanceOf[String].split(",")
          RHMatchingToolkit.createPersonRequest(
            PersonIdWithActorRef(Id.create(row(0), classOf[Person]), actorRef),
            new Coord(row(1).toDouble, row(2).toDouble),
            row(3).toInt,
            new Coord(row(4).toDouble, row(5).toDouble),
            services
          )
        }
      val vehicles: Array[VehicleAndSchedule] = IOUtils
        .getBufferedReader(s"test/input/sf-light/vehicles_$suffix.csv")
        .lines()
        .toArray
        .drop(1)
        .map { line =>
          val row = line.asInstanceOf[String].split(",")
          RHMatchingToolkit.createVehicleAndSchedule(
            row(0),
            services.beamScenario.vehicleTypes(Id.create(row(1), classOf[BeamVehicleType])),
            new Coord(row(2).toDouble, row(3).toDouble),
            row(4).toInt
          )
        }
      val coords = requests.flatMap(r => List(r.pickup.activity.getCoord, r.dropoff.activity.getCoord))
      val spatialPoolCustomerReqs: QuadTree[CustomerRequest] = new QuadTree[CustomerRequest](
        coords.minBy(_.getX).getX - buffer,
        coords.minBy(_.getY).getY - buffer,
        coords.maxBy(_.getX).getX + buffer,
        coords.maxBy(_.getY).getY + buffer
      )
      requests.foreach(
        r => spatialPoolCustomerReqs.put(r.pickup.activity.getCoord.getX, r.dropoff.activity.getCoord.getY, r)
      )
      sample = Some((spatialPoolCustomerReqs, vehicles.toList))
    } catch {
      case _: Throwable =>
        logger.info("Now sampling!")
    }
    if (sample.isEmpty) {
      val pops = services.matsimServices.getScenario.getPopulation.getPersons
        .values()
        .asScala
        .filter(
          p =>
            p.getSelectedPlan.getPlanElements
              .get(0)
              .asInstanceOf[Activity]
              .getEndTime >= starTime && p.getSelectedPlan.getPlanElements
              .get(0)
              .asInstanceOf[Activity]
              .getEndTime <= endTime
        )
      val coords = pops.map(_.getSelectedPlan.getPlanElements.get(0).asInstanceOf[Activity].getCoord)
      val spatialPoolCustomerReqs: QuadTree[CustomerRequest] = new QuadTree[CustomerRequest](
        coords.minBy(_.getX).getX - buffer,
        coords.minBy(_.getY).getY - buffer,
        coords.maxBy(_.getX).getX + buffer,
        coords.maxBy(_.getY).getY + buffer
      )
      val out1 = new BufferedWriter(new FileWriter(s"test/input/sf-light/requests_$suffix.csv"))
      out1.write(heading1)
      pops.foreach { p =>
        val pickup = p.getSelectedPlan.getPlanElements.get(0).asInstanceOf[Activity]
        val dropoff = p.getSelectedPlan.getPlanElements.get(2).asInstanceOf[Activity]
        val req = RHMatchingToolkit.createPersonRequest(
          PersonIdWithActorRef(p.getId, actorRef),
          pickup.getCoord,
          pickup.getEndTime.toInt,
          dropoff.getCoord,
          services
        )
        spatialPoolCustomerReqs.put(pickup.getCoord.getX, dropoff.getCoord.getY, req)
        val line = "\n" + p.getId + "," +
        pickup.getCoord.getX + "," + pickup.getCoord.getY + "," +
        pickup.getEndTime.toInt + "," + dropoff.getCoord.getX + "," + dropoff.getCoord.getY
        out1.write(line)
      }
      out1.close()

      val out2 = new BufferedWriter(new FileWriter(s"test/input/sf-light/vehicles_$suffix.csv"))
      out2.write(heading2)
      val vehicleType = services.beamScenario.vehicleTypes(Id.create("Car", classOf[BeamVehicleType]))
      val rand: Random = new Random(services.beamScenario.beamConfig.matsim.modules.global.randomSeed)
      val vehicles = (1 to nbVehicles).map { i =>
        val x = spatialPoolCustomerReqs.getMinEasting + (spatialPoolCustomerReqs.getMaxEasting - spatialPoolCustomerReqs.getMinEasting) * rand
          .nextDouble()
        val y = spatialPoolCustomerReqs.getMinNorthing + (spatialPoolCustomerReqs.getMaxNorthing - spatialPoolCustomerReqs.getMinNorthing) * rand
          .nextDouble()
        val time = starTime + (endTime - starTime) * rand.nextDouble()
        val vehid = s"v$i"
        out2.write("\n" + vehid + "," + vehicleType.id.toString + "," + x + "," + y + "," + time.toInt)
        RHMatchingToolkit.createVehicleAndSchedule(vehid, vehicleType, new Coord(x, y), time.toInt)
      }.toList
      out2.close()
      sample = Some((spatialPoolCustomerReqs, vehicles))
    }
    sample.get
  }

}
