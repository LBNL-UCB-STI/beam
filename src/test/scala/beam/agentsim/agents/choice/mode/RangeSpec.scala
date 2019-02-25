package beam.agentsim.agents.choice.mode
import org.scalatest.FlatSpec
import beam.sim.common.Range

class RangeSpec extends FlatSpec {

  "Range(0,0)" should "be empty" in {
    assert(Range(0, 0).isEmpty)
  }

  "[:] and [0:]" should "mean Range(0, Int.MaxValue)" in {
    assert(Range("[:]") == Range(0, Int.MaxValue))
    assert(Range("[0:]") == Range(0, Int.MaxValue))
  }

  "[:<number>] and [0:<number>]" should "mean Range(0, <number>)" in {
    assert(Range("[:2147483647]") == Range(0, 2147483647))
    assert(Range("[0:2147483647]") == Range(0, 2147483647))
  }

  "The empty string" should "mean the empty range" in {
    assert(Range("") == Range.empty())
  }

  "[1:10]" should "mean Range(1, 10)" in {
    assert(Range("[1:10]") == Range(1, 10))
  }
  it should "contain both 1 and 10" in {
    assert(Range("[1:10]").has(1))
    assert(Range("[1:10]").has(10))
  }

  "(1:10]" should "mean Range(2, 10)" in {
    assert(Range("(1:10]") == Range(2, 10))
  }
  it should "contain 10, but not 1" in {
    assert(Range("(1:10]").has(10))
    assert(!Range("(1:10]").has(1))
  }

  "[1:10)" should "mean Range(1, 9)" in {
    assert(Range("[1:10)") == Range(1, 9))
  }
  it should "contain 1, but not 10" in {
    assert(Range("[1:10)").has(1))
    assert(!Range("[1:10)").has(10))
  }

}
