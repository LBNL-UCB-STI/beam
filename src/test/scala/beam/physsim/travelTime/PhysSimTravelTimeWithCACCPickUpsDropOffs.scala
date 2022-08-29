package beam.physsim.travelTime

import beam.physsim.bprsim.{BPRSimConfig, BPRSimulation, ParallelBPRSimulation}
import beam.physsim.jdeqsim.cacc.CACCSettings
import beam.physsim.jdeqsim.cacc.roadcapacityadjustmentfunctions.{
  Hao2018CaccRoadCapacityAdjustmentFunction,
  RoadCapacityAdjustmentFunction
}
import beam.physsim.jdeqsim.{cacc, JDEQSimRunner}
import beam.physsim.{LinkPickUpsDropOffs, PickUpDropOffHolder, TimeToValueCollection}
import beam.sim.BeamConfigChangesObservable
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigValueFactory}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.core.config.{Config => MatsimConfig}
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.mobsim.jdeqsim
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader
import org.matsim.core.population.io.PopulationReader
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.vehicles.Vehicle
import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}

class PhysSimTravelTimeWithCACCPickUpsDropOffs extends AnyWordSpec with Matchers with LazyLogging {
  import beam.physsim.travelTime.PhysSimTravelTimeWithCACCPickUpsDropOffs._

  val simulationConfigPath = "test/input/beamville/beam.conf"
  val simulationNetworkPath = "test/test-resources/beam/physsim/beamville-network-output.xml"
  val simulationPlansPath = "test/test-resources/beam/physsim/physsim-plans-few-persons.xml"

  val config: Config = testConfig(simulationConfigPath)
    .withValue(
      "beam.physsim.pickUpDropOffAnalysis.secondsFromPickUpPropOffToAffectTravelTime",
      ConfigValueFactory.fromAnyRef(100)
    )
    .withValue(
      "beamConfig.beam.physsim.pickUpDropOffAnalysis.additionalTravelTimeMultiplier",
      ConfigValueFactory.fromAnyRef(2.0)
    )
    .withValue(
      "beam.physsim.flowCapacityFactor",
      ConfigValueFactory.fromAnyRef(0.001)
    )
    .withValue(
      "beam.physsim.jdeqsim.cacc.minRoadCapacity",
      ConfigValueFactory.fromAnyRef(0.0001)
    )
    .withValue(
      "beamConfig.beam.physsim.jdeqsim.cacc.minSpeedMetersPerSec",
      ConfigValueFactory.fromAnyRef(0.001)
    )
    .resolve()

  val beamConfig: BeamConfig = BeamConfig(config)
  val configBuilder = new MatSimBeamConfigBuilder(config)
  val matsimConfig: MatsimConfig = configBuilder.buildMatSimConf()
  val network: Network = PhysSimTravelTimeWithCACCPickUpsDropOffs.readNetwork(simulationNetworkPath)
  val scenario: MutableScenario = readScenario(matsimConfig, network, simulationPlansPath)
  val jdeqConfig = new jdeqsim.JDEQSimConfigGroup
  jdeqConfig.setFlowCapacityFactor(beamConfig.beam.physsim.flowCapacityFactor)
  jdeqConfig.setStorageCapacityFactor(beamConfig.beam.physsim.storageCapacityFactor)
  jdeqConfig.setSimulationEndTime(beamConfig.matsim.modules.qsim.endTime)

  val isCACCVehicle: java.util.Map[String, java.lang.Boolean] = beamvilleAllVehiclesFromSimulation
    .map { vehId =>
      vehId.toString -> new java.lang.Boolean(true)
    }
    .toMap
    .asJava

  class Hao2018CaccRoadCapacityAdjustmentFunctionWithoutPrintingStats(beamConfig: BeamConfig)
      extends Hao2018CaccRoadCapacityAdjustmentFunction(
        beamConfig,
        97,
        null,
        new BeamConfigChangesObservable(beamConfig)
      ) {
    override def printStats(): Unit = {}
  }

  val roadCapacityAdjustmentFunction: RoadCapacityAdjustmentFunction =
    new Hao2018CaccRoadCapacityAdjustmentFunctionWithoutPrintingStats(beamConfig)

  val caccSettings: CACCSettings = CACCSettings(
    isCACCVehicle,
    1.0,
    7,
    roadCapacityAdjustmentFunction
  )

  val pickUpDropOffHolder = new PickUpDropOffHolder(beamvilleLinkPickUpsDropOffsFromSimulation, beamConfig)

  "JDEQSimulation (matsim and beam version)" must {
    "return the same average link travel time" in {
      val (eventManager: EventsManagerImpl, eventBuffer: BufferEventHandler) = createEventManager
      val sim = new jdeqsim.JDEQSimulation(jdeqConfig, scenario, eventManager)
      sim.run()
      val avgLTTMatsim = AverageLinkTravelTimeCalculationResults.getFrom(eventBuffer.buffer)

      val avgLTTBeam = JDEQSimAvgLinkTravelTime(None, None)

      val maxVal = math.max(avgLTTMatsim.averageLinkTravelTime, avgLTTBeam.averageLinkTravelTime)
      val minVal = math.min(avgLTTMatsim.averageLinkTravelTime, avgLTTBeam.averageLinkTravelTime)

      val delta = maxVal - minVal

      delta / maxVal should be < 0.001
    }
  }

