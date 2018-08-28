package beam.agentsim.agents.ridehail.graph
import java.util
import java.util.concurrent.CopyOnWriteArrayList

import beam.agentsim.agents.ridehail.graph.RealizedModeStatsGraphSpec.RealizedModeStatsGraph
import beam.agentsim.events.{ModeChoiceEvent, ReplanningEvent}
import beam.analysis.plots.RealizedModeStats
import beam.integration.IntegrationSpecCommon
import com.google.inject.Provides
import org.matsim.api.core.v01.events.Event
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.utils.collections.Tuple
import org.scalatest.{Matchers, WordSpecLike}

import scala.concurrent.Promise

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global

object RealizedModeStatsGraphSpec {

  class RealizedModeStatsGraph(
    computation: RealizedModeStats.RealizedModesStatsComputation with EventAnalyzer
  ) extends BasicEventHandler
      with IterationEndsListener {

    private val realizedModeStats =
      new RealizedModeStats(computation)

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
      computation.eventFile(event.getIteration)
    }
  }

  class StatsValidationHandler extends BasicEventHandler {
    private val counter = new CopyOnWriteArrayList[String]()
    private var personsId = Set[String]()

    override def handleEvent(event: Event): Unit = event match {
      case evn if evn.getEventType.equalsIgnoreCase(ModeChoiceEvent.EVENT_TYPE) =>
        updateModeChoice(evn)
      case evn: ModeChoiceEvent                                                 => updateModeChoice(evn)
      case evn if evn.getEventType.equalsIgnoreCase(ReplanningEvent.EVENT_TYPE) =>
      case evn: ReplanningEvent                                                 =>
      case _                                                                    =>
    }

    private def updateCounter(event: Event) = {
      val mode = event.getAttributes.get(ModeChoiceEvent.ATTRIBUTE_MODE)
      counter.add(mode)
    }

    private def updateModeChoice(evn: Event): Unit = {
      val mode = evn.getAttributes.get(ModeChoiceEvent.ATTRIBUTE_MODE)
      val personId = evn.getAttributes.get(ModeChoiceEvent.ATTRIBUTE_PERSON_ID)
      if (personsId.contains(personId)) {
        personsId = personsId - personId
      } else {}
    }

    def counterValue: Seq[String] = counter.asScala
  }

}

class RealizedModeStatsGraphSpec extends WordSpecLike with Matchers with IntegrationSpecCommon {
  "Realized Mode Graph Collected Data" must {

    "contains valid realized mode stats" in {
      val computation = new RealizedModeStats.RealizedModesStatsComputation with EventAnalyzer {
        private val promise = Promise[java.util.Map[Integer, java.util.Map[String, Integer]]]()

        override def compute(
          stat: Tuple[util.Map[
            Integer,
            util.Map[String, Integer]
          ], util.Set[String]]
        ): Array[Array[Double]] = {
          promise.success(stat.getFirst)
          super.compute(stat)
        }

        override def eventFile(iteration: Int): Unit = {}
      }

      GraphRunHelper(
        new AbstractModule() {
          override def install(): Unit = {
            addControlerListenerBinding().to(classOf[RealizedModeStatsGraph])
          }

          @Provides def provideGraph(
            eventsManager: EventsManager
          ): RealizedModeStatsGraph = {
            val graph = new RealizedModeStatsGraph(computation)
            eventsManager.addHandler(graph)
            graph
          }
        },
        baseConfig
      ).run()
    }
  }
}
