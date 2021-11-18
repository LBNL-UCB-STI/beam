package beam.analysis

import java.util

import beam.agentsim.events.PathTraversalEvent
import beam.analysis.plots.{GraphAnalysis, GraphUtils, GraphsStatsAgentSimEventsListener}
import org.jfree.chart.JFreeChart
import org.jfree.chart.plot.CategoryPlot
import org.jfree.data.category.{CategoryDataset, DefaultCategoryDataset}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.IterationEndsEvent

import scala.collection.JavaConverters._
import scala.collection.mutable

class SimpleRideHailUtilization extends IterationSummaryAnalysis with GraphAnalysis {
  // Offset is number of passengers, value is number of rides with that amount of passengers
  private var overallRideStat: Array[Int] = Array.fill[Int](0)(0)
  private val iterOverallRideStat = mutable.Map[Int, Array[Int]]()

  override def createGraph(event: IterationEndsEvent): Unit = {
    iterOverallRideStat += event.getIteration -> overallRideStat.clone()
    val dataset = new DefaultCategoryDataset
    val revIteration = iterOverallRideStat.keys.toSeq.sorted
    revIteration.foreach { iteration =>
      iterOverallRideStat(iteration).zipWithIndex.foreach { case (rides, numOfPassenger) =>
        dataset.addValue(
          java.lang.Double.valueOf(rides.toString),
          s"RideTripsWith${numOfPassenger}Passengers",
          s"it.$iteration"
        )
      }
    }
    val fileName = event.getServices.getControlerIO.getOutputFilename("rideHailUtilisation.png")
    createGraphInRootDirectory(
      dataset,
      fileName,
      (0 until iterOverallRideStat.values.flatten.size).map(numOfPass => s"RideTripsWith${numOfPass}Passengers").asJava
    )
  }

  override def processStats(event: Event): Unit = {
    event match {
      case pte: PathTraversalEvent if pte.vehicleId.toString.contains("rideHailVehicle-") =>
        handle(pte.numberOfPassengers)
      case _ =>
    }
  }

  override def resetStats(): Unit = {
    overallRideStat = Array.fill[Int](0)(0)
  }

  override def getSummaryStats: util.Map[String, java.lang.Double] = {
    val summaryMap = overallRideStat.zipWithIndex
      .map { case (rides, numOfPassenger) =>
        val value: java.lang.Double = java.lang.Double.valueOf(rides.toString)
        s"RideTripsWith${numOfPassenger}Passengers" -> value
      }
      .toMap
      .asJava
    summaryMap
  }

  def getStat(numOfPassengers: Int): Option[Int] = {
    overallRideStat.lift(numOfPassengers)
  }

  def handle(numOfPassengers: Int): Int = {
    val arrToUpdate = if (numOfPassengers >= overallRideStat.length) {
      val newArr = Array.fill[Int](numOfPassengers + 1)(0)
      Array.copy(overallRideStat, 0, newArr, 0, overallRideStat.length)
      overallRideStat = newArr
      newArr
    } else {
      overallRideStat
    }
    incrementCounter(arrToUpdate, numOfPassengers)
  }

  private def incrementCounter(arrToUpdate: Array[Int], offset: Int): Int = {
    val updatedCounter = arrToUpdate(offset) + 1
    arrToUpdate.update(offset, updatedCounter)
    updatedCounter
  }

  protected def createGraphInRootDirectory(
    dataset: CategoryDataset,
    fileName: String,
    rideTrips: util.List[String]
  ): Unit = {
    val graphTitleName = "Ride Hail Utilisation"
    val xAxisTitle = "Iteration"
    val yAxisTitle = "# Ride Trip"
    val legend = true
    val chart: JFreeChart = GraphUtils.createStackedBarChartWithDefaultSettings(
      dataset,
      graphTitleName,
      xAxisTitle,
      yAxisTitle,
      legend
    )
    val plot: CategoryPlot = chart.getCategoryPlot
    GraphUtils.plotLegendItems(plot, rideTrips, dataset.getRowCount)
    GraphUtils.saveJFreeChartAsPNG(
      chart,
      fileName,
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )
  }
}
