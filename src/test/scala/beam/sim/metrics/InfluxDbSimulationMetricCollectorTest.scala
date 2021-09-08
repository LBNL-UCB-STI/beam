package beam.sim.metrics

import java.util.concurrent.ConcurrentHashMap

import org.junit.runner.RunWith
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import scala.collection.mutable

@RunWith(classOf[JUnitRunner])
class InfluxDbSimulationMetricCollectorTest extends AnyWordSpecLike with Matchers {

  val concurrentMap = new ConcurrentHashMap[String, Long]()
  val metricName = "test_name"
  val metricTime = 100500L
  val mapMetricName = s"$metricName:$metricTime"
  val delta = 1L
  val numberOfRuns = 42

  class Writer() extends Thread {

    override def run(): Unit = {
      for (_ <- 1 to numberOfRuns) {
        InfluxDbSimulationMetricCollector.getNextInfluxTs(concurrentMap, metricName, metricTime, delta)
      }
    }
  }

  "InfluxDbSimulationMetricCollector.getNextInfluxTs" should {
    "work properly when used from different threads for the same metric name" in {
      concurrentMap.clear()

      val cores = Runtime.getRuntime.availableProcessors()
      val threadsCnt = cores * 10
      val threads: mutable.Queue[Writer] = mutable.Queue.empty[Writer]
      val expected: Long = (threadsCnt * numberOfRuns * delta) + metricTime

      for (_ <- 1 to threadsCnt) {
        val thread = new Writer()
        thread.start()
        threads.enqueue(thread)
      }

      while (threads.nonEmpty) {
        val thread = threads.dequeue()
        thread.join()
      }

      concurrentMap should not be empty

      val storedTime = concurrentMap.getOrDefault(mapMetricName, metricTime)
      storedTime shouldBe expected
    }
  }
}