  "Different combination of PickUpDropOffHolder and CACCSettings" must {
    "return expected link travel time in JDEQ simulation" in {
      def calcAverageLinkTravelTime(
        maybeCaccSettings: Option[CACCSettings],
        maybePickUpDropOffHolder: Option[PickUpDropOffHolder]
      ): AverageLinkTravelTimeCalculationResults = {
        JDEQSimAvgLinkTravelTime(maybeCaccSettings, maybePickUpDropOffHolder)
      }

      val noPickUpWithCACC = calcAverageLinkTravelTime(Some(caccSettings), None)
      val noPickUpNoCACC = calcAverageLinkTravelTime(None, None)
      val withPickUpWithCACC = calcAverageLinkTravelTime(Some(caccSettings), Some(pickUpDropOffHolder))
      val withPickUpNoCACC = calcAverageLinkTravelTime(None, Some(pickUpDropOffHolder))

      noPickUpWithCACC.averageLinkTravelTime should be <= noPickUpNoCACC.averageLinkTravelTime
      noPickUpWithCACC.averageLinkTravelTime should be < withPickUpWithCACC.averageLinkTravelTime
      noPickUpNoCACC.averageLinkTravelTime should be < withPickUpNoCACC.averageLinkTravelTime
      withPickUpWithCACC.averageLinkTravelTime should be < withPickUpNoCACC.averageLinkTravelTime
    }

    "return expected link travel time in BPR simulation" in {
      def calcAverageLinkTravelTime(
        maybeCaccSettings: Option[CACCSettings],
        maybePickUpDropOffHolder: Option[PickUpDropOffHolder]
      ): AverageLinkTravelTimeCalculationResults = {
        BPRSimAvgLinkTravelTime(maybeCaccSettings, maybePickUpDropOffHolder)
      }

      val noPickUpWithCACC = calcAverageLinkTravelTime(Some(caccSettings), None)
      val noPickUpNoCACC = calcAverageLinkTravelTime(None, None)
      val withPickUpWithCACC = calcAverageLinkTravelTime(Some(caccSettings), Some(pickUpDropOffHolder))
      val withPickUpNoCACC = calcAverageLinkTravelTime(None, Some(pickUpDropOffHolder))

      noPickUpWithCACC.averageLinkTravelTime should be <= noPickUpNoCACC.averageLinkTravelTime
      noPickUpWithCACC.averageLinkTravelTime should be < withPickUpWithCACC.averageLinkTravelTime
      noPickUpNoCACC.averageLinkTravelTime should be < withPickUpNoCACC.averageLinkTravelTime
      withPickUpWithCACC.averageLinkTravelTime should be <= withPickUpNoCACC.averageLinkTravelTime
    }

    "return expected link travel time in ParBPR simulation" in {
      def calcAverageLinkTravelTime(
        maybeCaccSettings: Option[CACCSettings],
        maybePickUpDropOffHolder: Option[PickUpDropOffHolder]
      ): AverageLinkTravelTimeCalculationResults = {
        ParBPRSimAvgLinkTravelTime(maybeCaccSettings, maybePickUpDropOffHolder)
      }

      val noPickUpWithCACC = calcAverageLinkTravelTime(Some(caccSettings), None)
      val noPickUpNoCACC = calcAverageLinkTravelTime(None, None)
      val withPickUpWithCACC = calcAverageLinkTravelTime(Some(caccSettings), Some(pickUpDropOffHolder))
      val withPickUpNoCACC = calcAverageLinkTravelTime(None, Some(pickUpDropOffHolder))

      noPickUpWithCACC.averageLinkTravelTime should be <= noPickUpNoCACC.averageLinkTravelTime
      noPickUpWithCACC.averageLinkTravelTime should be < withPickUpWithCACC.averageLinkTravelTime
      noPickUpNoCACC.averageLinkTravelTime should be < withPickUpNoCACC.averageLinkTravelTime
      withPickUpWithCACC.averageLinkTravelTime should be <= withPickUpNoCACC.averageLinkTravelTime
    }
  }

  def BPRSimAvgLinkTravelTime(
    maybeCaccSettings: Option[CACCSettings],
    maybePickUpDropOffHolder: Option[PickUpDropOffHolder]
  ): AverageLinkTravelTimeCalculationResults = {
    val travelTimeFunction = JDEQSimRunner.getTravelTimeFunction(
      "FREE_FLOW",
      beamConfig.beam.physsim.flowCapacityFactor,
      0,
      maybeCaccSettings,
      maybePickUpDropOffHolder
    )
    val bprConfig =
      BPRSimConfig(
        jdeqConfig.getSimulationEndTime,
        1,
        0,
        1.0,
        900,
        travelTimeFunction,
        maybeCaccSettings
      )
    val (eventManager: EventsManagerImpl, eventBuffer: BufferEventHandler) = createEventManager
    val sim = new BPRSimulation(scenario, bprConfig, eventManager)
    sim.run()

    AverageLinkTravelTimeCalculationResults.getFrom(eventBuffer.buffer)
  }

