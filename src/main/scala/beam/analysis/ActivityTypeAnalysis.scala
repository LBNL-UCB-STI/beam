package beam.analysis
import java.util.concurrent.TimeUnit

import beam.analysis.plots.{GraphAnalysis, GraphUtils, GraphsStatsAgentSimEventsListener}
import beam.utils.logging.ExponentialLazyLogging
import org.jfree.chart.ChartFactory
import org.jfree.chart.plot.PlotOrientation
import org.jfree.data.category.{CategoryDataset, DefaultCategoryDataset}
import org.matsim.api.core.v01.events.{ActivityEndEvent, ActivityStartEvent, Event}
import org.matsim.core.controler.events.IterationEndsEvent

import scala.collection.mutable

case class ActivityTime(activity: String, time: Int)

class ActivityTypeAnalysis(maxTime: Int) extends GraphAnalysis with ExponentialLazyLogging {

  private val activityTypeFileBaseName = "activityType"

  private val hourlyActivityType = mutable.Map[String, ActivityTime]()
  private val hourlyActivityCount = mutable.TreeMap[Int, mutable.Map[String, Int]]()

  override def processStats(event: Event): Unit = {
    val hourOfEvent = (event.getTime / 3600).toInt
    event match {
      case activityStart: ActivityStartEvent =>
        hourlyActivityType.update(
          activityStart.getPersonId.toString,
          ActivityTime(activityStart.getActType, hourOfEvent)
        )
      case activityEnd: ActivityEndEvent =>
        val previousActivity =
          hourlyActivityType.remove(activityEnd.getPersonId.toString).getOrElse(ActivityTime(activityEnd.getActType, 0))
        (previousActivity.time to hourOfEvent).foreach(updateActivityCount(_, previousActivity.activity))
      case _ =>
    }
  }

  def updateActivityCount(hour: Int, activity: String): Unit = {
    val activityTypeCount = hourlyActivityCount.getOrElse(hour, mutable.Map[String, Int]().withDefaultValue(0))
    activityTypeCount.update(activity, activityTypeCount(activity) + 1)
    hourlyActivityCount.update(hour, activityTypeCount)
  }

  override def resetStats(): Unit = {
    hourlyActivityType.clear()
    hourlyActivityCount.clear()
  }

  override def createGraph(event: IterationEndsEvent): Unit = {
    val outputDirectoryHiearchy = event.getServices.getControlerIO

    val activityDataset = createActivityDataset()
    val activityGraphImageFile =
      outputDirectoryHiearchy.getIterationFilename(event.getIteration, s"$activityTypeFileBaseName.png")
    createGraph(activityDataset, activityGraphImageFile, "Activity Type")

  }

  private def createActivityDataset(): CategoryDataset = {
    val dataset = new DefaultCategoryDataset

    val maxHour = TimeUnit.SECONDS.toHours(maxTime).toInt

    hourlyActivityType.foreach({
      case (_, ActivityTime(activity, hour)) => (hour to maxHour).foreach(updateActivityCount(_, activity))
    })

    hourlyActivityCount.foreach({
      case (hour, activityTypeCount) =>
        activityTypeCount.foreach({
          case (activityType, count) =>
            dataset.addValue(count, activityType, hour)
        })
    })
    dataset
  }

  private def createGraph(dataSet: CategoryDataset, graphImageFile: String, title: String): Unit = {
    val chart = GraphUtils.createLineChartWithDefaultSettings(dataSet, title, "Hour", "#Count", true, true)

    GraphUtils.saveJFreeChartAsPNG(
      chart,
      graphImageFile,
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )
  }
}
