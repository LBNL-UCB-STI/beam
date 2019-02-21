package beam.agentsim.agents.ridehail.graph
import java.util
import java.util.concurrent.CopyOnWriteArrayList

import beam.agentsim.agents.ridehail.graph.RealizedModeStatsGraphSpec.{RealizedModeStatsGraph, StatsValidationHandler}
import beam.agentsim.events.{ModeChoiceEvent, ReplanningEvent}
import beam.analysis.plots.{GraphsStatsAgentSimEventsListener, RealizedModeAnalysis}
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

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise

object RealizedModeStatsGraphSpec {

  class RealizedModeStatsGraph(
    computation: RealizedModeAnalysis.RealizedModesStatsComputation with EventAnalyzer
  ) extends BasicEventHandler
      with IterationEndsListener {

    private lazy val realizedModeStats =
      new RealizedModeAnalysis(computation, true)

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
    private lazy val counter = new CopyOnWriteArrayList[String]()
    private var personsId = Set[String]()
    private var lastPersonMode = Map[String, String]()

    override def handleEvent(event: Event): Unit = event match {
      case evn if evn.getEventType.equalsIgnoreCase(ModeChoiceEvent.EVENT_TYPE) =>
        updateModeChoice(evn)
      case evn: ModeChoiceEvent => updateModeChoice(evn)
      case evn if evn.getEventType.equalsIgnoreCase(ReplanningEvent.EVENT_TYPE) =>
        updateReplanning(evn)
      case evn: ReplanningEvent => updateReplanning(evn)
      case _                    =>
    }

    private def updateModeChoice(evn: Event): Unit = {
      val mode = evn.getAttributes.get(ModeChoiceEvent.ATTRIBUTE_MODE)
      val personId = evn.getAttributes.get(ModeChoiceEvent.ATTRIBUTE_PERSON_ID)
      if (personsId.contains(personId)) {
        personsId = personsId - personId
      } else {
        counter.add(mode)
        lastPersonMode = lastPersonMode + (personId -> mode)
      }
    }

    private def updateReplanning(evn: Event): Unit = {
      val personId = evn.getAttributes.get(ReplanningEvent.ATTRIBUTE_PERSON)
      personsId = personsId + personId
      val mode = lastPersonMode.get(personId)
      mode.foreach { m =>
        lastPersonMode = lastPersonMode - personId
        counter.add("others")
        counter.remove(m)
      }
    }

    def counterValue: Seq[String] = counter.asScala
  }

}

class RealizedModeStatsGraphSpec extends WordSpecLike with Matchers with IntegrationSpecCommon {
  "Realized Mode Graph Collected Data" must {

    "contains valid realized mode stats" in {
      val computation = new RealizedModeAnalysis.RealizedModesStatsComputation with EventAnalyzer {
        private val promise = Promise[java.util.Map[Integer, java.util.Map[String, java.lang.Double]]]()

        override def compute(
          stat: Tuple[util.Map[
            Integer,
            util.Map[String, java.lang.Double]
          ], util.Set[String]]
        ): Array[Array[Double]] = {
          //this check handle to exit from current function recursion
          if (!promise.isCompleted)
            promise.success(stat.getFirst)
          super.compute(stat)

        }

        override def eventFile(iteration: Int): Unit = {
          val handler = new StatsValidationHandler
          parseEventFile(iteration, handler)
          promise.future.foreach { a =>
            val modes = handler.counterValue
              .groupBy(identity)
              .map { case (mode, ms) => mode -> ms.size }

            val all = a.asScala.values
              .flatMap(_.asScala)
              .groupBy(_._1)
              .map { case (s, is) => s -> is.map(_._2.toInt).sum }

            modes shouldEqual all
          }
        }
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
