package beam.utils

import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.Activity

import scala.collection.JavaConverters._
import scala.collection.mutable

class ActivitySegment(private val activities: Array[Activity], val binSize: Int) extends LazyLogging {
  import ActivitySegment._

  val sorted: Array[Activity] = activities.sortBy(x => x.getEndTime)

  def minTime: Int = sorted.head.getEndTime.toInt
  def maxTime: Int = sorted.last.getEndTime.toInt

  private val emptyArr: Array[Activity] = Array.empty
  private val maxIdx: Int = sorted.last.getEndTime.toInt / binSize
  private val arr: Array[Array[Activity]] = build(sorted, binSize)

  def getActivities(time: Double): IndexedSeq[Activity] = {
    val idx = time.toInt / binSize
    if (idx > maxIdx) {
      // logger.warn(s"Cant find bucket at time: $time, idx: $idx, maxIdx: $maxIdx")
      emptyArr
    } else {
      val r: Array[Activity] = Option(arr(idx)).getOrElse(emptyArr)
      r
    }
  }

  def getActivities(startTime: Double, endTime: Double): scala.collection.Set[Activity] = {
    require(startTime <= endTime)
    val res = new mutable.HashSet[Activity]()
    var t: Double = startTime
    while (t <= endTime) {
      getActivities(t).foreach(res += _)
      t += binSize
    }
    res
  }
}

object ActivitySegment {

  def apply(scenario: Scenario, binSize: Int): ActivitySegment = {
    val activities = scenario.getPopulation.getPersons.values.asScala.flatMap { person =>
      person.getSelectedPlan.getPlanElements.asScala.collect {
        case act: Activity if !act.getEndTime.isNegInfinity => act
      }
    }.toArray
    new ActivitySegment(activities, binSize)
  }

  def build(activities: Array[Activity], binSize: Int): Array[Array[Activity]] = {
    val maxTime = activities.maxBy(x => x.getEndTime).getEndTime
    val maxIdx: Int = maxTime.toInt / binSize
    val arr: Array[Array[Activity]] = Array.ofDim(maxIdx + 1)
    val binToActivities: Map[Int, Array[Activity]] = activities
      .map { act =>
        val binIdx = (act.getEndTime / binSize).toInt
        binIdx -> act
      }
      .groupBy { case (binIdx, _) => binIdx }
      .map { case (binIdx, xs) => binIdx -> xs.map(_._2) }
    binToActivities.foreach { case (bin, acts) =>
      arr.update(bin, acts)
    }
    arr
  }
}
