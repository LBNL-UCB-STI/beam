package beam.analysis

import beam.agentsim.events._
import beam.analysis.plots.{GraphAnalysis, GraphUtils, GraphsStatsAgentSimEventsListener}
import beam.utils.logging.ExponentialLazyLogging

import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.core.controler.events.IterationEndsEvent

import org.jfree.chart.ChartFactory
//import org.jfree.chart.JFreeChart
import org.jfree.chart.plot.PlotOrientation
//import org.jfree.chart.plot.XYPlot
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer
import org.jfree.data.xy.XYSeries
import org.jfree.data.xy.XYSeriesCollection
/*import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.network.Network
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.utils.geometry.CoordUtils
import org.matsim.core.utils.io.IOUtils*/

import scala.collection.mutable

import java.awt.geom.Ellipse2D

class ChargingAnalysis extends GraphAnalysis with ExponentialLazyLogging {
  private val fileBaseName = "chargeEventsPerDayPerDriver"

  sealed trait GeneralizedVehicleType
  case object CAV_Ridehail extends GeneralizedVehicleType
  case object Human_Ridehail extends GeneralizedVehicleType

  type DriverId = Id[Person]

  val vehicleTypeToHourlyLoad = mutable.Map.empty[String, mutable.Map[Int, (Double, Int)]]
  //charge events per day per driver for RH (by human vs CAV)
  //avg kWh per day per driver for RH

  val humanCount = mutable.Map.empty[DriverId, Int]
  val cavCount = mutable.Map.empty[DriverId, Int]

  override def processStats(event: Event): Unit = {
    event match {
      case refuelSessionEvent: RefuelSessionEvent =>
        val driverId: DriverId = refuelSessionEvent.getPersonId
        val vehicleType = refuelSessionEvent.vehicleType
        val generalizedVehicleTypeOption: Option[GeneralizedVehicleType] =
          if (refuelSessionEvent.getAttributes
                .get(RefuelSessionEvent.ATTRIBUTE_VEHICLE_ID)
                .toLowerCase
                .contains("ridehail")) {
            if (vehicleType.isCaccEnabled) Some(CAV_Ridehail) else Some(Human_Ridehail)
          } else None
        generalizedVehicleTypeOption
          .map {
            case CAV_Ridehail   => cavCount
            case Human_Ridehail => humanCount
          }
          .map { countMap =>
            {
              countMap.get(driverId) match {
                case Some(count) => countMap.put(driverId, count + 1)
                case None        => countMap.put(driverId, 1)
              }
            }
          }
      case _ =>
    }
  }

  override def resetStats(): Unit = {
    humanCount.clear
    cavCount.clear
  }

  override def createGraph(event: IterationEndsEvent): Unit = {
    val outputDirectoryHiearchy = event.getServices.getControlerIO
    val pathToPlotCav =
      outputDirectoryHiearchy.getIterationFilename(event.getIteration, s"${fileBaseName}_cav.png")
    createPlotFrom(cavCount, pathToPlotCav)
    val pathToPlotHuman =
      outputDirectoryHiearchy.getIterationFilename(event.getIteration, s"${fileBaseName}_human.png")
    createPlotFrom(humanCount, pathToPlotHuman)
  }

  private def createPlotFrom(countMap: mutable.Map[DriverId, Int], pathToPlot: String) = {

    val series = new XYSeries("Charge Events Per Driver", false)
    countMap.foreach {
      case (driver, count) => series.add(driver.toString.chars.sum, count)
    }

    val dataset = new XYSeriesCollection
    dataset.addSeries(series)

    val chart = ChartFactory.createScatterPlot(
      "Charge Events Per Driver",
      "Driver",
      "Count",
      dataset,
      PlotOrientation.VERTICAL,
      true,
      true,
      false
    )

    val xyplot = chart.getXYPlot()
    xyplot.setDomainCrosshairVisible(false)
    xyplot.setRangeCrosshairVisible(false)

    val renderer = new XYLineAndShapeRenderer()
    renderer.setSeriesShape(0, new Ellipse2D.Double(0, 0, 5, 5))
    renderer.setSeriesLinesVisible(0, false)

    xyplot.setRenderer(0, renderer)

    GraphUtils.saveJFreeChartAsPNG(
      chart,
      pathToPlot,
      1000,
      1000
    )
  }
}