  def ParBPRSimAvgLinkTravelTime(
    maybeCaccSettings: Option[CACCSettings],
    maybePickUpDropOffHolder: Option[PickUpDropOffHolder]
  ): AverageLinkTravelTimeCalculationResults = {
    val travelTimeFunction = JDEQSimRunner.getTravelTimeFunction(
      "FREE_FLOW",
      beamConfig.beam.physsim.flowCapacityFactor,
      0,
      maybeCaccSettings,
      maybePickUpDropOffHolder
    )
    val bprConfig =
      BPRSimConfig(
        jdeqConfig.getSimulationEndTime,
        8,
        60,
        1.0,
        900,
        travelTimeFunction,
        None
      )
    val (eventManager: EventsManagerImpl, eventBuffer: BufferEventHandler) = createEventManager
    val sim = new ParallelBPRSimulation(scenario, bprConfig, eventManager, 42)
    sim.run()

    AverageLinkTravelTimeCalculationResults.getFrom(eventBuffer.buffer)
  }

  def JDEQSimAvgLinkTravelTime(
    maybeCaccSettings: Option[CACCSettings],
    maybePickUpDropOffHolder: Option[PickUpDropOffHolder]
  ): AverageLinkTravelTimeCalculationResults = {
    val (eventManager: EventsManagerImpl, eventBuffer: BufferEventHandler) = createEventManager
    val sim = new cacc.sim.JDEQSimulation(
      jdeqConfig,
      beamConfig,
      scenario,
      eventManager,
      maybeCaccSettings,
      maybePickUpDropOffHolder
    )

    sim.run()
    AverageLinkTravelTimeCalculationResults.getFrom(eventBuffer.buffer)
  }

  class AverageLinkTravelTimeCalculationResults(
    val averageLinkTravelTime: Double,
    val linkTravelTimes: ListBuffer[Double],
    val events: ArrayBuffer[Event],
    val allVehicleIds: String,
    val lintPickUpsDropOffs: String
  ) {
    override def toString: String = s"$averageLinkTravelTime [${events.size} events ${linkTravelTimes.size} ltt's]"
  }

  object AverageLinkTravelTimeCalculationResults {
    // threshold for link travel time to be picked up or thrown away
    val maxThreshold = 1000.0
    val minThreshold = 1.0

    def getFrom(events: ArrayBuffer[Event]): AverageLinkTravelTimeCalculationResults = {
      case class VehicleAtLink(linkId: Id[Link], vehicleId: Id[Vehicle])
      object VehicleAtLink {
        def apply(enterEvent: LinkEnterEvent): VehicleAtLink = {
          new VehicleAtLink(enterEvent.getLinkId, enterEvent.getVehicleId)
        }
        def apply(leaveEvent: LinkLeaveEvent): VehicleAtLink = {
          new VehicleAtLink(leaveEvent.getLinkId, leaveEvent.getVehicleId)
        }
      }

      val vehicleEnterLink = scala.collection.mutable.HashMap.empty[VehicleAtLink, Double]
      val linkTravelTimes = scala.collection.mutable.ListBuffer.empty[Double]

      val linkToTime = mutable.HashMap.empty[Id[Link], mutable.ArrayBuffer[Double]]
      val vehicleIds = mutable.HashSet.empty[Id[Vehicle]]
      def saveLinkTime(linkId: Id[Link], time: Double): Unit = {

        linkToTime.get(linkId) match {
          case Some(times) => times.append(time)
          case None        => linkToTime(linkId) = mutable.ArrayBuffer(time)
        }
      }

      events.foreach {
        case enterEvent: LinkEnterEvent =>
          vehicleEnterLink.put(VehicleAtLink(enterEvent), enterEvent.getTime)

          saveLinkTime(enterEvent.getLinkId, enterEvent.getTime)
          vehicleIds.add(enterEvent.getVehicleId)

        case leaveEvent: LinkLeaveEvent =>
          val vehicleAtLink = VehicleAtLink(leaveEvent)
          vehicleEnterLink.get(vehicleAtLink) match {
            case Some(time) => linkTravelTimes.append(leaveEvent.getTime - time)
            case None       =>
          }

          saveLinkTime(leaveEvent.getLinkId, leaveEvent.getTime)
          vehicleIds.add(leaveEvent.getVehicleId)

        case _ =>
      }

      val truncatedMean = linkTravelTimes.filter { x =>
        minThreshold < x && x < maxThreshold
      }

      //      val allVehiclesStr = s"Seq(${vehicleIds.mkString(", ")})"
      //      val linkToPickUpsDropOffsStr: String = {
      //        def getTTVC(times: Seq[Double]): String = {
      //          val timesStr = times.mkString(",")
      //          val valuesStr = times.map(_ => 1).mkString(",")
      //          s"new TimeToValueCollection(times = mutable.ArrayBuffer[Double]($timesStr), values = mutable.ArrayBuffer[Int]($valuesStr))"
      //        }
      //        def getLinkPickUpsDropOffs(linkId: Id[Link], times: Seq[Double]): String = {
      //          s"${linkId.toString} -> LinkPickUpsDropOffs(${linkId.toString}, timeToPickUps = ${getTTVC(times)}, timeToDropOffs = ${getTTVC(times)})"
      //        }
      //        val linkPUDO = linkToTime.map { case (linkId, times) => getLinkPickUpsDropOffs(linkId, times) }
      //        s"mutable.HashMap[Int, LinkPickUpsDropOffs](${linkPUDO.mkString(",")})"
      //      }

      new AverageLinkTravelTimeCalculationResults(
        truncatedMean.sum / truncatedMean.size,
        linkTravelTimes,
        events,
        "uncomment allVehiclesStr above",
        "uncomment linkToPickUpsDropOffsStr above"
      )
    }
  }
}

