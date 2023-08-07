package beam.agentsim.agents.choice.mode

import beam.sim.common.DoubleTypedRange
import org.scalatest.flatspec.AnyFlatSpec

class DoubleTypedRangeSpec extends AnyFlatSpec {

  "(0:0)" should "be empty" in {
    assert(DoubleTypedRange("(0:0)").isEmpty)
  }

  "[:] and [0:]" should "mean DoubleTypedRange(0, Int.MaxValue)" in {
    assert(DoubleTypedRange("[:]") == DoubleTypedRange(0, Double.MaxValue))
    assert(DoubleTypedRange("[0:]") == DoubleTypedRange(0, Double.MaxValue))
  }

  "[:<number>] and [0:<number>]" should "mean DoubleTypedRange(0, <number>)" in {
    assert(DoubleTypedRange("[:2147483647]") == DoubleTypedRange(0, 2147483647))
    assert(DoubleTypedRange("[0:2147483647]") == DoubleTypedRange(0, 2147483647))
  }

  "The empty string" should "mean the empty range" in {
    assert(DoubleTypedRange("") == DoubleTypedRange.empty())
  }

  "[1: 10]" should "mean DoubleTypedRange(1, 10)" in {
    assert(DoubleTypedRange("[1:10]") == DoubleTypedRange(1, 10))
  }
  it should "contain both 1 and 10" in {
    assert(DoubleTypedRange("[1:10]").has(1))
    assert(DoubleTypedRange("[1:10]").has(10))
  }

  "(1:10]" should "contain 10, but not 1" in {
    assert(DoubleTypedRange("(1:10]").has(10))
    assert(!DoubleTypedRange("(1:10]").has(1))
  }

  "[1:10)" should "contain 1, but not 10" in {
    assert(DoubleTypedRange("[1:10)").has(1))
    assert(!DoubleTypedRange("[1:10)").has(10))
  }

  "(0:1)" should "contain Double.MinPositiveValue, but not 0" in {
    assert(DoubleTypedRange("(0:1)").has(Double.MinPositiveValue))
    assert(!DoubleTypedRange("(0:1)").has(0))
  }

  "(0:1)" should "contain 0.99999999, but not 1.0" in {
    assert(DoubleTypedRange("(0:1)").has(0.99999999))
    assert(!DoubleTypedRange("(0:1)").has(1))
  }

}
