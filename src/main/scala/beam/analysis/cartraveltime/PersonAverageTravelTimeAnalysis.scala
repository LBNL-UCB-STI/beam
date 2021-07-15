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

/**
  * Analyzes the average travel time by car mode for every iteration (including walk)
  * @param beamConfig Beam config instance
  */
class PersonAverageTravelTimeAnalysis @Inject() (
  beamConfig: BeamConfig
) extends GraphAnalysis {

  object GraphMode extends Enumeration {
    type GraphMode = Value
    val PersonEntersAndLeavesVehicle, PersonDepartureAndArrival = Value
  }

  // Tracks the person departure events (car only)
  val personCarDepartures: mutable.HashMap[String, PersonDepartureEvent] =
    mutable.HashMap.empty[String, PersonDepartureEvent]

  // Tracks the person arrival events (car only)
  val personCarArrivals: mutable.HashMap[String, PersonArrivalEvent] = mutable.HashMap.empty[String, PersonArrivalEvent]

  // Tracks the person walk events (towards the car to get into the vehicle)
  val personWalksTowardsCar: mutable.HashMap[String, PersonEntersVehicleEvent] =
    mutable.HashMap.empty[String, PersonEntersVehicleEvent]

  // Tracks the person walk events (away from the car after getting down the car)
  val personWalksAwayFromCar: mutable.HashMap[String, PersonLeavesVehicleEvent] =
    mutable.HashMap.empty[String, PersonLeavesVehicleEvent]

  // Tracks the person entering vehicle events (car only)
  val personEntersCar: mutable.HashMap[String, PersonEntersVehicleEvent] =
    mutable.HashMap.empty[String, PersonEntersVehicleEvent]

  // Tracks the person leaving the vehicle events (car only)
  val personLeavesCar: mutable.HashMap[String, PersonLeavesVehicleEvent] =
    mutable.HashMap.empty[String, PersonLeavesVehicleEvent]

  // Stores the total travel times for each person during the iteration (car only)
  val personTotalCarTravelTimes: mutable.HashMap[String, List[Double]] = mutable.HashMap.empty[String, List[Double]]

  // Stores the cumulative walk time (to/from car) for the respective departure of the person
  // this is then added to the travel time to get including walk times graph
  val personTravelTimeIncludingWalkForCurrentDeparture: mutable.HashMap[String, Double] =
    mutable.HashMap.empty[String, Double]

  // Stores the total travel times including walk to/from vehicle for each person during the iteration (car only)
  val personTotalTravelTimesIncludingWalk: mutable.HashMap[String, List[Double]] =
    mutable.HashMap.empty[String, List[Double]]

  // Stores the average travel times (including walk times) for all people for all iterations (car only)
  val averageCarTravelTimesIncludingWalkForAllIterations: mutable.HashMap[Int, Double] =
    mutable.HashMap.empty[Int, Double]

  // Stores the average travel times for all people for all iterations (car only)
  val averageCarTravelTimesForAllIterations: mutable.HashMap[Int, Double] = mutable.HashMap.empty[Int, Double]

  /**
    * Processes the required events to compute the average travel time.
    * @param event instance of event
    */
  override def processStats(event: Event): Unit = {
    event match {
      // Processes and tracks the person departing from the source location
      case pde: PersonDepartureEvent if isCarMode(pde.getLegMode) =>
        processDepartureEvent(pde)
      // Process the person walking to the vehicle and entering the vehicle events
      case pev: PersonEntersVehicleEvent if personCarDepartures.contains(pev.getPersonId.toString) =>
        processPersonEntersVehicleEvent(pev)
      // Process the person walking away from the vehicle and leaving the vehicle events
      case plv: PersonLeavesVehicleEvent if personCarDepartures.contains(plv.getPersonId.toString) =>
        processPersonLeavesVehicleEvent(plv)
      // Process the person arriving at the destination events
      case pae: PersonArrivalEvent
          if isCarMode(pae.getLegMode) && personCarDepartures.contains(pae.getPersonId.toString) =>
        processArrivalEvent(pae)
      case _ =>
    }
  }

  /**
    * Processes the person entering the vehicle event to compute the walk and travel times.
    * @param pev instance of PersonEntersVehicleEvent
    */
  private def processPersonEntersVehicleEvent(pev: PersonEntersVehicleEvent): Unit = {
    val personId = pev.getPersonId.toString
    // if the person is walking
    if (isPersonBody(pev.getVehicleId.toString, personId)) {
      // Person started to walk towards his car
      personWalksTowardsCar.put(personId, pev)
    } else {
      personWalksTowardsCar.get(personId) match {
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

  /**
    * Processes the person leaving the vehicle events to compute the walk and travel times.
    * @param plv instance of PersonLeavesVehicleEvent
    */
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
    * Processes and tracks the departure event of the person to compute the travel times.
    * @param event PersonDepartureEvent
    */
  private def processDepartureEvent(event: PersonDepartureEvent): Unit = {
    // Start tracking departure of this person
    personCarDepartures.put(event.getPersonId.toString, event)
    personTravelTimeIncludingWalkForCurrentDeparture.put(event.getPersonId.toString, 0.0)
  }

  /**
    * Processes the arrival at destination event compute the total/average travel times.
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
        val updatedTime = personTotalTravelTimesIncludingWalk.getOrElse(
          personId,
          List.empty[Double]
        ) :+ personTravelTimeIncludingWalkForCurrentDeparture
          .getOrElse(personId, 0.0)
        personTotalTravelTimesIncludingWalk.put(personId, updatedTime)
        // clear state
        personTravelTimeIncludingWalkForCurrentDeparture.remove(personId)
      case None =>
    }
  }

  /**
    * Creates two line graphs : Average car travel times / Average car travel times (including walk).
    * @param event instance of IterationEndsEvent
    */
  override def createGraph(event: IterationEndsEvent): Unit = {
    // Total travel times of all people (car mode only) including walk to/from the car before/after travel
    val totalTravelTimesIncludingWalk =
      personTotalCarTravelTimes.values.toList.flatten ++ personTotalTravelTimesIncludingWalk.values.flatten
    // Tracks the average travel time (including walk) for each iteration
    averageCarTravelTimesIncludingWalkForAllIterations.put(
      event.getIteration,
      getAverageTravelTimeInMinutes(totalTravelTimesIncludingWalk)
    )
    // Tracks the average travel time for each iteration
    averageCarTravelTimesForAllIterations.put(
      event.getIteration,
      getAverageTravelTimeInMinutes(personTotalCarTravelTimes.values.toList.flatten)
    )
    // Plot XY line charts in the last iteration and save in the root folder
    if ((event.getIteration == beamConfig.beam.agentsim.lastIteration) && beamConfig.beam.outputs.writeGraphs) {
      val line1Data =
        getSeriesDataForMode(averageCarTravelTimesIncludingWalkForAllIterations, event, "CarTravelTimes_IncludingWalk")
      val line2Data = getSeriesDataForMode(averageCarTravelTimesForAllIterations, event, "CarTravelTimes")
      val dataset = GraphUtils.createMultiLineXYDataset(Array(line1Data, line2Data))
      createRootGraphForAverageCarTravelTime(event, dataset)
    }
  }

  /**
    * Generates the series data required for the XY graph
    * @param data data that needs to be converted to the series data
    * @param event instance of iteration ends event
    * @param title title for the series graph
    * @return XYSeries data set
    */
  private def getSeriesDataForMode(
    data: mutable.HashMap[Int, Double],
    event: IterationEndsEvent,
    title: String
  ): XYSeries = {
    // Compute average travel time of all people during the current iteration
    val items: Array[XYDataItem] = data.toArray.map(i => new XYDataItem(i._1, i._2))
    GraphUtils.createXYSeries(title, "", "", items)
  }

  /**
    * Computes the average travel time (in minutes) from the total travel times.
    * @param totalTravelTimes Total travel times recorded by all people during the current iteration
    * @return average travel time (in minutes)
    */
  private def getAverageTravelTimeInMinutes(totalTravelTimes: List[Double]) = {
    try {
      val averageTravelTime = totalTravelTimes.sum / totalTravelTimes.length
      java.util.concurrent.TimeUnit.SECONDS.toMinutes(Math.ceil(averageTravelTime).toLong).toDouble
    } catch {
      case _: Exception => 0d
    }
  }

  /**
    * Plots graph for average travel times (car mode only) at root level
    * @param event IterationEndsEvent
    */
  private def createRootGraphForAverageCarTravelTime(
    event: IterationEndsEvent,
    dataSet: XYDataset
  ): Unit = {

    val fileName = "averageCarTravelTimes.png"
    val outputDirectoryHierarchy = event.getServices.getControlerIO
    val chart =
      ChartFactory.createXYLineChart("Average Travel Times [Car]", "Iteration", "Average Travel Time [min]", dataSet)
    val xyPlot = chart.getXYPlot
    val xAxis = xyPlot.getDomainAxis()

    // This sets some padding to the graph when the x-axis value starts at 0
    xAxis.setLowerBound(xAxis.getRange.getLowerBound - 0.5)

    // Sets the unit width of each tick on x-axis
    val tickUnits = new TickUnits()
    tickUnits.add(new NumberTickUnit(1.0))
    xAxis.setStandardTickUnits(tickUnits)

    // Saves the graph as png file
    GraphUtils.saveJFreeChartAsPNG(
      chart,
      outputDirectoryHierarchy.getOutputFilename(fileName),
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )
  }

  /**
    * Checks if the given mode is car mode or not
    * @param mode beam mode
    * @return boolean
    */
  private def isCarMode(mode: String) = mode.equalsIgnoreCase("car")

  /**
    * Checks if the current vehicle is the person's body (walk modes)
    * @param vehicle id of the vehicle
    * @param personId id of the person
    * @return boolean
    */
  private def isPersonBody(vehicle: String, personId: String) = vehicle.equalsIgnoreCase(s"body-$personId")

  /**
    * Resets all collections at the end of current iteration.
    */
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
