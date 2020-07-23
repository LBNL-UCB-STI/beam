package beam.analysis

import java.util

import beam.agentsim.events.PathTraversalEvent
import beam.analysis.plots.{GraphUtils, GraphsStatsAgentSimEventsListener}
import beam.router.Modes.BeamMode.CAR
import beam.utils.NetworkHelper
import beam.utils.logging.ExponentialLazyLogging
import com.google.inject.Inject
import org.jfree.chart.plot.CategoryPlot
import org.jfree.data.category.DefaultCategoryDataset
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Link
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.events.handler.BasicEventHandler

import scala.collection.JavaConverters._

case class DelayInLength(delay: Double, length: Int)

class DelayMetricAnalysis @Inject()(
  eventsManager: EventsManager,
  controlerIO: OutputDirectoryHierarchy,
  networkHelper: NetworkHelper
) extends BasicEventHandler
    with ExponentialLazyLogging {

  eventsManager.addHandler(this)

  private val cumulativeDelay: Array[Double] = Array.ofDim[Double](networkHelper.maxLinkId + 1)

  private val cumulativeLength: Array[Double] = Array.ofDim[Double](networkHelper.maxLinkId + 1)

  private var linkTravelsCount: Array[Int] = Array.ofDim[Int](networkHelper.maxLinkId + 1)

  private var linkAverageDelay: Array[DelayInLength] = Array.ofDim[DelayInLength](networkHelper.maxLinkId + 1)

  private val bins = Array(0, 500, 1000, 2000, 3000)
  private val legends = Array("0-500", "500-1000", "1000-2000", "2000-3000", "3000+")
  private val capacitiesDelay = scala.collection.mutable.Map[Int, Double]()
  private val delayAveragePerKMDataset = new DefaultCategoryDataset
  private val delayTotalByLinkCapacityDataset = new DefaultCategoryDataset
  private val fileName = "delayTotalByLinkCapacity"
  private val xAxisName = "Iteration (s)"
  private val yAxisName = "Total Delay (hour)"
  private val graphTitle = "Total Delay by Binned Link Capacity"
  private val yAxisAverageGraphName = "Average Delay Intensity (sec/km)"
  private val averageGraphTitle = "Average Delay per Kilometer"
  private val networkUtilizedGraphTitle = "Physsim Network Utilization"
  private val xAxisName_NetworkUtilized = "hour"
  private val yAxisName_NetworkUtilized = "Network Percent Used"
  private val linkUtilization = scala.collection.mutable.SortedMap[Int, Set[Int]]()

  var totalTravelTime = 0.0

  /**
    * Handles the PathTraversalEvent notification and generates the metric delay analysis data
    *
    * @param event Event
    */
  override def handleEvent(event: Event): Unit = {
    event match {
      case pathTraversalEvent: PathTraversalEvent =>
        calculateNetworkUtilization(pathTraversalEvent)

        val mode = pathTraversalEvent.mode
        if (mode.value.equalsIgnoreCase(CAR.value)) {
          val linkIds = pathTraversalEvent.linkIds
          val linkTravelTimes = pathTraversalEvent.linkTravelTime
          assert(linkIds.length == linkTravelTimes.length)

          if (linkIds.nonEmpty) {
            var index = 0
            while (index < linkIds.length) {
              val linkId = linkIds(index)
              val link = networkHelper.getLinkUnsafe(linkId)
              process(linkId, link, linkTravelTimes(index))
              index += 1
            }
          }
        }
      case _ =>
    }
  }

  def process(index: Int, link: Link, travelTime: Double): Unit = {
    val freeLength = link.getLength
    val freeSpeed = link.getFreespeed
    var freeFlowDelay = travelTime - (freeLength / freeSpeed)
    if (freeFlowDelay >= 0) {
      val existingFreeFlowDelay = cumulativeDelay(index)
      val existingLinkLength = cumulativeLength(index)

      val delay = freeFlowDelay + existingFreeFlowDelay
      cumulativeDelay(index) = delay

      val len = freeLength + existingLinkLength
      cumulativeLength(index) = len

      totalTravelTime += travelTime

      val travelsCount = linkTravelsCount(index) + 1
      linkTravelsCount(index) = travelsCount

      //calculate average of link delay for further calculating weighted average
      linkAverageDelay(index) = DelayInLength((travelsCount * delay) / len, travelsCount)

    } else if (freeFlowDelay >= -1) {
      freeFlowDelay = 0
    } else {
      logger.warn(" The delay is negative and the delay = " + freeFlowDelay)
    }
  }

  override def reset(iteration: Int): Unit = {
    util.Arrays.fill(cumulativeDelay, 0.0)
    util.Arrays.fill(cumulativeLength, 0.0)
    util.Arrays.fill(linkTravelsCount, 0)
    linkAverageDelay = Array.ofDim[DelayInLength](networkHelper.maxLinkId + 1)
    capacitiesDelay.clear
    linkUtilization.clear()
    totalTravelTime = 0
  }

  def calculateNetworkUtilization(pathTraversalEvent: PathTraversalEvent): Unit = {

    val time = pathTraversalEvent.time / 3600
    val utilizedLinks = pathTraversalEvent.linkIds.toSet
    linkUtilization += time.toInt -> (linkUtilization.getOrElse(time.toInt, Set[Int]()) ++ utilizedLinks)
  }

  def categoryDelayCapacityDataset(iteration: Int): Unit = {
    cumulativeDelay.zipWithIndex.foreach {
      case (delay, index) =>
        val link = networkHelper.getLinkUnsafe(index)
        val capacity = link.getCapacity
        val bin = largeset(capacity)
        val capacityDelay = capacitiesDelay.getOrElse(bin, 0.0)
        capacitiesDelay(bin) = delay + capacityDelay
    }

    for (index <- bins.indices) {
      val bin = bins(index)
      val capacityBin: Double = capacitiesDelay.getOrElse(bin, 0)
      delayTotalByLinkCapacityDataset.addValue(capacityBin / 3600, legends(index), iteration)
    }
  }

  // getting the bin for capacity
  def largeset(capacity: Double): Int = {
    bins.reverse.foreach { bin =>
      if (capacity >= bin) return bin
    }
    0
  }

  // calculating weighted average
  def averageDelayDataset(event: IterationEndsEvent) {
    val iteration = event.getIteration
    val nonNull: IndexedSeq[DelayInLength] = linkAverageDelay.filter(x => x != null)
    val sumDelay: Double = nonNull.view.map(delayInLength => delayInLength.delay).sum
    val sumLength: Double = nonNull.view.map(delayInLength => delayInLength.length).sum
    val avg = sumDelay / sumLength
    delayAveragePerKMDataset.addValue(avg, 0, iteration)
  }

  def generateDelayAnalysis(event: IterationEndsEvent): Unit = {
    categoryDelayCapacityDataset(event.getIteration)
    if (delayTotalByLinkCapacityDataset != null) {
      createDelayCapacityGraph(fileName)
    }
    averageDelayDataset(event)
    createDelayAveragePerKilometerGraph()
    createNetworkUtilizationGraph(event.getIteration)
  }

  def createDelayCapacityGraph(fileName: String): Unit = {
    val chart = GraphUtils.createStackedBarChartWithDefaultSettings(
      delayTotalByLinkCapacityDataset,
      graphTitle,
      xAxisName,
      yAxisName,
      true
    )

    val plot: CategoryPlot = chart.getCategoryPlot
    GraphUtils.plotLegendItems(plot, legends.toList.asJava, delayTotalByLinkCapacityDataset.getRowCount)

    val graphImageFile = controlerIO.getOutputFilename(fileName + ".png")
    GraphUtils.saveJFreeChartAsPNG(
      chart,
      graphImageFile,
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )
  }

  def createDelayAveragePerKilometerGraph(): Unit = {
    val fileName = controlerIO.getOutputFilename("delayAveragePerKilometer.png")
    val chart = GraphUtils.createStackedBarChartWithDefaultSettings(
      delayAveragePerKMDataset,
      averageGraphTitle,
      xAxisName,
      yAxisAverageGraphName,
      false
    )
    GraphUtils.saveJFreeChartAsPNG(
      chart,
      fileName,
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )
  }

  def createNetworkUtilizationGraph(iterationNumber: Int): Unit = {
    val dataset = new DefaultCategoryDataset
    val totalLink = networkHelper.allLinks.length
    for (hour <- 0 to linkUtilization.keysIterator.max) {
      dataset.addValue((linkUtilization.getOrElse(hour, Set()).size * 100).toDouble / totalLink, 0, hour)
    }

    val graphImageFile: String = controlerIO.getIterationFilename(iterationNumber, "physsimNetworkUtilization.png")

    val chart = GraphUtils.createStackedBarChartWithDefaultSettings(
      dataset,
      networkUtilizedGraphTitle,
      xAxisName_NetworkUtilized,
      yAxisName_NetworkUtilized,
      false
    )

    GraphUtils.saveJFreeChartAsPNG(
      chart,
      graphImageFile,
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )
  }

  def getTotalDelay: Double = cumulativeDelay.sum

}
