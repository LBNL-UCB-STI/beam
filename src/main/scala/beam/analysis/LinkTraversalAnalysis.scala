package beam.analysis

import beam.agentsim.events.PathTraversalEvent
import beam.analysis.plots.{ExtendedBoxAndWhiskerRenderer, GraphAnalysis, GraphUtils, GraphsStatsAgentSimEventsListener}
import beam.sim.common.GeoUtils
import beam.utils.logging.ExponentialLazyLogging
import org.jfree.chart.JFreeChart
import org.jfree.chart.axis.{CategoryAxis, NumberAxis}
import org.jfree.chart.plot.CategoryPlot
import org.jfree.data.statistics.DefaultBoxAndWhiskerCategoryDataset
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Network
import org.matsim.core.controler.events.IterationEndsEvent

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.collection.JavaConverters._

class LinkTraversalAnalysis(geoUtils: GeoUtils, network: Network) extends GraphAnalysis with ExponentialLazyLogging {

  private val xAxisLabel = "Mode"
  private val yAxisLabel = "PathTraversal-To-Euclidean"
  private val linkTraversalFileBaseName = "linkTraversalStats"
  private val modeLinksRatio = mutable.Map[String, ListBuffer[Double]]()
  private val networkLinks = network.getLinks
  override def processStats(event: Event): Unit = {
    event match {
      case pathTraversalEvent: PathTraversalEvent =>
        val mode = pathTraversalEvent.mode.value
        val length = pathTraversalEvent.legLength
        val links = pathTraversalEvent.linkIds
        if (links.nonEmpty) {
          val firstNode = links.head
          val lastNode = links.last
          val firstLinkToNode = networkLinks.get(Id.createLinkId(firstNode)).getToNode
          val lastLinkToNode = networkLinks.get(Id.createLinkId(lastNode)).getToNode
          val euclideanDistance = geoUtils.distLatLon2Meters(firstLinkToNode.getCoord, lastLinkToNode.getCoord)
          if (euclideanDistance != 0.0) {
            val lengthPerEuclideanList = modeLinksRatio.getOrElse(mode, new ListBuffer[Double]())
            lengthPerEuclideanList += length / euclideanDistance
            modeLinksRatio.put(mode, lengthPerEuclideanList)
          }
        }
      case _ =>
    }
  }

  override def createGraph(event: IterationEndsEvent): Unit = {
    val outputDirectoryHierarchy = event.getServices.getControlerIO
    val fileName = outputDirectoryHierarchy.getIterationFilename(event.getIteration, linkTraversalFileBaseName + ".png")

    val xAxis = new CategoryAxis(xAxisLabel)
    val yAxis = new NumberAxis(yAxisLabel)
    yAxis.setAutoRangeIncludesZero(false)
    val renderer = new ExtendedBoxAndWhiskerRenderer
    renderer.setFillBox(true)
    renderer.setBaseItemLabelsVisible(true)
    val boxAndWhiskerCategoryDataset: DefaultBoxAndWhiskerCategoryDataset = new DefaultBoxAndWhiskerCategoryDataset()
    modeLinksRatio.foreach {
      case (mode, ratio) =>
        boxAndWhiskerCategoryDataset.add(ratio.asJava, mode, "")
    }

    val plot: CategoryPlot =
      new CustomCategoryPlot(modeLinksRatio.keys.toList.asJava, boxAndWhiskerCategoryDataset, xAxis, yAxis, renderer)
    val chart = new JFreeChart("Path Traversal To Euclidean Ratio", JFreeChart.DEFAULT_TITLE_FONT, plot, true)
    GraphUtils.saveJFreeChartAsPNG(
      chart,
      fileName,
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )
  }

  override def resetStats(): Unit = {
    modeLinksRatio.clear()
  }
}

class CustomCategoryPlot(
  result: java.util.List[String],
  boxAndWhiskerCategoryDataset: DefaultBoxAndWhiskerCategoryDataset,
  xAxis: CategoryAxis,
  yAxis: NumberAxis,
  renderer: ExtendedBoxAndWhiskerRenderer
) extends CategoryPlot(boxAndWhiskerCategoryDataset, xAxis, yAxis, renderer) {

  override def getCategoriesForAxis(axis: CategoryAxis): java.util.List[_] = {
    result
  }
}
