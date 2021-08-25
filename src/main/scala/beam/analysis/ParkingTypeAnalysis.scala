package beam.analysis

import java.util.concurrent.TimeUnit

import beam.agentsim.events.{LeavingParkingEvent, ParkingEvent}
import beam.analysis.plots.{GraphAnalysis, GraphUtils, GraphsStatsAgentSimEventsListener}
import beam.utils.logging.ExponentialLazyLogging
import org.jfree.data.category.{CategoryDataset, DefaultCategoryDataset}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.IterationEndsEvent

import scala.collection.mutable

case class VehicleParking(vehicleId: String, parkingType: String)

class ParkingTypeAnalysis(maxTime: Int) extends GraphAnalysis with ExponentialLazyLogging {

  private val parkingFileBaseName = "parkingType"

  private val hourlyParkingTypeCount = mutable.TreeMap[Int, mutable.Map[String, Int]]()
  private val vehicleParkingInHour = mutable.Map[VehicleParking, Int]().withDefaultValue(0)

  override def processStats(event: Event): Unit = {
    val hourOfEvent = (event.getTime / 3600).toInt
    event match {
      case parkEvent: ParkingEvent =>
        val vehicleId = parkEvent.vehicleId.toString
        vehicleParkingInHour.update(VehicleParking(vehicleId, parkEvent.parkingType.toString), hourOfEvent)

      case leavingParkingEvent: LeavingParkingEvent =>
        val vehicleId = leavingParkingEvent.vehicleId.toString

        val previousParkingHour =
          vehicleParkingInHour.remove(VehicleParking(vehicleId, leavingParkingEvent.parkingType.toString)).getOrElse(0)
        (previousParkingHour to hourOfEvent).foreach(updateParkingCount(_, leavingParkingEvent.parkingType.toString))

      case _ =>
    }
  }

  override def resetStats(): Unit = {
    hourlyParkingTypeCount.clear()
    vehicleParkingInHour.clear()
  }

  override def createGraph(event: IterationEndsEvent): Unit = {
    val outputDirectoryHiearchy = event.getServices.getControlerIO

    val parkingDataset = createParkingDataset()
    val parkingGraphImageFile =
      outputDirectoryHiearchy.getIterationFilename(event.getIteration, s"$parkingFileBaseName.png")
    createGraph(parkingDataset, parkingGraphImageFile, "Parking Type")

  }

  private def createGraph(dataSet: CategoryDataset, graphImageFile: String, title: String): Unit = {
    val chart =
      GraphUtils.createLineChartWithDefaultSettings(dataSet, title, "Hour", "#Count", true, true)

    GraphUtils.saveJFreeChartAsPNG(
      chart,
      graphImageFile,
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )

  }

  private def createParkingDataset(): CategoryDataset = {

    val maxHour = TimeUnit.SECONDS.toHours(maxTime).toInt
    val dataset = new DefaultCategoryDataset
    vehicleParkingInHour.foreach({ case (VehicleParking(_, parkingType), hour) =>
      (hour to maxHour).foreach(updateParkingCount(_, parkingType))
    })
    hourlyParkingTypeCount.foreach({ case (hour, parkingTypeCount) =>
      parkingTypeCount.foreach({ case (parkingType, count) =>
        dataset.addValue(count, parkingType, hour)
      })
    })
    dataset
  }

  private def updateParkingCount(hour: Int, parkingType: String): Unit = {
    val parkingTypeCount = hourlyParkingTypeCount.getOrElse(hour, mutable.Map[String, Int]().withDefaultValue(0))
    parkingTypeCount.update(parkingType, parkingTypeCount(parkingType) + 1)
    hourlyParkingTypeCount.update(hour, parkingTypeCount)
  }
}
