package beam.analysis.plots

import java.{lang, util}

import beam.agentsim.agents.GenericEventsSpec
import beam.analysis.IterationSummaryAnalysis
import org.matsim.api.core.v01.events.Event
import org.matsim.core.events.handler.BasicEventHandler
import org.scalatest.Matchers

class GenericAnalysisSpec extends GenericEventsSpec with Matchers {

  var summaryStats: util.Map[lang.String, lang.Double] = _

  protected def runAnalysis(analysis: IterationSummaryAnalysis): Unit = {
    processHandlers(List(new BasicEventHandler {
      override def handleEvent(event: Event): Unit = {
        analysis.processStats(event)
      }
    }))

    summaryStats = analysis.getSummaryStats
  }
}
