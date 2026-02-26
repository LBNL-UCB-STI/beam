package beam

import java.util.concurrent.ConcurrentHashMap

object TestTracker {
  private val running = ConcurrentHashMap.newKeySet[String]()

  def addTest(suiteName: String, testName: String): Unit =
    running.add(s"$suiteName: $testName")

  def removeTest(suiteName: String, testName: String): Unit =
    running.remove(s"$suiteName: $testName")

  def getRunningTests: Seq[String] = {
    import scala.jdk.CollectionConverters._
    running.asScala.toSeq
  }
}
