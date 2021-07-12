package beam.router.skim.urbansim

import akka.actor.ActorSystem
import beam.router.FreeFlowTravelTime
import beam.router.Modes.BeamMode
import beam.router.skim.ActivitySimSkimmer.ActivitySimSkimmerKey
import beam.router.skim.ActivitySimPathType
import beam.router.skim.core.{AbstractSkimmerInternal, AbstractSkimmerKey}
import beam.sim.{BeamHelper, BeamServices}
import beam.sim.config.BeamExecutionConfig
import beam.utils.TestConfigUtils.testConfig
import com.google.inject.Injector
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.core.scenario.MutableScenario
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.concurrent.Await

class BackgroundSkimsCreatorTest extends AnyFlatSpec with Matchers with BeamHelper {

  val actorSystemName = "BackgroundSkimsCreatorTest"

  val config: Config = ConfigFactory
    .parseString(
      s"""
        |beam.actorSystemName = "$actorSystemName"
        |beam.routing.carRouter="staticGH"
        |beam.urbansim.backgroundODSkimsCreator.skimsKind = "activitySim"
        |beam.urbansim.backgroundODSkimsCreator.routerType = "r5+gh"
        |beam.agentsim.taz.filePath = test/test-resources/taz-centers.12.csv
        |beam.urbansim.backgroundODSkimsCreator.maxTravelDistanceInMeters.walk = 1000
      """.stripMargin
    )
    .withFallback(testConfig("test/input/sf-light/sf-light-1k.conf"))
    .resolve()

  implicit val actorSystem: ActorSystem = ActorSystem(s"$actorSystemName", config)

  val beamExecutionConfig: BeamExecutionConfig = setupBeamWithConfig(config)

  val (scenarioBuilt, beamScenario, _) = buildBeamServicesAndScenario(
    beamExecutionConfig.beamConfig,
    beamExecutionConfig.matsimConfig
  )
  val scenario: MutableScenario = scenarioBuilt
  val injector: Injector = buildInjector(config, beamExecutionConfig.beamConfig, scenario, beamScenario)
  val beamServices: BeamServices = buildBeamServices(injector, scenario)

  def createBackgroundSkimsCreator(
    modes: Seq[BeamMode],
    withTransit: Boolean,
    buildDirectWalkRoute: Boolean,
    buildDirectCarRoute: Boolean
  ): BackgroundSkimsCreator = {
    val tazClustering: TAZClustering = new TAZClustering(beamScenario.tazTreeMap)
    val tazActivitySimSkimmer = BackgroundSkimsCreator.createTAZActivitySimSkimmer(beamServices, tazClustering)
    new BackgroundSkimsCreator(
      beamServices,
      beamScenario,
      tazClustering,
      tazActivitySimSkimmer,
      new FreeFlowTravelTime,
      modes,
      withTransit = withTransit,
      buildDirectWalkRoute = buildDirectWalkRoute,
      buildDirectCarRoute = buildDirectCarRoute,
      calculationTimeoutHours = 1
    )(actorSystem)
  }

  "skims creator" should "generate WALK skims only" in {
    val skimsCreator =
      createBackgroundSkimsCreator(
        modes = Seq(BeamMode.CAR, BeamMode.WALK),
        withTransit = false,
        buildDirectCarRoute = false,
        buildDirectWalkRoute = true
      )
    skimsCreator.start()
    skimsCreator.increaseParallelismTo(Runtime.getRuntime.availableProcessors())

    val finalSkimmer = Await.result(skimsCreator.getResult, 10.minutes).abstractSkimmer
    skimsCreator.stop()

    val skims: Map[AbstractSkimmerKey, AbstractSkimmerInternal] = finalSkimmer.readOnlySkim.currentSkim
    val keys = skims.keys.map(_.asInstanceOf[ActivitySimSkimmerKey]).toSeq

    keys.count(_.pathType != ActivitySimPathType.WALK) shouldBe 0
    keys.size shouldBe 22 // because max walk trip length is 1000 meters
  }

  "skims creator" should "generate CAR skims only" in {
    val skimsCreator =
      createBackgroundSkimsCreator(
        modes = Seq(BeamMode.CAR, BeamMode.WALK),
        withTransit = false,
        buildDirectCarRoute = true,
        buildDirectWalkRoute = false
      )
    skimsCreator.start()
    skimsCreator.increaseParallelismTo(Runtime.getRuntime.availableProcessors())

    val finalSkimmer = Await.result(skimsCreator.getResult, 10.minutes).abstractSkimmer
    skimsCreator.stop()

    val skims: Map[AbstractSkimmerKey, AbstractSkimmerInternal] = finalSkimmer.readOnlySkim.currentSkim
    val keys = skims.keys.map(_.asInstanceOf[ActivitySimSkimmerKey]).toSeq

    keys.count(_.pathType != ActivitySimPathType.SOV) shouldBe 0
    keys.size shouldBe 144
  }

