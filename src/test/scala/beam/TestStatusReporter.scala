package beam

import org.scalatest.Reporter
import org.scalatest.events._

class TestStatusReporter extends Reporter {

  override def apply(event: Event): Unit = event match {
    case e: TestStarting =>
      TestTracker.addTest(e.suiteName, e.testName)

    case e: TestSucceeded =>
      TestTracker.removeTest(e.suiteName, e.testName)

    case e: TestFailed =>
      TestTracker.removeTest(e.suiteName, e.testName)

    case e: TestPending =>
      TestTracker.removeTest(e.suiteName, e.testName)

    case e: TestCanceled =>
      TestTracker.removeTest(e.suiteName, e.testName)

    case _ => // ignore other event types
  }
}
