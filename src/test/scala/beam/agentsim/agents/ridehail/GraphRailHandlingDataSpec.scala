package beam.agentsim.agents.ridehail

import java.{lang, util}

import beam.agentsim.agents.ridehail.graph.RideHailingWaitingGraphSpec.RideHailingWaitingGraph
import beam.agentsim.agents.ridehail.graph.RideHailingWaitingSingleStatsSpec.RideHailingWaitingSingleGraph
import beam.analysis.plots._
import beam.integration.IntegrationSpecCommon
import beam.router.r5.NetworkCoordinator
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import com.google.inject.Provides
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.AbstractModule
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.core.utils.collections.Tuple
import org.scalatest.{Matchers, WordSpecLike}

import scala.collection.JavaConverters._

object GraphRailHandlingDataSpec {}

class GraphRailHandlingDataSpec
    extends WordSpecLike
    with Matchers
    with BeamHelper
    with IntegrationSpecCommon {

  "Graph Collected Data" must {

    def initialSetup(childModule: AbstractModule): Unit = {
      val beamConfig = BeamConfig(baseConfig)
      val configBuilder = new MatSimBeamConfigBuilder(baseConfig)
      val matsimConfig = configBuilder.buildMatSamConf()

      matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
      FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

      val networkCoordinator = new NetworkCoordinator(beamConfig)
      networkCoordinator.loadNetwork()

      val scenario =
        ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
      scenario.setNetwork(networkCoordinator.network)

      val injector = org.matsim.core.controler.Injector.createInjector(
        scenario.getConfig,
        module(baseConfig, scenario, networkCoordinator.transportNetwork),
        childModule
      )

      val beamServices: BeamServices =
        injector.getInstance(classOf[BeamServices])

      beamServices.controler.run()
    }

    "contains non empty waiting single stats" in {

      val waitingStat =
        new RideHailingWaitingSingleStats.RideHailingWaitingSingleComputation {
          override def compute(stat: util.Map[Integer, lang.Double]): Array[Array[Double]] = {
            val data = super.compute(stat)
            data.length should be > 0
            data.foldRight(0) { case (arr, acc) => acc + arr.length } should be > 0
            data
          }
        }

      initialSetup(new AbstractModule() {
        override def install(): Unit = {
          addControlerListenerBinding().to(classOf[RideHailingWaitingSingleGraph])
        }

        @Provides def provideGraph(
          beamServices: BeamServices,
          eventsManager: EventsManager
        ): RideHailingWaitingSingleGraph = {
          val graph =
            new RideHailingWaitingSingleGraph(beamServices, waitingStat)
          eventsManager.addHandler(graph)
          graph
        }
      })
    }

    "contains non empty waiting stats" in {

      val waitingStat =
        new RideHailWaitingStats.WaitingStatsComputation {
          override def compute(
            stat: Tuple[util.List[java.lang.Double], util.Map[Integer, util.List[java.lang.Double]]]
          ): Tuple[util.Map[Integer, util.Map[java.lang.Double, Integer]], Array[Array[Double]]] = {
            val data = super.compute(stat)
            val asScala = data.getFirst.asScala
            asScala.isEmpty shouldBe false
            asScala.foldRight(0) { case (arr, acc) => acc + arr._2.size } should be > 0
            data
          }
        }

      initialSetup(new AbstractModule() {
        override def install(): Unit = {
          addControlerListenerBinding().to(classOf[RideHailingWaitingGraph])
        }

        @Provides def provideGraph(eventsManager: EventsManager): RideHailingWaitingGraph = {
          val graph =
            new RideHailingWaitingGraph(waitingStat)
          eventsManager.addHandler(graph)
          graph
        }
      })
    }
  }
}
