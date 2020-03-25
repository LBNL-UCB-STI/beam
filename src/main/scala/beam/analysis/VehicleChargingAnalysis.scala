package beam.analysis

//import beam.agentsim.events.{ChargingPlugInEvent, ChargingPlugOutEvent}
import beam.agentsim.events.{ChargingPlugInEvent, ChargingPlugOutEvent}
import beam.analysis.plots.{GraphAnalysis, GraphUtils, GraphsStatsAgentSimEventsListener}
import beam.sim.metrics.MetricsSupport
import beam.utils.logging.ExponentialLazyLogging
import org.jfree.chart.ChartFactory
import org.jfree.chart.plot.PlotOrientation
import org.jfree.data.category.{CategoryDataset, DefaultCategoryDataset}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.IterationEndsEvent

import scala.collection.mutable

class VehicleChargingAnalysis extends GraphAnalysis with ExponentialLazyLogging with MetricsSupport {

  private val vehicleChargingFileBaseName = "chargingNumberVehicles"

  private val vehicleChargingTime = mutable.Map[String, Int]()
  private val hourlyChargingCount = mutable.TreeMap[Int, Int]().withDefaultValue(0)

  override def processStats(event: Event): Unit = {
    val hourOfEvent = (event.getTime / 3600).toInt
    event match {
      case pluginEvent: ChargingPlugInEvent =>
        countOccurrence(
          "charging-plug-in",
          pluginEvent.getTime.toInt,
          tags =
            Map("pricing_model" -> pluginEvent.pricingModelString, "charging_point" -> pluginEvent.chargingPointString)
        )

        val vehicle = pluginEvent.getAttributes().get(ChargingPlugInEvent.ATTRIBUTE_VEHICLE_ID)
        vehicleChargingTime.update(vehicle, hourOfEvent)

      case plugoutEvent: ChargingPlugOutEvent =>
        countOccurrence(
          "charging-plug-out",
          plugoutEvent.getTime.toInt,
          tags = Map(
            "pricing_model"  -> plugoutEvent.pricingModelString,
            "charging_point" -> plugoutEvent.chargingPointString
          )
        )

        val vehicle = plugoutEvent.getAttributes().get(ChargingPlugOutEvent.ATTRIBUTE_VEHICLE_ID)
        val pluginTime = vehicleChargingTime.remove(vehicle)
        pluginTime match {
          case Some(time) =>
            (time until hourOfEvent) foreach (hour => {
              hourlyChargingCount.update(hour, hourlyChargingCount(hour) + 1)
            })
          case None =>
            logger.warn("Found ChargingPlugOutEvent without ChargingPlugInEvent")

        }

      case _ =>
    }
  }

  override def resetStats(): Unit = {
    vehicleChargingTime.clear()
    hourlyChargingCount.clear()
  }

  override def createGraph(event: IterationEndsEvent): Unit = {
    val outputDirectoryHiearchy = event.getServices.getControlerIO

    val chargingDataset = createChargingDataset()
    val chargingGraphImageFile =
      outputDirectoryHiearchy.getIterationFilename(event.getIteration, s"$vehicleChargingFileBaseName.png")
    createGraph(chargingDataset, chargingGraphImageFile, "Vehicles Charging")

  }

  private def createChargingDataset(): CategoryDataset = {
    val dataset = new DefaultCategoryDataset

    hourlyChargingCount.foreach({
      case (hour, count) => dataset.addValue(count, "charging-vehicle", hour)
    })

    dataset
  }

  private def createGraph(dataSet: CategoryDataset, graphImageFile: String, title: String): Unit = {

    val chart =
      ChartFactory.createLineChart(title, "Hour", "Count", dataSet, PlotOrientation.VERTICAL, true, true, false)

    GraphUtils.saveJFreeChartAsPNG(
      chart,
      graphImageFile,
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )

  }
}
