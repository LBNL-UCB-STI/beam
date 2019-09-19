package beam.utils

import beam.router.BeamRouter.Location
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Coord, Scenario}
import org.matsim.api.core.v01.population.Activity

import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConverters._
import scala.collection.mutable

class ActivitySegment(val activities: Array[Activity], val binSize: Int) extends LazyLogging {
  import ActivitySegment._

  val sorted = activities.sortBy(x => x.getEndTime)

  private val emptyArr: Array[Coord] = Array.empty
  private val maxIdx: Int = sorted.last.getEndTime.toInt / binSize
  private val arr: Array[Array[Coord]] = build(sorted, binSize)

  def getCoords(time: Double): IndexedSeq[Coord] = {
    val idx = time.toInt / binSize
    if (idx > maxIdx) {
      logger.warn(s"Cant find bucket at time: $time, idx: $idx, maxIdx: $maxIdx")
      emptyArr
    } else {
      val r: Array[Location] = Option(arr(idx)).getOrElse(emptyArr)
      r
    }
  }

  def getCoords(startTime: Double, endTime: Double): scala.collection.Set[Coord] = {
    require(startTime <= endTime)
    val res = new mutable.HashSet[Coord]()
    var t: Double = startTime
    while (t <= endTime) {
      getCoords(t).foreach(res += _)
      t += binSize
    }
    res
  }
}

object ActivitySegment {

  def apply(scenario: Scenario, binSize: Int): ActivitySegment = {
    val activities = scenario.getPopulation.getPersons.values.asScala.flatMap { person =>
      person.getSelectedPlan.getPlanElements.asScala.collect {
        case act: Activity if act.getEndTime != Double.NegativeInfinity =>
          act
      }
    }.toArray
    new ActivitySegment(activities, binSize)
  }

  def build(sorted: Array[Activity], binSize: Int): Array[Array[Coord]] = {
    val minTime = sorted.head.getEndTime
    val maxTime = sorted.last.getEndTime
    val maxIdx: Int = maxTime.toInt / binSize
    val arr: Array[Array[Coord]] = Array.ofDim(maxIdx + 1)

    val buf = new ArrayBuffer[Coord]
    var i: Int = 0
    var j: Int = minTime.toInt / binSize
    var s = minTime
    while (i < sorted.length) {
      val act = sorted(i)
      val time = act.getEndTime
      if (time - s > binSize) {
        arr.update(j, buf.toArray)
        buf.clear()
        j += 1
        s = time
      } else {
        buf += act.getCoord
      }
      i += 1
    }
    arr
  }
}
