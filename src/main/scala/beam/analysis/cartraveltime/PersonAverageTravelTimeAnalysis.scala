package beam.analysis.cartraveltime

import beam.agentsim.events.ModeChoiceEvent
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
    val PersonEntersAndLeavesVehicle , PersonDepartureAndArrival = Value
  }

  val personChoseOrEnteredCar : mutable.HashMap[String,Either[ModeChoiceEvent,PersonEntersVehicleEvent]] = mutable.HashMap.empty[String,Either[ModeChoiceEvent,PersonEntersVehicleEvent]]
  val personDepartedByCar : mutable.HashMap[String, PersonDepartureEvent] = mutable.HashMap.empty[String,PersonDepartureEvent]

  val personTotalTravelTimeForCurrentIterationEntersAndLeavesMode : mutable.HashMap[String,List[Double]] = mutable.HashMap.empty[String,List[Double]]
  val averageTravelTimesForAllIterationsEntersAndLeavesMode : mutable.HashMap[Int,Double] = mutable.HashMap.empty[Int,Double]

  val personTotalTravelTimeForCurrentIterationArrivalAndDepartureMode : mutable.HashMap[String,List[Double]] = mutable.HashMap.empty[String,List[Double]]
  val averageTravelTimesForAllIterationsArrivalAndDepartureMode : mutable.HashMap[Int,Double] = mutable.HashMap.empty[Int,Double]

  override def processStats(event: Event): Unit = {
    event match {
      case mc : ModeChoiceEvent if isCarMode(mc.mode) => processModeChoiceEvent(mc)
      case pev: PersonEntersVehicleEvent => processPersonEntersVehicleEvent(pev)
      case plv: PersonLeavesVehicleEvent => processPersonLeavesVehicleEvent(plv)
      case pde: PersonDepartureEvent if isCarMode(pde.getLegMode) => processDepartureEvent(pde)
      case pae: PersonArrivalEvent if isCarMode(pae.getLegMode) => processArrivalEvent(pae)
      case _ =>
    }
  }

  private def processModeChoiceEvent(mc : ModeChoiceEvent) : Unit = {
    personChoseOrEnteredCar.put(mc.personId.toString,Left(mc))
  }

  private def processPersonEntersVehicleEvent(pev: PersonEntersVehicleEvent): Unit = {
      personChoseOrEnteredCar.get(pev.getPersonId.toString) match {
        case Some(e:Either[ModeChoiceEvent,PersonEntersVehicleEvent]) =>
          e match {
            case _ if e.isLeft && isPersonBody(pev.getVehicleId.toString,pev.getPersonId.toString) =>
              personChoseOrEnteredCar.put(pev.getPersonId.toString,Right(pev))
            case _ =>
          }
        case None =>
      }
  }

  private def processPersonLeavesVehicleEvent(plv: PersonLeavesVehicleEvent) = {
    val personId = plv.getPersonId.toString
      personChoseOrEnteredCar.get(personId) match {
        case Some(e:Either[ModeChoiceEvent,PersonEntersVehicleEvent]) =>
          e match {
            case _ if e.isRight && isPersonBody(e.right.get.getVehicleId.toString,e.right.get.getPersonId.toString) =>
              val updatedTravelTimes = personTotalTravelTimeForCurrentIterationEntersAndLeavesMode.getOrElse(personId,List()) :+ (plv.getTime - e.right.get.getTime)
              personTotalTravelTimeForCurrentIterationEntersAndLeavesMode.put(personId,updatedTravelTimes)
              personChoseOrEnteredCar.remove(personId)
            case _ =>
          }
        case None =>
      }
    }

  /**
   * Processes departure events to compute the travel times.
   * @param event PersonDepartureEvent
   */
  private def processDepartureEvent(event: PersonDepartureEvent): Unit = {
    // Track the departure event by person
    personDepartedByCar.put(event.getPersonId.toString,event)
  }

  /**
   * Processes arrival events to compute the travel time from previous departure
   * @param event PersonArrivalEvent
   */
  private def processArrivalEvent(event: PersonArrivalEvent): Unit = {
    // Check for previous departure by car for the person
    personDepartedByCar.get(event.getPersonId.toString) match {
      case Some(departureEvent: PersonDepartureEvent) =>
        // compute the travel time
        val travelTime = event.getTime - departureEvent.getTime
        // add the computed travel time to the list of travel times tracked during the hour
        val travelTimes = personTotalTravelTimeForCurrentIterationArrivalAndDepartureMode.getOrElse(event.getPersonId.toString, List.empty[Double]) :+ travelTime
        personTotalTravelTimeForCurrentIterationArrivalAndDepartureMode.put(event.getPersonId.toString,travelTimes)
        // discard the departure event
        personDepartedByCar.remove(event.getPersonId.toString)
      case None =>
    }
  }

  override def createGraph(event: IterationEndsEvent): Unit = {
    val line1Data = getSeriesDataForMode(GraphMode.PersonEntersAndLeavesVehicle,event)
    val line2Data = getSeriesDataForMode(GraphMode.PersonDepartureAndArrival,event)
    if((event.getIteration == beamConfig.beam.agentsim.lastIteration) && beamConfig.beam.outputs.writeGraphs){
      val dataset = GraphUtils.createMultiLineXYDataset(Array(line1Data,line2Data))
      createRootGraphForAverageCarTravelTime(event,dataset,"averageCarTravelTimesLineChart.png")
      createRootGraphForAverageCarTravelTime(event,GraphUtils.createMultiLineXYDataset(Array(line1Data)),"averageCarTravelTimesLine1Chart.png")
      createRootGraphForAverageCarTravelTime(event,GraphUtils.createMultiLineXYDataset(Array(line2Data)),"averageCarTravelTimesLine2Chart.png")
    }
  }

  private def getSeriesDataForMode(graphMode: GraphMode.Value,event: IterationEndsEvent): XYSeries = {
    // Compute average travel time of all people during the current iteration
    val averageTravelTimeForCurrentIteration = getAverageTravelTimeInMinutes(graphMode)
    val averageTravelTime = graphMode match {
      case _ if graphMode == GraphMode.PersonEntersAndLeavesVehicle => averageTravelTimesForAllIterationsEntersAndLeavesMode
      case _ => averageTravelTimesForAllIterationsArrivalAndDepartureMode
    }
    averageTravelTime.put(event.getIteration,averageTravelTimeForCurrentIteration)
    val items: Array[XYDataItem] = averageTravelTime.toArray.map(i => new XYDataItem(i._1,i._2))
    GraphUtils.createXYSeries(graphMode.toString,"","",items)
  }
  
  private def getAverageTravelTimeInMinutes(graphMode: GraphMode.Value) = {
    try {
      val travelTimesByPerson = graphMode match {
        case _ if graphMode == GraphMode.PersonEntersAndLeavesVehicle => personTotalTravelTimeForCurrentIterationEntersAndLeavesMode
        case _ => personTotalTravelTimeForCurrentIterationArrivalAndDepartureMode
      }
    val consolidatedTravelTimes = travelTimesByPerson.values.toList.flatten
    val averageTravelTime = consolidatedTravelTimes.sum / consolidatedTravelTimes.length
      java.util.concurrent.TimeUnit.SECONDS.toMinutes(Math.ceil(averageTravelTime).toLong).toDouble
    } catch {
      case _: Exception => 0D
    }
  }
  

  /**
   * Plots graph for average travel times at root level
   * @param event IterationEndsEvent
   */
  private def createRootGraphForAverageCarTravelTime(event: IterationEndsEvent, dataSet : XYDataset,fileName : String): Unit = {
    val outputDirectoryHierarchy = event.getServices.getControlerIO
    val chart = ChartFactory.createXYLineChart("Average Travel Times [Car]", "Iteration", "Average Travel Time [min]", dataSet)
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

  private def isCarMode(mode : String)  = mode.equalsIgnoreCase("car")

  private def isPersonBody(vehicle : String,personId : String)  = vehicle.equalsIgnoreCase(s"body-$personId")

  override def resetStats(): Unit = {
    personChoseOrEnteredCar.clear()
    personDepartedByCar.clear()
    personTotalTravelTimeForCurrentIterationEntersAndLeavesMode.clear()
    personTotalTravelTimeForCurrentIterationArrivalAndDepartureMode.clear()
  }
}
