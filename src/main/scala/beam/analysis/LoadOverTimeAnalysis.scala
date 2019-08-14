package beam.analysis

import beam.agentsim.events._
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.analysis.plots.{GraphAnalysis, GraphUtils, GraphsStatsAgentSimEventsListener}
import beam.utils.logging.ExponentialLazyLogging

import org.jfree.chart.ChartFactory
import org.jfree.chart.plot.PlotOrientation
import org.jfree.data.category.{CategoryDataset, DefaultCategoryDataset}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.IterationEndsEvent

import scala.collection.mutable

class LoadOverTimeAnalysis extends GraphAnalysis with ExponentialLazyLogging {
  private val loadOverTimeFileBaseName = "loadOverTime"

  val vehicleTypeToHourlyLoad = mutable.Map.empty[String, mutable.Map[Int, (Double, Int)]]

  override def processStats(event: Event): Unit = {
    val hourOfEvent = (event.getTime / 3600).toInt
    event match {
      case refuelSessionEvent: RefuelSessionEvent =>
        val vehicleType = refuelSessionEvent.vehicleType
        val loadVehicleType =
          if (refuelSessionEvent.getAttributes
                .get(RefuelSessionEvent.ATTRIBUTE_VEHICLE_ID)
                .toLowerCase
                .contains("ridehail")) {
            if (vehicleType.isCaccEnabled) "CAV RideHail" else "Human RideHail"
          } else "Personal"
        val energyInJoules = refuelSessionEvent.energyInJoules
        val sessionDuration = refuelSessionEvent.sessionDuration
        val currentEventAverageLoad = energyInJoules / sessionDuration / 1000
        vehicleTypeToHourlyLoad.get(loadVehicleType) match {
          case Some(hourlyLoadMap) =>
            hourlyLoadMap.get(hourOfEvent) match {
              case Some((currentAverage, currentCount)) =>
                val currentLoadTotal = currentAverage * currentCount
                val newCount = currentCount + 1
                hourlyLoadMap.put(hourOfEvent, ((currentLoadTotal + currentEventAverageLoad) / newCount, newCount))
              case None => hourlyLoadMap.put(hourOfEvent, (currentEventAverageLoad, 1))
            }
          case None =>
            vehicleTypeToHourlyLoad.put(loadVehicleType, mutable.Map(hourOfEvent -> (currentEventAverageLoad, 1)))
        }
      case _ =>
    }
  }

  override def resetStats(): Unit = {
    vehicleTypeToHourlyLoad.clear
  }

  override def createGraph(event: IterationEndsEvent): Unit = {
    val outputDirectoryHiearchy = event.getServices.getControlerIO

    val loadDataset = createLoadDataset()
    val loadImageFile =
      outputDirectoryHiearchy.getIterationFilename(event.getIteration, s"$loadOverTimeFileBaseName.png")
    createGraph(loadDataset, loadImageFile, "Load Over Time")
  }

  private def createLoadDataset(): CategoryDataset = {
    val dataset = new DefaultCategoryDataset
    vehicleTypeToHourlyLoad.foreach {
      case (beamVehicleType, hourlyLoadMap) => {
        hourlyLoadMap.toSeq.sortBy(_._1) foreach {
          case (hour, (average, _)) => dataset.addValue(average, beamVehicleType.toString, hour)
        }
      }
    }

    dataset

    /*val maxHour = TimeUnit.SECONDS.toHours(maxTime).toInt
    val dataset = new DefaultCategoryDataset
    vehicleParkingInHour.foreach({
      case (VehicleParking(_, parkingType), hour) =>
        (hour to maxHour).foreach(updateParkingCount(_, parkingType.toString))
    })
    hourlyParkingTypeCount.foreach({
      case (hour, parkingTypeCount) =>
        parkingTypeCount.foreach({
          case (parkingType, count) =>
            dataset.addValue(count, parkingType, hour)
        })
    })
    dataset
   */
  }

  private def createGraph(dataSet: CategoryDataset, graphImageFile: String, title: String): Unit = {

    val chart =
      ChartFactory.createLineChart(title, "Hour", "#Count", dataSet, PlotOrientation.VERTICAL, true, true, false)

    GraphUtils.saveJFreeChartAsPNG(
      chart,
      graphImageFile,
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )

  }
}
