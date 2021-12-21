package beam.agentsim.agents

import beam.sim.BeamHelper
import beam.utils.EventReader._
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.events.Event
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers._

import scala.collection.mutable

class ActivitiesDurationSpec extends AnyFlatSpec with BeamHelper {

  def getActivitiesDurationsGroupedByType(events: Seq[Event]): Map[String, Set[Double]] = {
    class Activity(val time: Double, val actType: String, val person: String)
    class ActEnd(time: Double, actType: String, person: String) extends Activity(time, actType, person)
    class ActStart(time: Double, actType: String, person: String) extends Activity(time, actType, person)

    val activities: Seq[Activity] = events
      .filter(e => "actstart".equals(e.getEventType) || "actend".equals(e.getEventType))
      .map {
        case act if act.getEventType == "actstart" =>
          new ActStart(act.getTime, act.getAttributes.get("actType"), act.getAttributes.get("person"))
        case act if act.getEventType == "actend" =>
          new ActEnd(act.getTime, act.getAttributes.get("actType"), act.getAttributes.get("person"))
      }

    val activitiesToDurations = mutable.HashMap.empty[String, Set[Double]]

    activities
      .groupBy(_.person)
      .foreach { case (_, activities) =>
        val (typeToDurations, _) = activities.foldLeft(Seq.empty[(String, Double)], Option.empty[ActStart]) {
          case ((durations, _), actStart: ActStart) => (durations, Some(actStart))
          case ((durations, Some(actStart)), actEnd: ActEnd) if actEnd.actType == actStart.actType =>
            val duration = actEnd.time - actStart.time
            (durations :+ (actEnd.actType, duration), None)
          case ((durations, _), _) => (durations, None)
        }

        typeToDurations.foreach { case (actType, duration) =>
          activitiesToDurations.get(actType) match {
            case Some(durations) => activitiesToDurations(actType) = durations + duration
            case None            => activitiesToDurations(actType) = Set(duration)
          }
        }
      }

    activitiesToDurations.toMap
  }

  def checkIfDurationsExistAndBiggerThan(
    activitiesDurations: Map[String, Set[Double]],
    activityType: String,
    value: Double
  ): Unit = {
    val durations = activitiesDurations.get(activityType)
    durations should not be None
    durations.get.foreach { duration =>
      assert(duration > value, f"all $activityType durations should be more than $value")
    }
  }

  def checkIfDurationsExistAndEqual(
    activitiesDurations: Map[String, Set[Double]],
    activityType: String,
    value: Double
  ): Unit = {
    val durations = activitiesDurations.get(activityType)
    durations should not be None
    durations.get.foreach { duration =>
      assert(duration == value, f"all $activityType durations should be equal to $value")
    }
  }

  it should "have expected activities duration" in {
    val config = ConfigFactory
      .parseString(s"""
                      |beam.agentsim.lastIteration = 0
                      |beam.outputs.events.fileOutputFormats = "xml,csv"
                     """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

    val (matSimConfig, _, _) = runBeamWithConfig(config)

    val filePath = getEventsFilePath(matSimConfig, "events", "xml", 0).getAbsolutePath
    val events = fromXmlFile(filePath)

    val activitiesDurations: Map[String, Set[Double]] = getActivitiesDurationsGroupedByType(events)

    checkIfDurationsExistAndBiggerThan(activitiesDurations, "Shopping", 2000)
    checkIfDurationsExistAndBiggerThan(activitiesDurations, "Other", 600)
    checkIfDurationsExistAndBiggerThan(activitiesDurations, "Work", 40000)
  }

  it should "have fixed Other,Shopping and Work activities duration" in {
    val expectedWorkDuration = 360.0
    val expectedShoppingDuration = 260.0
    val expectedOtherDuration = 160.0
    val config = ConfigFactory
      .parseString(s"""
                      |beam.agentsim.lastIteration = 0
                      |beam.outputs.events.fileOutputFormats = "xml,csv"
                      |beam.agentsim.agents.activities.activityTypeToFixedDurationMap = [
                      |"Other -> $expectedOtherDuration",
                      |"Shopping -> $expectedShoppingDuration",
                      |"Work -> $expectedWorkDuration"
                      |]
                     """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

    val (matSimConfig, _, _) = runBeamWithConfig(config)
    val filePath = getEventsFilePath(matSimConfig, "events", "xml", 0).getAbsolutePath
    val events = fromXmlFile(filePath)

    val activitiesDurations: Map[String, Set[Double]] = getActivitiesDurationsGroupedByType(events)

    checkIfDurationsExistAndEqual(activitiesDurations, "Work", expectedWorkDuration)
    checkIfDurationsExistAndEqual(activitiesDurations, "Other", expectedOtherDuration)
    checkIfDurationsExistAndEqual(activitiesDurations, "Shopping", expectedShoppingDuration)
  }
}
