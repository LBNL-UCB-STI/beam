package beam.integration

import beam.sim.BeamHelper
import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers


trait Repeated extends AnyFlatSpec with Matchers with BeamHelper with Retries {

  val retries = 5

  override def withFixture(test: NoArgTest) = {
    if (isRetryable(test))
      withFixture(test, retries)
    else
      super.withFixture(test)
  }

  def withFixture(test: NoArgTest, count: Int): Outcome = {
    val outcome = super.withFixture(test)
    println(outcome.toString)
    outcome match {
      case Failed(_) | Canceled(_) => if (count == 1) super.withFixture(test) else withFixture(test, count - 1)
      case other => other
    }
  }
}