package beam.analysis

import beam.agentsim.events._
import beam.analysis.plots.{GraphAnalysis, GraphUtils, GraphsStatsAgentSimEventsListener}
import beam.sim.metrics.SimulationMetricCollector
import beam.sim.metrics.SimulationMetricCollector.SimulationTime
import beam.utils.logging.ExponentialLazyLogging
import org.jfree.data.category.{CategoryDataset, DefaultCategoryDataset}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.IterationEndsEvent

import scala.collection.mutable

class LoadOverTimeAnalysis(simMetricCollector: SimulationMetricCollector)
    extends GraphAnalysis
    with ExponentialLazyLogging {
  private val loadOverTimeFileBaseName = "chargingPower"

  val vehicleTypeToHourlyLoad = mutable.Map.empty[String, mutable.Map[Int, (Double, Int)]]
  val chargerTypeToHourlyLoad = mutable.Map.empty[String, mutable.Map[Int, (Double, Int)]]
  val parkingTypeToHourlyLoad = mutable.Map.empty[String, mutable.Map[Int, (Double, Int)]]

  override def processStats(event: Event): Unit = {
    val hourOfEvent = (event.getTime / 3600).toInt
    event match {
      case refuelSessionEvent: RefuelSessionEvent =>
        val vehicleType = refuelSessionEvent.vehicleType
        val loadVehicleType =
          if (
            refuelSessionEvent.getAttributes
              .get(RefuelSessionEvent.ATTRIBUTE_VEHICLE_ID)
              .toLowerCase
              .contains("ridehail")
          ) {
            if (vehicleType.isCaccEnabled) "CAV RideHail" else "Human RideHail"
          } else "Personal"
        val energyInkWh = refuelSessionEvent.energyInJoules / 3.6e6

        vehicleTypeToHourlyLoad.get(loadVehicleType) match {
          case Some(hourlyLoadMap) =>
            hourlyLoadMap.get(hourOfEvent) match {
              case Some((currentLoadTotal, currentCount)) =>
                val newCount = currentCount + 1
                hourlyLoadMap.put(hourOfEvent, (currentLoadTotal + energyInkWh, newCount))
              case None => hourlyLoadMap.put(hourOfEvent, (energyInkWh, 1))
            }
          case None =>
            vehicleTypeToHourlyLoad.put(loadVehicleType, mutable.Map(hourOfEvent -> (energyInkWh, 1)))
        }

        val chargerType = refuelSessionEvent.chargingPointString
        chargerTypeToHourlyLoad.get(chargerType) match {
          case Some(hourlyLoadMap) =>
            hourlyLoadMap.get(hourOfEvent) match {
              case Some((currentLoadTotal, currentCount)) =>
                val newCount = currentCount + 1
                hourlyLoadMap.put(hourOfEvent, (currentLoadTotal + energyInkWh, newCount))
              case None => hourlyLoadMap.put(hourOfEvent, (energyInkWh, 1))
            }
          case None =>
            chargerTypeToHourlyLoad.put(chargerType, mutable.Map(hourOfEvent -> (energyInkWh, 1)))
        }

        val parkingType: String = refuelSessionEvent.parkingType
        parkingTypeToHourlyLoad.get(parkingType) match {
          case Some(hourlyLoadMap) =>
            hourlyLoadMap.get(hourOfEvent) match {
              case Some((currentLoadTotal, currentCount)) =>
                val newCount = currentCount + 1
                hourlyLoadMap.put(hourOfEvent, (currentLoadTotal + energyInkWh, newCount))
              case None => hourlyLoadMap.put(hourOfEvent, (energyInkWh, 1))
            }
          case None =>
            parkingTypeToHourlyLoad.put(parkingType, mutable.Map(hourOfEvent -> (energyInkWh, 1)))
        }

        if (simMetricCollector.metricEnabled(loadOverTimeFileBaseName)) {
          // it turns out that coordinates already in WGS
          // geoUtils.utm2Wgs(refuelSessionEvent.stall.locationUTM)
          val locationWGS = refuelSessionEvent.stall.locationUTM

          val sessionDuration = refuelSessionEvent.sessionDuration
          val currentEventAverageLoadInkWh = if (sessionDuration != 0) energyInkWh / sessionDuration else 0

          simMetricCollector.write(
            loadOverTimeFileBaseName,
            SimulationTime(event.getTime.toInt),
            Map(
              "count"       -> 1.0,
              "averageLoad" -> currentEventAverageLoadInkWh,
              "lon"         -> locationWGS.getX,
              "lat"         -> locationWGS.getY
            ),
            Map(
              "vehicleType"   -> loadVehicleType,
              "typeOfCharger" -> chargerType,
              "parkingType"   -> parkingType
            )
          )
        }

      case _ =>
    }
  }

  override def resetStats(): Unit = {
    vehicleTypeToHourlyLoad.clear
    chargerTypeToHourlyLoad.clear
    parkingTypeToHourlyLoad.clear
  }

  override def createGraph(event: IterationEndsEvent): Unit = {
    val outputDirectoryHiearchy = event.getServices.getControlerIO

    var loadDataset = createLoadDataset(vehicleTypeToHourlyLoad)
    var loadImageFile =
      outputDirectoryHiearchy.getIterationFilename(event.getIteration, s"${loadOverTimeFileBaseName}ByUser.png")
    createGraph(loadDataset, loadImageFile, "Load By User Type")

    loadDataset = createLoadDataset(chargerTypeToHourlyLoad)
    loadImageFile =
      outputDirectoryHiearchy.getIterationFilename(event.getIteration, s"${loadOverTimeFileBaseName}ByChargerType.png")
    createGraph(loadDataset, loadImageFile, "Load By Charger Type")

    loadDataset = createLoadDataset(parkingTypeToHourlyLoad)
    loadImageFile =
      outputDirectoryHiearchy.getIterationFilename(event.getIteration, s"${loadOverTimeFileBaseName}ByParkingType.png")
    createGraph(loadDataset, loadImageFile, "Load By Parking Type")
  }

  private def createLoadDataset(
    hourlyLoadData: mutable.Map[String, mutable.Map[Int, (Double, Int)]]
  ): CategoryDataset = {
    val dataset = new DefaultCategoryDataset
    val allHours = hourlyLoadData.flatMap(tup => tup._2.keys).toList.distinct.sorted
    hourlyLoadData.foreach { case (loadType, hourlyLoadMap) =>
      allHours.foreach { hour =>
        hourlyLoadMap.get(hour) match {
          case Some((average, _)) =>
            dataset.addValue(average, loadType, hour)
          case None =>
            dataset.addValue(0.0, loadType, hour)
        }
      }
    }
    dataset
  }

  private def createGraph(dataSet: CategoryDataset, graphImageFile: String, title: String): Unit = {
    val chart = GraphUtils.createLineChartWithDefaultSettings(
      dataSet,
      title,
      "Hour",
      "Avg. Power (kW)",
      true,
      true
    )

    GraphUtils.saveJFreeChartAsPNG(
      chart,
      graphImageFile,
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )

  }
}
