package beam.agentsim.agents.ridehail.graph
import java.util
import beam.agentsim.agents.ridehail.graph.RealizedModeStatsGraphSpec.{RealizedModeStatsGraph, StatsValidationHandler}
import beam.agentsim.events.{ModeChoiceEvent, ReplanningEvent}
import beam.analysis.plots.RealizedModeAnalysis
import beam.integration.IntegrationSpecCommon
import beam.sim.config.BeamConfig
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
    computation: RealizedModeAnalysis.RealizedModesStatsComputation with EventAnalyzer,
    beamConfig: BeamConfig
  ) extends BasicEventHandler
      with IterationEndsListener {

    private lazy val realizedModeStats =
      new RealizedModeAnalysis(computation, true, beamConfig)

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
    private var personsId = Map[String, Int]()
    private var counter = Map[String, Double]()
    private var personModeCounter = Map[String, Set[String]]()
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
      if (personsId.contains(personId) && personsId(personId).equals(1)) {
        personsId += personId -> 0
        val modes = personModeCounter(personId) + mode
        personModeCounter = personModeCounter + (personId -> modes)
      } else {
        val modes = personModeCounter.getOrElse(personId, Set[String]())
        modes.foreach(mode => counter += mode -> (counter.getOrElse(mode, 0.0) + 1.0 / modes.size))
        personsId = personsId - personId
        personModeCounter = personModeCounter - personId
        personModeCounter += personId -> Set(mode)
      }
    }

    private def updateReplanning(evn: Event): Unit = {
      personsId += evn.getAttributes.get(ReplanningEvent.ATTRIBUTE_PERSON) -> 1
    }

    def counterValue: Map[String, Double] = {
      personModeCounter.values.foreach { modes =>
        modes.foreach(mode => counter += mode -> (counter.getOrElse(mode, 0.0) + 1.0 / modes.size))
      }
      counter
    }
  }

}

class RealizedModeStatsGraphSpec extends WordSpecLike with Matchers with IntegrationSpecCommon {
  "Realized Mode Graph Collected Data" must {

    "contains valid realized mode stats" ignore {
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
            val all = a.asScala.values
              .flatMap(_.asScala)
              .groupBy(_._1)
              .map { case (s, is) => s -> is.map(_._2).reduce((a, b) => a + b) }

            Map(modes.toSeq.sortBy(_._1): _*) shouldEqual Map(all.toSeq.sortBy(_._1): _*)
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
            val graph = new RealizedModeStatsGraph(computation, BeamConfig(baseConfig))
            eventsManager.addHandler(graph)
            graph
          }
        },
        baseConfig
      ).run()
    }
  }
}