  "skims creator" should "generate transit skims only" in {
    val skimsCreator =
      createBackgroundSkimsCreator(
        modes = Seq(BeamMode.CAR, BeamMode.WALK),
        withTransit = true,
        buildDirectCarRoute = false,
        buildDirectWalkRoute = false
      )
    skimsCreator.start()
    skimsCreator.increaseParallelismTo(Runtime.getRuntime.availableProcessors())

    val finalSkimmer = Await.result(skimsCreator.getResult, 10.minutes).abstractSkimmer
    skimsCreator.stop()

    val skims: Map[AbstractSkimmerKey, AbstractSkimmerInternal] = finalSkimmer.readOnlySkim.currentSkim

    val pathTypeToSkimsCount = skims.keys
      .map(_.asInstanceOf[ActivitySimSkimmerKey])
      .foldLeft(scala.collection.mutable.HashMap.empty[ActivitySimPathType, Int]) {
        case (pathTypeToCount, skimmerKey) =>
          pathTypeToCount.get(skimmerKey.pathType) match {
            case Some(count) => pathTypeToCount(skimmerKey.pathType) = count + 1
            case None        => pathTypeToCount(skimmerKey.pathType) = 1
          }
          pathTypeToCount
      }

    pathTypeToSkimsCount(ActivitySimPathType.DRV_HVY_WLK) shouldBe 12
    pathTypeToSkimsCount(ActivitySimPathType.WLK_LOC_WLK) shouldBe 86
    pathTypeToSkimsCount(ActivitySimPathType.DRV_LRF_WLK) shouldBe 19
    pathTypeToSkimsCount(ActivitySimPathType.WLK_LRF_WLK) shouldBe 28
    pathTypeToSkimsCount(ActivitySimPathType.WLK_HVY_WLK) shouldBe 24
    pathTypeToSkimsCount(ActivitySimPathType.DRV_LOC_WLK) shouldBe 31

    skims.keys.size shouldBe (12 + 86 + 19 + 28 + 24 + 31)
  }

  "skims creator" should "generate all types of skims" in {
    val skimsCreator =
      createBackgroundSkimsCreator(
        modes = Seq(BeamMode.CAR, BeamMode.WALK),
        withTransit = true,
        buildDirectCarRoute = true,
        buildDirectWalkRoute = true
      )
    skimsCreator.start()
    skimsCreator.increaseParallelismTo(Runtime.getRuntime.availableProcessors())

    val finalSkimmer = Await.result(skimsCreator.getResult, 10.minutes).abstractSkimmer
    skimsCreator.stop()

    val skims: Map[AbstractSkimmerKey, AbstractSkimmerInternal] = finalSkimmer.readOnlySkim.currentSkim

    val pathTypeToSkimsCount = skims.keys
      .map(_.asInstanceOf[ActivitySimSkimmerKey])
      .foldLeft(scala.collection.mutable.HashMap.empty[ActivitySimPathType, Int]) {
        case (pathTypeToCount, skimmerKey) =>
          pathTypeToCount.get(skimmerKey.pathType) match {
            case Some(count) => pathTypeToCount(skimmerKey.pathType) = count + 1
            case None        => pathTypeToCount(skimmerKey.pathType) = 1
          }
          pathTypeToCount
      }

    pathTypeToSkimsCount(ActivitySimPathType.DRV_HVY_WLK) shouldBe 12
    pathTypeToSkimsCount(ActivitySimPathType.WLK_LOC_WLK) shouldBe 86
    pathTypeToSkimsCount(ActivitySimPathType.DRV_LRF_WLK) shouldBe 19
    pathTypeToSkimsCount(ActivitySimPathType.WLK_LRF_WLK) shouldBe 28
    pathTypeToSkimsCount(ActivitySimPathType.WLK_HVY_WLK) shouldBe 24
    pathTypeToSkimsCount(ActivitySimPathType.DRV_LOC_WLK) shouldBe 31

    pathTypeToSkimsCount(ActivitySimPathType.SOV) shouldBe 144
    pathTypeToSkimsCount(ActivitySimPathType.WALK) shouldBe 22 // because max walk trip length is 1000 meters

    val walkKeys = skims.keys.filter {
      case k: ActivitySimSkimmerKey => k.pathType == ActivitySimPathType.WALK
    }
    val walkSkims = walkKeys.map(key => key -> skims.get(key)).toMap
    walkSkims.size shouldBe 22

    val walkTransitKeys = skims.keys.filter {
      case k: ActivitySimSkimmerKey => k.pathType == ActivitySimPathType.WLK_LOC_WLK
    }
    val walkTransitSkims = walkTransitKeys.map(key => key -> skims.get(key)).toMap
    walkTransitSkims.size shouldBe 86

    skims.keys.size shouldBe (12 + 86 + 19 + 28 + 24 + 31 + 144 + 22)
  }
}