object PhysSimTravelTimeWithCACCPickUpsDropOffs {

  def readNetwork(path: String): Network = {
    val network = NetworkUtils.createNetwork()
    new MatsimNetworkReader(network)
      .readFile(path)
    network.getLinks.values().asScala.foreach { link =>
      link.setCapacity(10000)
    }
    network
  }

  def readScenario(matsimConfig: MatsimConfig, network: Network, path: String): MutableScenario = {
    val scenario = ScenarioUtils.createMutableScenario(matsimConfig)
    new PopulationReader(scenario).readFile(path)
    scenario.setNetwork(network)
    scenario
  }

  private def createEventManager = {
    val eventManager = new EventsManagerImpl
    val bufferEventHandler = new BufferEventHandler
    eventManager.addHandler(bufferEventHandler)
    (eventManager, bufferEventHandler)
  }

  val beamvilleAllVehiclesFromSimulation: Seq[Int] = Seq(19, 17, 2, 13, 10, 14, 11, 18, 15, 12)

  val beamvilleLinkPickUpsDropOffsFromSimulation: mutable.HashMap[Int, LinkPickUpsDropOffs] =
    mutable.HashMap[Int, LinkPickUpsDropOffs](
      57 -> LinkPickUpsDropOffs(
        57,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](32409.710260994303, 39609.7102609943),
          values = mutable.ArrayBuffer[Int](1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](32409.710260994303, 39609.7102609943),
          values = mutable.ArrayBuffer[Int](1, 1)
        )
      ),
      248 -> LinkPickUpsDropOffs(
        248,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](26157.710260994303, 26158.420521988606, 36957.7102609943,
            36958.420521988606, 44157.7102609943, 44158.420521988606),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](26157.710260994303, 26158.420521988606, 36957.7102609943,
            36958.420521988606, 44157.7102609943, 44158.420521988606),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        )
      ),
      143 -> LinkPickUpsDropOffs(
        143,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](21670.31769190209),
          values = mutable.ArrayBuffer[Int](1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](21670.31769190209),
          values = mutable.ArrayBuffer[Int](1)
        )
      ),
      158 -> LinkPickUpsDropOffs(
        158,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](26300.476491694022, 26370.794183596114, 37100.476491694026,
            37170.79418359612, 44300.476491694026, 44370.79418359612),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](26300.476491694022, 26370.794183596114, 37100.476491694026,
            37170.79418359612, 44300.476491694026, 44370.79418359612),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        )
      ),
      60 -> LinkPickUpsDropOffs(
        60,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](21670.31769190209, 21671.027952896395),
          values = mutable.ArrayBuffer[Int](1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](21670.31769190209, 21671.027952896395),
          values = mutable.ArrayBuffer[Int](1, 1)
        )
      ),
      302 -> LinkPickUpsDropOffs(
        302,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](26159.13078298291, 26300.476491694022, 36959.13078298291,
            37100.476491694026, 44159.13078298291, 44300.476491694026),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](26159.13078298291, 26300.476491694022, 36959.13078298291,
            37100.476491694026, 44159.13078298291, 44300.476491694026),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        )
      ),
      128 -> LinkPickUpsDropOffs(
        128,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72069.02795289639, 72139.34564479848),
          values = mutable.ArrayBuffer[Int](1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72069.02795289639, 72139.34564479848),
          values = mutable.ArrayBuffer[Int](1, 1)
        )
      ),
      206 -> LinkPickUpsDropOffs(
        206,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](24460.31769190209, 24530.635383804183, 28060.31769190209,
            28130.635383804183, 31660.31769190209, 31730.635383804183, 35260.31769190209, 35330.63538380418,
            38860.31769190209, 38930.63538380418, 42460.31769190209, 42530.63538380418, 46060.31769190209,
            46130.63538380418, 49660.31769190209, 49730.63538380418, 53260.31769190209, 53330.63538380418),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](24460.31769190209, 24530.635383804183, 28060.31769190209,
            28130.635383804183, 31660.31769190209, 31730.635383804183, 35260.31769190209, 35330.63538380418,
            38860.31769190209, 38930.63538380418, 42460.31769190209, 42530.63538380418, 46060.31769190209,
            46130.63538380418, 49660.31769190209, 49730.63538380418, 53260.31769190209, 53330.63538380418),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        )
      ),
      196 -> LinkPickUpsDropOffs(
        196,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72211.79411968346, 72212.50438067775, 75811.79411968346,
            79411.79411968346, 83011.79411968346, 86611.79411968346, 90211.79411968346, 93811.79411968346,
            97411.79411968346, 101011.79411968346),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72211.79411968346, 72212.50438067775, 75811.79411968346,
            79411.79411968346, 83011.79411968346, 86611.79411968346, 90211.79411968346, 93811.79411968346,
            97411.79411968346, 101011.79411968346),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        )
      ),
      134 -> LinkPickUpsDropOffs(
        134,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](26371.504444590417, 26372.21470558472, 37171.50444459042,
            37172.21470558472, 44371.50444459042, 44372.21470558472),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](26371.504444590417, 26372.21470558472, 37171.50444459042,
            37172.21470558472, 44371.50444459042, 44372.21470558472),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        )
      ),
      74 -> LinkPickUpsDropOffs(
        74,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](21742.766166787093, 21743.476427781396, 32480.027952896395,
            32480.738213890698, 39680.027952896395, 39680.7382138907),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](21742.766166787093, 21743.476427781396, 32480.027952896395,
            32480.738213890698, 39680.027952896395, 39680.7382138907),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        )
      ),
      184 -> LinkPickUpsDropOffs(
        184,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](24532.05590579279, 24602.37359769488, 28132.05590579279,
            28202.37359769488, 31732.05590579279, 31802.37359769488, 35332.05590579279, 35402.37359769488,
            38932.05590579279, 39002.37359769488, 42532.05590579279, 42602.37359769488, 46132.05590579279,
            46202.37359769488, 49732.05590579279, 49802.37359769488, 53332.05590579279, 53402.37359769488),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](24532.05590579279, 24602.37359769488, 28132.05590579279,
            28202.37359769488, 31732.05590579279, 31802.37359769488, 35332.05590579279, 35402.37359769488,
            38932.05590579279, 39002.37359769488, 42532.05590579279, 42602.37359769488, 46132.05590579279,
            46202.37359769488, 49732.05590579279, 49802.37359769488, 53332.05590579279, 53402.37359769488),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        )
      ),
      122 -> LinkPickUpsDropOffs(
        122,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72139.34564479848, 72140.05590579277),
          values = mutable.ArrayBuffer[Int](1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72139.34564479848, 72140.05590579277),
          values = mutable.ArrayBuffer[Int](1, 1)
        )
      ),
      68 -> LinkPickUpsDropOffs(
        68,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](21743.476427781396, 21744.1866887757, 32480.738213890698, 32481.448474885,
            39680.7382138907, 39681.448474885),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](21743.476427781396, 21744.1866887757, 32480.738213890698, 32481.448474885,
            39680.7382138907, 39681.448474885),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        )
      ),
      80 -> LinkPickUpsDropOffs(
        80,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](21672.448474885, 21742.766166787093, 32409.710260994303,
            32480.027952896395, 39609.7102609943, 39680.027952896395),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](21672.448474885, 21742.766166787093, 32409.710260994303,
            32480.027952896395, 39609.7102609943, 39680.027952896395),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        )
      ),
      178 -> LinkPickUpsDropOffs(
        178,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](24531.345644798486, 24532.05590579279, 28131.345644798486,
            28132.05590579279, 31731.345644798486, 31732.05590579279, 35331.345644798486, 35332.05590579279,
            38931.345644798486, 38932.05590579279, 42531.345644798486, 42532.05590579279, 46131.345644798486,
            46132.05590579279, 49731.345644798486, 49732.05590579279, 53331.345644798486, 53332.05590579279),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](24531.345644798486, 24532.05590579279, 28131.345644798486,
            28132.05590579279, 31731.345644798486, 31732.05590579279, 35331.345644798486, 35332.05590579279,
            38931.345644798486, 38932.05590579279, 42531.345644798486, 42532.05590579279, 46131.345644798486,
            46132.05590579279, 49731.345644798486, 49732.05590579279, 53331.345644798486, 53332.05590579279),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        )
      ),
      116 -> LinkPickUpsDropOffs(
        116,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72140.05590579277, 72140.76616678707, 75740.05590579277,
            75740.76616678707, 79340.05590579277, 79340.76616678707, 82940.05590579277, 82940.76616678707,
            86540.05590579277, 86540.76616678707, 90140.05590579277, 90140.76616678707, 93740.05590579277,
            93740.76616678707, 97340.05590579277, 97340.76616678707, 100940.05590579277, 100940.76616678707),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72140.05590579277, 72140.76616678707, 75740.05590579277,
            75740.76616678707, 79340.05590579277, 79340.76616678707, 82940.05590579277, 82940.76616678707,
            86540.05590579277, 86540.76616678707, 90140.05590579277, 90140.76616678707, 93740.05590579277,
            93740.76616678707, 97340.05590579277, 97340.76616678707, 100940.05590579277, 100940.76616678707),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        )
      ),
      62 -> LinkPickUpsDropOffs(
        62,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](21671.738213890698, 21672.448474885),
          values = mutable.ArrayBuffer[Int](1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](21671.738213890698, 21672.448474885),
          values = mutable.ArrayBuffer[Int](1, 1)
        )
      ),
      154 -> LinkPickUpsDropOffs(
        154,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](21744.1866887757, 21814.50438067779, 32481.448474885, 32551.766166787093,
            39681.448474885, 39751.76616678709),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](21744.1866887757, 21814.50438067779, 32481.448474885, 32551.766166787093,
            39681.448474885, 39751.76616678709),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        )
      ),
      44 -> LinkPickUpsDropOffs(
        44,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72079.44847488498, 72079.80350951319, 75679.44847488498,
            75679.80350951319, 79279.44847488498, 79279.80350951319, 82879.44847488498, 82879.80350951319,
            86479.44847488498, 86479.80350951319, 90079.44847488498, 90079.80350951319, 93679.44847488498,
            93679.80350951319, 97279.44847488498, 97279.80350951319),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72079.44847488498, 72079.80350951319, 75679.44847488498,
            75679.80350951319, 79279.44847488498, 79279.80350951319, 82879.44847488498, 82879.80350951319,
            86479.44847488498, 86479.80350951319, 90079.44847488498, 90079.80350951319, 93679.44847488498,
            93679.80350951319, 97279.44847488498, 97279.80350951319),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        )
      ),
      50 -> LinkPickUpsDropOffs(
        50,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72078.73821389068, 72079.44847488498, 75678.73821389068,
            75679.44847488498, 79278.73821389068, 79279.44847488498, 82878.73821389068, 82879.44847488498,
            86478.73821389068, 86479.44847488498, 90078.73821389068, 90079.44847488498, 93678.73821389068,
            93679.44847488498, 97278.73821389068, 97279.44847488498),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72078.73821389068, 72079.44847488498, 75678.73821389068,
            75679.44847488498, 79278.73821389068, 79279.44847488498, 82878.73821389068, 82879.44847488498,
            86478.73821389068, 86479.44847488498, 90078.73821389068, 90079.44847488498, 93678.73821389068,
            93679.44847488498, 97278.73821389068, 97279.44847488498),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        )
      ),
      38 -> LinkPickUpsDropOffs(
        38,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72007.7102609943, 72008.42052198859, 75607.7102609943, 75608.42052198859,
            79207.7102609943, 79208.42052198859, 82807.7102609943, 82808.42052198859, 86407.7102609943,
            86408.42052198859, 90007.7102609943, 90008.42052198859, 93607.7102609943, 93608.42052198859,
            97207.7102609943, 97208.42052198859),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72007.7102609943, 72008.42052198859, 75607.7102609943, 75608.42052198859,
            79207.7102609943, 79208.42052198859, 82807.7102609943, 82808.42052198859, 86407.7102609943,
            86408.42052198859, 90007.7102609943, 90008.42052198859, 93607.7102609943, 93608.42052198859,
            97207.7102609943, 97208.42052198859),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        )
      ),
      229 -> LinkPickUpsDropOffs(
        229,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72212.50438067775),
          values = mutable.ArrayBuffer[Int](1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72212.50438067775),
          values = mutable.ArrayBuffer[Int](1)
        )
      ),
      340 -> LinkPickUpsDropOffs(
        340,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](26157.355034628214, 26157.710260994303, 36957.35503462821,
            36957.7102609943, 44157.35503462821, 44157.7102609943),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](26157.355034628214, 26157.710260994303, 36957.35503462821,
            36957.7102609943, 44157.35503462821, 44157.7102609943),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        )
      ),
      136 -> LinkPickUpsDropOffs(
        136,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](26370.794183596114, 26371.504444590417, 37170.79418359612,
            37171.50444459042, 44370.79418359612, 44371.50444459042),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](26370.794183596114, 26371.504444590417, 37170.79418359612,
            37171.50444459042, 44370.79418359612, 44371.50444459042),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        )
      ),
      316 -> LinkPickUpsDropOffs(
        316,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72211.43889331738, 72211.79411968346, 75811.43889331738,
            75811.79411968346, 79411.43889331738, 79411.79411968346, 83011.43889331738, 83011.79411968346,
            86611.43889331738, 86611.79411968346, 90211.43889331738, 90211.79411968346, 93811.43889331738,
            93811.79411968346, 97411.43889331738, 97411.79411968346, 101011.43889331738, 101011.79411968346),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72211.43889331738, 72211.79411968346, 75811.43889331738,
            75811.79411968346, 79411.43889331738, 79411.79411968346, 83011.43889331738, 83011.79411968346,
            86611.43889331738, 86611.79411968346, 90211.43889331738, 90211.79411968346, 93811.43889331738,
            93811.79411968346, 97411.43889331738, 97411.79411968346, 101011.43889331738, 101011.79411968346),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        )
      ),
      202 -> LinkPickUpsDropOffs(
        202,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72140.76616678707, 72211.08385868916, 75740.76616678707,
            75811.08385868916, 79340.76616678707, 79411.08385868916, 82940.76616678707, 83011.08385868916,
            86540.76616678707, 86611.08385868916, 90140.76616678707, 90211.08385868916, 93740.76616678707,
            93811.08385868916, 97340.76616678707, 97411.08385868916, 100940.76616678707, 101011.08385868916),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72140.76616678707, 72211.08385868916, 75740.76616678707,
            75811.08385868916, 79340.76616678707, 79411.08385868916, 82940.76616678707, 83011.08385868916,
            86540.76616678707, 86611.08385868916, 90140.76616678707, 90211.08385868916, 93740.76616678707,
            93811.08385868916, 97340.76616678707, 97411.08385868916, 100940.76616678707, 101011.08385868916),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        )
      ),
      244 -> LinkPickUpsDropOffs(
        244,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](26157.355034628214, 36957.35503462821, 44157.35503462821),
          values = mutable.ArrayBuffer[Int](1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](26157.355034628214, 36957.35503462821, 44157.35503462821),
          values = mutable.ArrayBuffer[Int](1, 1, 1)
        )
      ),
      180 -> LinkPickUpsDropOffs(
        180,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](24530.635383804183, 24531.345644798486, 28130.635383804183,
            28131.345644798486, 31730.635383804183, 31731.345644798486, 35330.63538380418, 35331.345644798486,
            38930.63538380418, 38931.345644798486, 42530.63538380418, 42531.345644798486, 46130.63538380418,
            46131.345644798486, 49730.63538380418, 49731.345644798486, 53330.63538380418, 53331.345644798486),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](24530.635383804183, 24531.345644798486, 28130.635383804183,
            28131.345644798486, 31730.635383804183, 31731.345644798486, 35330.63538380418, 35331.345644798486,
            38930.63538380418, 38931.345644798486, 42530.63538380418, 42531.345644798486, 46130.63538380418,
            46131.345644798486, 49730.63538380418, 49731.345644798486, 53330.63538380418, 53331.345644798486),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        )
      ),
      106 -> LinkPickUpsDropOffs(
        106,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72068.31769190209, 72069.02795289639),
          values = mutable.ArrayBuffer[Int](1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72068.31769190209, 72069.02795289639),
          values = mutable.ArrayBuffer[Int](1, 1)
        )
      ),
      360 -> LinkPickUpsDropOffs(
        360,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](21814.859415306004, 32552.121201415306, 39752.121201415306),
          values = mutable.ArrayBuffer[Int](1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](21814.859415306004, 32552.121201415306, 39752.121201415306),
          values = mutable.ArrayBuffer[Int](1, 1, 1)
        )
      ),
      52 -> LinkPickUpsDropOffs(
        52,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72008.42052198859, 72078.73821389068, 75608.42052198859,
            75678.73821389068, 79208.42052198859, 79278.73821389068, 82808.42052198859, 82878.73821389068,
            86408.42052198859, 86478.73821389068, 90008.42052198859, 90078.73821389068, 93608.42052198859,
            93678.73821389068, 97208.42052198859, 97278.73821389068),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72008.42052198859, 72078.73821389068, 75608.42052198859,
            75678.73821389068, 79208.42052198859, 79278.73821389068, 82808.42052198859, 82878.73821389068,
            86408.42052198859, 86478.73821389068, 90008.42052198859, 90078.73821389068, 93608.42052198859,
            93678.73821389068, 97208.42052198859, 97278.73821389068),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        )
      ),
      109 -> LinkPickUpsDropOffs(
        109,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](24603.083858689184, 72068.31769190209),
          values = mutable.ArrayBuffer[Int](1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](24603.083858689184, 72068.31769190209),
          values = mutable.ArrayBuffer[Int](1, 1)
        )
      ),
      192 -> LinkPickUpsDropOffs(
        192,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72211.08385868916, 72211.43889331738, 75811.08385868916,
            75811.43889331738, 79411.08385868916, 79411.43889331738, 83011.08385868916, 83011.43889331738,
            86611.08385868916, 86611.43889331738, 90211.08385868916, 90211.43889331738, 93811.08385868916,
            93811.43889331738, 97411.08385868916, 97411.43889331738, 101011.08385868916, 101011.43889331738),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72211.08385868916, 72211.43889331738, 75811.08385868916,
            75811.43889331738, 79411.08385868916, 79411.43889331738, 83011.08385868916, 83011.43889331738,
            86611.08385868916, 86611.43889331738, 90211.08385868916, 90211.43889331738, 93811.08385868916,
            93811.43889331738, 97411.08385868916, 97411.43889331738, 101011.08385868916, 101011.43889331738),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        )
      ),
      34 -> LinkPickUpsDropOffs(
        34,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72007.7102609943, 75607.7102609943, 79207.7102609943, 82807.7102609943,
            86407.7102609943, 90007.7102609943, 93607.7102609943, 97207.7102609943),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72007.7102609943, 75607.7102609943, 79207.7102609943, 82807.7102609943,
            86407.7102609943, 90007.7102609943, 93607.7102609943, 97207.7102609943),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1)
        )
      ),
      144 -> LinkPickUpsDropOffs(
        144,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](21814.50438067779, 21814.859415306004, 32551.766166787093,
            32552.121201415306, 39751.76616678709, 39752.121201415306),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](21814.50438067779, 21814.859415306004, 32551.766166787093,
            32552.121201415306, 39751.76616678709, 39752.121201415306),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        )
      ),
      126 -> LinkPickUpsDropOffs(
        126,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72080.15873587927, 75680.15873587927, 75740.05590579277,
            79280.15873587927, 79340.05590579277, 82880.15873587927, 82940.05590579277, 86480.15873587927,
            86540.05590579277, 90080.15873587927, 90140.05590579277, 93680.15873587927, 93740.05590579277,
            97280.15873587927, 97340.05590579277, 100940.05590579277),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72080.15873587927, 75680.15873587927, 75740.05590579277,
            79280.15873587927, 79340.05590579277, 82880.15873587927, 82940.05590579277, 86480.15873587927,
            86540.05590579277, 90080.15873587927, 90140.05590579277, 93680.15873587927, 93740.05590579277,
            97280.15873587927, 97340.05590579277, 100940.05590579277),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        )
      ),
      246 -> LinkPickUpsDropOffs(
        246,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](26158.420521988606, 26159.13078298291, 36958.420521988606,
            36959.13078298291, 44158.420521988606, 44159.13078298291),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](26158.420521988606, 26159.13078298291, 36958.420521988606,
            36959.13078298291, 44158.420521988606, 44159.13078298291),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1)
        )
      ),
      58 -> LinkPickUpsDropOffs(
        58,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](21671.027952896395, 21671.738213890698),
          values = mutable.ArrayBuffer[Int](1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](21671.027952896395, 21671.738213890698),
          values = mutable.ArrayBuffer[Int](1, 1)
        )
      ),
      228 -> LinkPickUpsDropOffs(
        228,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](24460.31769190209, 28060.31769190209, 31660.31769190209,
            35260.31769190209, 38860.31769190209, 42460.31769190209, 46060.31769190209, 49660.31769190209,
            53260.31769190209),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](24460.31769190209, 28060.31769190209, 31660.31769190209,
            35260.31769190209, 38860.31769190209, 42460.31769190209, 46060.31769190209, 49660.31769190209,
            53260.31769190209),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1)
        )
      ),
      336 -> LinkPickUpsDropOffs(
        336,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72079.80350951319, 72080.15873587927, 75679.80350951319,
            75680.15873587927, 79279.80350951319, 79280.15873587927, 82879.80350951319, 82880.15873587927,
            86479.80350951319, 86480.15873587927, 90079.80350951319, 90080.15873587927, 93679.80350951319,
            93680.15873587927, 97279.80350951319, 97280.15873587927),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](72079.80350951319, 72080.15873587927, 75679.80350951319,
            75680.15873587927, 79279.80350951319, 79280.15873587927, 82879.80350951319, 82880.15873587927,
            86479.80350951319, 86480.15873587927, 90079.80350951319, 90080.15873587927, 93679.80350951319,
            93680.15873587927, 97279.80350951319, 97280.15873587927),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        )
      ),
      138 -> LinkPickUpsDropOffs(
        138,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](26372.21470558472, 37172.21470558472, 44372.21470558472),
          values = mutable.ArrayBuffer[Int](1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](26372.21470558472, 37172.21470558472, 44372.21470558472),
          values = mutable.ArrayBuffer[Int](1, 1, 1)
        )
      ),
      102 -> LinkPickUpsDropOffs(
        102,
        timeToPickUps = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](24602.37359769488, 24603.083858689184, 28202.37359769488,
            31802.37359769488, 35402.37359769488, 39002.37359769488, 42602.37359769488, 46202.37359769488,
            49802.37359769488, 53402.37359769488),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        ),
        timeToDropOffs = new TimeToValueCollection(
          times = mutable.ArrayBuffer[Double](24602.37359769488, 24603.083858689184, 28202.37359769488,
            31802.37359769488, 35402.37359769488, 39002.37359769488, 42602.37359769488, 46202.37359769488,
            49802.37359769488, 53402.37359769488),
          values = mutable.ArrayBuffer[Int](1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        )
      )
    )
}

class BufferEventHandler extends BasicEventHandler {
  val buffer: ArrayBuffer[Event] = mutable.ArrayBuffer.empty[Event]

  override def handleEvent(event: Event): Unit = {
    buffer += event
  }
}
