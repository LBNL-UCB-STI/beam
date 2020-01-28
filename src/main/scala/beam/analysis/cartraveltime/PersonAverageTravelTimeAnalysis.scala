package beam.analysis.cartraveltime

import beam.analysis.plots.{GraphAnalysis, GraphUtils, GraphsStatsAgentSimEventsListener}
import beam.sim.config.BeamConfig
import javax.inject.Inject
import org.jfree.chart.ChartFactory
import org.jfree.chart.axis.{NumberTickUnit, TickUnits}
import org.jfree.data.xy.{XYDataItem, XYDataset, XYSeries}
import org.matsim.api.core.v01.events._
import org.matsim.core.controler.events.IterationEndsEvent

import scala.collection.mutable

class PersonAverageTravelTimeAnalysis @Inject()(
  beamConfig: BeamConfig
) extends GraphAnalysis {

  object GraphMode extends Enumeration {
    type GraphMode = Value
    val PersonEntersAndLeavesVehicle, PersonDepartureAndArrival = Value
  }

  val personCarDepartures: mutable.HashMap[String, PersonDepartureEvent] =
    mutable.HashMap.empty[String, PersonDepartureEvent]
  val personCarArrivals: mutable.HashMap[String, PersonArrivalEvent] = mutable.HashMap.empty[String, PersonArrivalEvent]

  val personWalksTowardsCar: mutable.HashMap[String, PersonEntersVehicleEvent] =
    mutable.HashMap.empty[String, PersonEntersVehicleEvent]

  val personWalksAwayFromCar: mutable.HashMap[String, PersonLeavesVehicleEvent] =
    mutable.HashMap.empty[String, PersonLeavesVehicleEvent]

  val personEntersCar: mutable.HashMap[String, PersonEntersVehicleEvent] =
    mutable.HashMap.empty[String, PersonEntersVehicleEvent]

  val personLeavesCar: mutable.HashMap[String, PersonLeavesVehicleEvent] =
    mutable.HashMap.empty[String, PersonLeavesVehicleEvent]

  val personTotalCarTravelTimes: mutable.HashMap[String, List[Double]] = mutable.HashMap.empty[String, List[Double]]

  val personTravelTimeIncludingWalkForCurrentDeparture: mutable.HashMap[String, Double] =
    mutable.HashMap.empty[String, Double]

  val personTotalTravelTimesIncludingWalk: mutable.HashMap[String, List[Double]] =
    mutable.HashMap.empty[String, List[Double]]

  val averageCarTravelTimesIncludingWalkForAllIterations: mutable.HashMap[Int, Double] =
    mutable.HashMap.empty[Int, Double]
  val averageCarTravelTimesForAllIterations: mutable.HashMap[Int, Double] = mutable.HashMap.empty[Int, Double]

  override def processStats(event: Event): Unit = {
    event match {
      case pde: PersonDepartureEvent if isCarMode(pde.getLegMode) =>
        processDepartureEvent(pde)
      case pev: PersonEntersVehicleEvent if personCarDepartures.contains(pev.getPersonId.toString) =>
        processPersonEntersVehicleEvent(pev)
      case plv: PersonLeavesVehicleEvent if personCarDepartures.contains(plv.getPersonId.toString) =>
        processPersonLeavesVehicleEvent(plv)
      case pae: PersonArrivalEvent
          if isCarMode(pae.getLegMode) && personCarDepartures.contains(pae.getPersonId.toString) =>
        processArrivalEvent(pae)
      case _ =>
    }
  }

  private def processPersonEntersVehicleEvent(pev: PersonEntersVehicleEvent): Unit = {
    val personId = pev.getPersonId.toString
    // if the person is walking
    if (isPersonBody(pev.getVehicleId.toString, personId)) {
      // Person started to walk towards his car
      personWalksTowardsCar.put(personId, pev)
    } else {
      personWalksTowardsCar.get(personId.toString) match {
        case Some(walkEvent: PersonEntersVehicleEvent) =>
          // Person previously walked towards his car
          personWalksTowardsCar.remove(personId)
          // Person now entered his car
          personEntersCar.put(personId, pev)
          // calculate the person walk time and add it to the cumulative walk times
          val walkTime = pev.getTime - walkEvent.getTime
          // add walk time to the including walk cumulative time for this departure
          val updatedTime = personTravelTimeIncludingWalkForCurrentDeparture.getOrElse(personId, 0.0) + walkTime
          personTravelTimeIncludingWalkForCurrentDeparture.put(personId, updatedTime)
        case None =>
      }
    }
  }

  private def processPersonLeavesVehicleEvent(plv: PersonLeavesVehicleEvent) = {
    val personId = plv.getPersonId.toString
    // The person is got down his car
    if (!isPersonBody(plv.getVehicleId.toString, personId)) {
      personEntersCar.get(personId) match {
        case Some(pev: PersonEntersVehicleEvent) =>
          // Person previously entered his car and started his journey
          personEntersCar.remove(personId)
          // Person now got down his car
          personLeavesCar.put(personId, plv)
          // calculate the person travel time by his car and add it to the cumulative car travel times
          val travelTime = plv.getTime - pev.getTime
          val updatedTravelTimes = personTotalCarTravelTimes.getOrElse(personId, List.empty[Double]) :+ travelTime
          personTotalCarTravelTimes.put(personId, updatedTravelTimes)
          // add walk time to the including walk cumulative time for this departure
          val updatedTime = personTravelTimeIncludingWalkForCurrentDeparture.getOrElse(personId, 0.0) + travelTime
          personTravelTimeIncludingWalkForCurrentDeparture.put(personId, updatedTime)
        case None =>
      }
    } else {
      // The person started walking away from his car
      personLeavesCar.get(personId) match {
        case Some(leftCarEvent: PersonLeavesVehicleEvent) =>
          // Person previously left his car
          personLeavesCar.remove(personId)
          // Person now walked away from his car
          personWalksAwayFromCar.put(personId, plv)
          // calculate the person walk time and add it to the cumulative walk times
          val walkTime = plv.getTime - leftCarEvent.getTime
          // add walk time to the including walk cumulative time for this departure
          val updatedTime = personTravelTimeIncludingWalkForCurrentDeparture.getOrElse(personId, 0.0) + walkTime
          personTravelTimeIncludingWalkForCurrentDeparture.put(personId, updatedTime)
        case None =>
      }
    }
  }

  /**
    * Processes departure events to compute the travel times.
    * @param event PersonDepartureEvent
    */
  private def processDepartureEvent(event: PersonDepartureEvent): Unit = {
    // Start tracking departure of this person
    personCarDepartures.put(event.getPersonId.toString, event)
    personTravelTimeIncludingWalkForCurrentDeparture.put(event.getPersonId.toString, 0.0)
  }

  /**
    * Processes arrival events to compute the travel time from previous departure
    * @param event PersonArrivalEvent
    */
  private def processArrivalEvent(event: PersonArrivalEvent): Unit = {
    val personId = event.getPersonId.toString
    // The person previously started walking away from car
    personWalksAwayFromCar.get(personId) match {
      case Some(_) =>
        // Person now completed his walk and arrived at his destination
        personWalksAwayFromCar.remove(personId)
        // Stop tracking the departure of this person
        personCarDepartures.remove(personId)
        // add including walk cumulative time of the current depature - arrival to total list
        val updatedTime = personTotalTravelTimesIncludingWalk.getOrElse(personId, List.empty[Double]) :+ personTravelTimeIncludingWalkForCurrentDeparture
          .getOrElse(personId, 0.0)
        personTotalTravelTimesIncludingWalk.put(personId, updatedTime)
        // clear state
        personTravelTimeIncludingWalkForCurrentDeparture.remove(personId)
      case None =>
    }
  }

  override def createGraph(event: IterationEndsEvent): Unit = {
    val averageTravelTimesIncludingWalk = personTotalCarTravelTimes.values.toList.flatten ++ personTotalTravelTimesIncludingWalk.values.flatten
    averageCarTravelTimesIncludingWalkForAllIterations.put(
      event.getIteration,
      getAverageTravelTimeInMinutes(averageTravelTimesIncludingWalk)
    )
    averageCarTravelTimesForAllIterations.put(
      event.getIteration,
      getAverageTravelTimeInMinutes(personTotalCarTravelTimes.values.toList.flatten)
    )
    if ((event.getIteration == beamConfig.beam.agentsim.lastIteration) && beamConfig.beam.outputs.writeGraphs) {
      val line1Data =
        getSeriesDataForMode(averageCarTravelTimesIncludingWalkForAllIterations, event, "CarTravelTimes_IncludingWalk")
      val line2Data = getSeriesDataForMode(averageCarTravelTimesForAllIterations, event, "CarTravelTimes")
      val dataset = GraphUtils.createMultiLineXYDataset(Array(line1Data, line2Data))
      createRootGraphForAverageCarTravelTime(event, dataset, "averageCarTravelTimesLineChart.png")
    }
  }

  private def getSeriesDataForMode(
    data: mutable.HashMap[Int, Double],
    event: IterationEndsEvent,
    title: String
  ): XYSeries = {
    // Compute average travel time of all people during the current iteration
    val items: Array[XYDataItem] = data.toArray.map(i => new XYDataItem(i._1, i._2))
    GraphUtils.createXYSeries(title, "", "", items)
  }

  private def getAverageTravelTimeInMinutes(totalTravelTimes: List[Double]) = {
    try {
      val averageTravelTime = totalTravelTimes.sum / totalTravelTimes.length
      java.util.concurrent.TimeUnit.SECONDS.toMinutes(Math.ceil(averageTravelTime).toLong).toDouble
    } catch {
      case _: Exception => 0D
    }
  }

  /**
    * Plots graph for average travel times at root level
    * @param event IterationEndsEvent
    */
  private def createRootGraphForAverageCarTravelTime(
    event: IterationEndsEvent,
    dataSet: XYDataset,
    fileName: String
  ): Unit = {
    val outputDirectoryHierarchy = event.getServices.getControlerIO
    val chart =
      ChartFactory.createXYLineChart("Average Travel Times [Car]", "Iteration", "Average Travel Time [min]", dataSet)
    val xyPlot = chart.getXYPlot
    val xAxis = xyPlot.getDomainAxis()
    xAxis.setLowerBound(xAxis.getRange.getLowerBound - 0.5)
    val tickUnits = new TickUnits()
    tickUnits.add(new NumberTickUnit(1.0))
    xAxis.setStandardTickUnits(tickUnits)
    GraphUtils.saveJFreeChartAsPNG(
      chart,
      outputDirectoryHierarchy.getOutputFilename(fileName),
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )
  }

  private def isCarMode(mode: String) = mode.equalsIgnoreCase("car")

  private def isPersonBody(vehicle: String, personId: String) = vehicle.equalsIgnoreCase(s"body-$personId")

  override def resetStats(): Unit = {
    personCarDepartures.clear()
    personCarArrivals.clear()
    personWalksTowardsCar.clear()
    personWalksAwayFromCar.clear()
    personEntersCar.clear()
    personLeavesCar.clear()
    personTotalCarTravelTimes.clear()
    personTotalTravelTimesIncludingWalk.clear()
    personTravelTimeIncludingWalkForCurrentDeparture.clear()
  }
}
