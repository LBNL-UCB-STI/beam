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
        |beam.routing.r5.linkRadiusMeters = 10000
        |beam.routing.r5.accessBufferTimeSeconds.car = 120
        |beam.routing.r5.transitAlternativeList = "OPTIMAL"
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
  val beamServices: BeamServices = buildBeamServices(injector)

  def createBackgroundSkimsCreator(
    modes: Seq[BeamMode],
    withTransit: Boolean,
    buildDirectWalkRoute: Boolean,
    buildDirectCarRoute: Boolean
  ): BackgroundSkimsCreator = {
    val tazClustering: TAZClustering = new TAZClustering(beamScenario.tazTreeMapForASimSkimmer)
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

    val skims: collection.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = finalSkimmer.currentSkim
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

    val skims: collection.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = finalSkimmer.currentSkim
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

    val skims: collection.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = finalSkimmer.currentSkim

    val pathTypeToSkimsCount = skims.keys
      .map(_.asInstanceOf[ActivitySimSkimmerKey])
      .groupBy(_.pathType)
      .mapValues(_.size)

    println(pathTypeToSkimsCount)

    pathTypeToSkimsCount.keySet should contain only (
      ActivitySimPathType.DRV_HVY_WLK,
      ActivitySimPathType.WLK_LOC_WLK,
      ActivitySimPathType.DRV_LRF_WLK,
      ActivitySimPathType.WLK_LRF_WLK,
      ActivitySimPathType.WLK_HVY_WLK,
      ActivitySimPathType.DRV_LOC_WLK
    )
    skims.size should be > 130
    pathTypeToSkimsCount(ActivitySimPathType.WLK_LOC_WLK) should be > 60
    pathTypeToSkimsCount(ActivitySimPathType.DRV_HVY_WLK) should be < 15
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

    val skims: collection.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = finalSkimmer.currentSkim

    val pathTypeToSkimsCount = skims.keys
      .map(_.asInstanceOf[ActivitySimSkimmerKey])
      .groupBy(_.pathType)
      .mapValues(_.size)

    println(pathTypeToSkimsCount)

    pathTypeToSkimsCount.keySet should contain only (
      ActivitySimPathType.DRV_HVY_WLK,
      ActivitySimPathType.WLK_LOC_WLK,
      ActivitySimPathType.DRV_LRF_WLK,
      ActivitySimPathType.WLK_LRF_WLK,
      ActivitySimPathType.WLK_HVY_WLK,
      ActivitySimPathType.DRV_LOC_WLK,
      ActivitySimPathType.SOV,
      ActivitySimPathType.WALK
    )
    skims.size should be > 280
    pathTypeToSkimsCount(ActivitySimPathType.WLK_LOC_WLK) should be > 60
    pathTypeToSkimsCount(ActivitySimPathType.DRV_HVY_WLK) should be < 15

    pathTypeToSkimsCount(ActivitySimPathType.SOV) shouldBe 144
    pathTypeToSkimsCount(ActivitySimPathType.WALK) shouldBe 22 // because max walk trip length is 1000 meters

  }
}
