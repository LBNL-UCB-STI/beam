package beam.agentsim.agents.ridehail.graph

import java.util
import java.util.concurrent.CopyOnWriteArrayList

import beam.agentsim.agents.ridehail.graph.ModeChosenStatsGraphSpec.{ModeChosenStatsGraph, StatsValidationHandler}
import beam.agentsim.events.ModeChoiceEvent
import beam.analysis.plots.{GraphsStatsAgentSimEventsListener, ModeChosenAnalysis}
import beam.integration.IntegrationSpecCommon
import beam.sim.config.BeamConfig
import com.google.inject.Provides
import org.matsim.api.core.v01.events.Event
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.matsim.core.utils.collections.Tuple
import org.scalatest.{Matchers, WordSpecLike}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise

object ModeChosenStatsGraphSpec {

  class ModeChosenStatsGraph(
    compute: ModeChosenAnalysis.ModeChosenComputation with EventAnalyzer,
    beamConfig: BeamConfig
  ) extends BasicEventHandler
      with IterationEndsListener {

    private lazy val modeChosenStats =
      new ModeChosenAnalysis(compute, beamConfig)

    override def reset(iteration: Int): Unit = {
      modeChosenStats.resetStats()
    }

    override def handleEvent(event: Event): Unit = {
      event match {
        case evn if evn.getEventType.equalsIgnoreCase(ModeChoiceEvent.EVENT_TYPE) =>
          modeChosenStats.processStats(evn)
        case evn: ModeChoiceEvent =>
          modeChosenStats.processStats(evn)
        case _ =>
      }
      Unit
    }

    override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
      modeChosenStats.createGraph(event)
      compute.eventFile(event.getIteration)
    }
  }

  class StatsValidationHandler extends BasicEventHandler {
    lazy val counter = new CopyOnWriteArrayList[String]()

    override def handleEvent(event: Event): Unit = {
      event match {
        case evn if evn.getEventType.equalsIgnoreCase(ModeChoiceEvent.EVENT_TYPE) =>
          updateCounter(event)
        case _: ModeChoiceEvent =>
          updateCounter(event)
        case _ =>
      }
      Unit
    }

    private def updateCounter(event: Event) = {
      val mode = event.getAttributes.get(ModeChoiceEvent.ATTRIBUTE_MODE)
      counter.add(mode)
    }

    def counterValue: Seq[String] = counter.asScala
  }
}

class ModeChosenStatsGraphSpec extends WordSpecLike with Matchers with IntegrationSpecCommon {

  "Mode Chosen Graph Collected Data" must {

    "contains valid mode chosen stats" in {
      val waitingStat =
        new ModeChosenAnalysis.ModeChosenComputation with EventAnalyzer {
          private val promise = Promise[java.util.Map[Integer, java.util.Map[String, Integer]]]()

          override def compute(
            stat: Tuple[java.util.Map[Integer, java.util.Map[String, Integer]], util.Set[String]]
          ): Array[Array[Double]] = {
            promise.trySuccess(stat.getFirst)
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
            addControlerListenerBinding().to(classOf[ModeChosenStatsGraph])
          }

          @Provides def provideGraph(
            eventsManager: EventsManager
          ): ModeChosenStatsGraph = {
            val graph = new ModeChosenStatsGraph(waitingStat, BeamConfig(baseConfig))
            eventsManager.addHandler(graph)
            graph
          }
        },
        baseConfig
      ).run()
    }
  }

}
