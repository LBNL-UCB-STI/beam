package beam.analysis

import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.api.core.v01.events.Event
import beam.agentsim.events.PathTraversalEvent
import beam.analysis.plots.{GraphUtils, GraphsStatsAgentSimEventsListener}
import beam.router.Modes.BeamMode.CAR
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import com.google.inject.Inject
import org.jfree.chart.plot.CategoryPlot
import org.jfree.data.category.DefaultCategoryDataset
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.controler.events.IterationEndsEvent

import scala.collection.mutable.Map
import collection.JavaConverters._

case class DelayInLength(delay: Double, length: Int)

class DelayMetricAnalysis @Inject()(
  eventsManager: EventsManager,
  controlerIO: OutputDirectoryHierarchy,
  services: BeamServices,
  scenario: Scenario,
  beamConfig: BeamConfig
) extends BasicEventHandler
    with LazyLogging {

  eventsManager.addHandler(this)
  private val networkLinks = scenario.getNetwork.getLinks
  private val cumulativeDelay: Map[String, Double] = Map()
  private val cumulativeLength: Map[String, Double] = Map()
  private var linkTravelsCount: Map[String, Int] = Map()
  private var linkAverageDelay: Map[String, DelayInLength] = Map()

  private val bins = Array(0, 500, 1000, 2000, 3000)
  private val legends = Array("0-500", "500-1000", "1000-2000", "2000-3000", "3000+")
  private val capacitiesDelay = scala.collection.mutable.Map[Int, Double]()
  private val delayAveragePerKMDataset = new DefaultCategoryDataset
  private val delayTotalByLinkCapacityDataset = new DefaultCategoryDataset
  private val fileName = "delayTotalByLinkCapacity"
  private val xAxisName = "capacity bins"
  private val yAxisName = "vehicle delay (hour)"
  private val graphTitle = "Delay Metric Analysis"
  private val xAxisAverageGraphName = "Iteration(s)"
  private val yAxisAverageGraphName = " Average Delay Intensity (sec/km)"
  private val averageGraphTitle = "Delay Average per kilometer Analysis"
  var totalTravelTime = 0.0

  /**
    * Handles the PathTraversalEvent notification and generates the metric delay analysis data
    *
    * @param event Event
    */
  override def handleEvent(event: Event): Unit = {
    event match {
      case pathTraversalEvent: PathTraversalEvent =>
        val mode = pathTraversalEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_MODE)
        if (mode.equals(CAR.value)) {
          val linkIds = pathTraversalEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_LINK_IDS).split(",")
          val linkTravelTimes = pathTraversalEvent.getLinkTravelTimes.split(",").map(_.toInt)
          assert(linkIds.length == linkTravelTimes.length)

          if (linkIds.nonEmpty) {
            for (index <- linkIds.indices) {
              val linkId = linkIds(index)
              val freeLength = networkLinks.get(Id.createLinkId(linkId)).getLength
              val freeSpeed = networkLinks.get(Id.createLinkId(linkId)).getFreespeed
              val travelTime = linkTravelTimes(index)
              var freeFlowDelay = travelTime - (freeLength / freeSpeed).round.toInt

              if (freeFlowDelay >= 0) {

                val existingFreeFlowDelay = cumulativeDelay.getOrElse(linkId, 0.0)
                val existingLinkLength = cumulativeLength.getOrElse(linkId, 0.0)

                cumulativeDelay(linkId) = freeFlowDelay + existingFreeFlowDelay
                cumulativeLength(linkId) = freeLength + existingLinkLength
                totalTravelTime += travelTime

                linkTravelsCount(linkId) = linkTravelsCount.getOrElse(linkId, 0) + 1

                linkAverageDelay(linkId) = DelayInLength(
                  (linkTravelsCount(linkId) * cumulativeDelay(linkId)) / cumulativeLength(linkId),
                  linkTravelsCount(linkId)
                ) //calculate average of link delay for further calculating weighted average

              } else if (freeFlowDelay >= -1) {
                freeFlowDelay = 0
              } else {
                logger.warn(" The delay is negative and the delay = " + freeFlowDelay)
              }
            }
          }
        }
      case _ =>
    }
  }

  override def reset(iteration: Int): Unit = {
    cumulativeDelay.clear
    cumulativeLength.clear
    linkTravelsCount.clear
    linkAverageDelay.clear
    capacitiesDelay.clear
    totalTravelTime = 0
  }

  def categoryDelayCapacityDataset(iteration: Int): Unit = {
    cumulativeDelay.keySet foreach { linkId =>
      val delay = cumulativeDelay.getOrElse(linkId, 0.0)
      val capacity = networkLinks.get(Id.createLinkId(linkId)).getCapacity

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
    val avg = linkAverageDelay.values.map(delayInLength => delayInLength.delay).sum / linkAverageDelay.values
      .map(delayInLength => delayInLength.length)
      .sum
    delayAveragePerKMDataset.addValue(avg, 0, iteration)
  }

  def generateDelayAnalysis(event: IterationEndsEvent): Unit = {
    categoryDelayCapacityDataset(event.getIteration)
    if (delayTotalByLinkCapacityDataset != null) {
      createDelayCapacityGraph(fileName)
    }
    averageDelayDataset(event)
    createDelayAveragePerKilometerGraph()
  }

  def createDelayCapacityGraph(fileName: String): Unit = {
    val chart = GraphUtils.createStackedBarChartWithDefaultSettings(
      delayTotalByLinkCapacityDataset,
      graphTitle,
      "iteration",
      yAxisName,
      fileName + ".png",
      true
    )

    val plot: CategoryPlot = chart.getCategoryPlot
    GraphUtils.plotLegendItems(plot, legends.toList.asJava, delayTotalByLinkCapacityDataset.getRowCount)

    val graphImageFile =
      GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputFilename(fileName + ".png")
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
      delayTotalByLinkCapacityDataset,
      averageGraphTitle,
      xAxisAverageGraphName,
      yAxisAverageGraphName,
      fileName,
      false
    )
    GraphUtils.saveJFreeChartAsPNG(
      chart,
      fileName,
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )
  }

  def getTotalDelay: Double = cumulativeDelay.values.sum

}
