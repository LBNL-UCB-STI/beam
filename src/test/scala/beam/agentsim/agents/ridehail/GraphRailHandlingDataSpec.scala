package beam.agentsim.agents.ridehail

import java.{lang, util}
import java.util.{List, Map}

import beam.agentsim.agents.ridehail.GraphRailHandlingDataSpec.{
  RideHailingWaitingGraph,
  RideHailingWaitingSingleGraph
}
import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent, ReplanningEvent}
import beam.analysis.plots.modality.RideHailDistanceRowModel
import beam.analysis.plots.{ModeChosenStats, RealizedModeStats, _}
import beam.integration.IntegrationSpecCommon
import beam.router.r5.NetworkCoordinator
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import com.google.inject.Provides
import org.matsim.api.core.v01.events.{
  Event,
  PersonArrivalEvent,
  PersonDepartureEvent,
  PersonEntersVehicleEvent
}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.core.utils.collections.Tuple
import org.scalatest.{Matchers, WordSpecLike}

import scala.collection.JavaConverters._

object GraphRailHandlingDataSpec {

  class RideHailingWaitingSingleGraph(
    beamServices: BeamServices,
    waitingComp: RideHailingWaitingSingleStats.RideHailingWaitingSingleComputation
  ) extends BasicEventHandler
      with IterationEndsListener {

    private val railHailingStat =
      new RideHailingWaitingSingleStats(beamServices, waitingComp)

    override def reset(iteration: Int): Unit = {
      railHailingStat.resetStats()
    }

    override def handleEvent(event: Event): Unit = {
      event match {
        case evn
            if evn.getEventType.equalsIgnoreCase(ModeChoiceEvent.EVENT_TYPE)
            || event.getEventType.equalsIgnoreCase(PersonEntersVehicleEvent.EVENT_TYPE) =>
          railHailingStat.processStats(event)
        case evn @ (_: ModeChoiceEvent | _: PersonEntersVehicleEvent) =>
          railHailingStat.processStats(evn)
        case _ =>
      }
      Unit
    }

    override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
      railHailingStat.createGraph(event)
    }
  }

  class RideHailingWaitingGraph(waitingComp: RideHailWaitingStats.WaitingStatsComputation)
      extends BasicEventHandler
      with IterationEndsListener {

    private val railHailingStat =
      new RideHailWaitingStats(waitingComp)

    override def reset(iteration: Int): Unit = {
      railHailingStat.resetStats()
    }

    override def handleEvent(event: Event): Unit = {
      event match {
        case evn
            if evn.getEventType.equalsIgnoreCase(ModeChoiceEvent.EVENT_TYPE)
            || event.getEventType.equalsIgnoreCase(PersonEntersVehicleEvent.EVENT_TYPE) =>
          railHailingStat.processStats(event)
        case evn @ (_: ModeChoiceEvent | _: PersonEntersVehicleEvent) =>
          railHailingStat.processStats(evn)
        case _ =>
      }
      Unit
    }

    override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
      railHailingStat.createGraph(event)
    }
  }

  class RealizedModeStatsGraph(waitingComp: RealizedModeStats.RealizedModesStatsComputation)
      extends BasicEventHandler
      with IterationEndsListener {

    private val realizedModeStats =
      new RealizedModeStats(waitingComp)

    override def reset(iteration: Int): Unit = {
      realizedModeStats.resetStats()
    }

    override def handleEvent(event: Event): Unit = {
      event match {
        case evn if evn.getEventType.equalsIgnoreCase(ModeChoiceEvent.EVENT_TYPE) =>
          realizedModeStats.processStats(event)
        case evn: ModeChoiceEvent =>
          realizedModeStats.processStats(evn)
        case evn if evn.getEventType.equalsIgnoreCase(ReplanningEvent.EVENT_TYPE) =>
          realizedModeStats.processStats(event)
        case evn: ReplanningEvent =>
          realizedModeStats.processStats(evn)
        case _ =>
      }
      Unit
    }

    override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
      realizedModeStats.createGraph(event)
    }
  }

  class PersonTravelTimeStatsGraph(comp: PersonTravelTimeStats.PersonTravelTimeComputation)
      extends BasicEventHandler
      with IterationEndsListener {

    private val personTravelTimeStats =
      new PersonTravelTimeStats(comp)

    override def reset(iteration: Int): Unit = {
      personTravelTimeStats.resetStats()
    }

    override def handleEvent(event: Event): Unit = {
      event match {
        case evn if evn.getEventType.equalsIgnoreCase(PersonDepartureEvent.EVENT_TYPE) =>
          personTravelTimeStats.processStats(event)
        case evn if evn.getEventType.equalsIgnoreCase(PersonArrivalEvent.EVENT_TYPE) =>
          personTravelTimeStats.processStats(event)
        case evn: PersonArrivalEvent =>
          personTravelTimeStats.processStats(evn)
        case evn: PersonDepartureEvent =>
          personTravelTimeStats.processStats(evn)
        case _ =>
      }
      Unit
    }

    override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
      personTravelTimeStats.createGraph(event)
    }
  }

  class ModeChosenStatsGraph(compute: ModeChosenStats.ModeChosenComputation)
      extends BasicEventHandler
      with IterationEndsListener {

    private val modeChosenStats =
      new ModeChosenStats(compute)

    override def reset(iteration: Int): Unit = {
      modeChosenStats.resetStats()
    }

    override def handleEvent(event: Event): Unit = {
      event match {
        case evn if evn.getEventType.equalsIgnoreCase(ModeChoiceEvent.EVENT_TYPE) =>
          modeChosenStats.processStats(event)
        case evn: ModeChoiceEvent =>
          modeChosenStats.processStats(evn)
        case _ =>
      }
      Unit
    }

    override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
      modeChosenStats.createGraph(event)
    }
  }

  class FuelUsageStatsGraph(compute: FuelUsageStats.FuelUsageStatsComputation)
      extends BasicEventHandler
      with IterationEndsListener {

    private val fuelUsageStats =
      new FuelUsageStats(compute)

    override def reset(iteration: Int): Unit = {
      fuelUsageStats.resetStats()
    }

    override def handleEvent(event: Event): Unit = {
      event match {
        case evn if evn.getEventType.equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE) =>
          fuelUsageStats.processStats(event)
        case evn: PathTraversalEvent =>
          fuelUsageStats.processStats(evn)
        case _ =>
      }
      Unit
    }

    override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
      fuelUsageStats.createGraph(event)
    }
  }
}

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
