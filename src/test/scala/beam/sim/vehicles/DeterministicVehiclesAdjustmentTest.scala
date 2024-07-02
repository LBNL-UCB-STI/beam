package beam.sim.vehicles

import org.apache.commons.math3.distribution.UniformRealDistribution
import org.scalactic.Tolerance.convertNumericToPlusOrMinusWrapper
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

import scala.collection.mutable

class DeterministicVehiclesAdjustmentTest extends AnyFunSuiteLike {

  val sampleSize: Int = 5
  val numberOfObjectsInTheList: Int = 10
  val numberOfLoops: Int = 1 * 1000000

  val minListValue: Int = 42
  val maxListValue: Int = math.max(numberOfObjectsInTheList, sampleSize) + minListValue
  val originalListOfObjects: List[Int] = (minListValue until maxListValue).toList

  val avgVal: Int = numberOfLoops / originalListOfObjects.length * sampleSize
  val delta: Int = (avgVal * 0.02).floor.toInt

  def saveSample(samplingResult: mutable.HashMap[Int, Int], sampledNumber: Int): Unit = {
    samplingResult(sampledNumber) = samplingResult.getOrElse(sampledNumber, 0) + 1
  }

  test("test one sample") {
    val realDistribution = new UniformRealDistribution
    val sampled = DeterministicVehiclesAdjustment.sample(realDistribution, originalListOfObjects, sampleSize)
    sampled.length shouldBe sampleSize
  }

  // ~ 2 min
  // ~ 6 min
  // ~ 10 min
  // ~ 15 min
  test("test multiple samples 1") {
    val realDistribution = new UniformRealDistribution
    val samplingResult = mutable.HashMap.empty[Int, Int]

    for (_ <- 1 to numberOfLoops) {
      val sampled = DeterministicVehiclesAdjustment.sample(realDistribution, originalListOfObjects, sampleSize)
      sampled.length shouldBe sampleSize
      sampled.foreach(s => saveSample(samplingResult, s))
    }

    samplingResult.values.sum / samplingResult.size shouldBe (avgVal +- delta)
    samplingResult.values.foreach { x => x shouldBe (avgVal +- delta) }
  }

  // ~ 1 min
  // ~ 2 min
  // ~ 2 min
  // ~ 2 min
  test("test multiple samples 2") {
    val samplingResult = mutable.HashMap.empty[Int, Int]

    for (_ <- 1 to numberOfLoops) {
      val sampled = DeterministicVehiclesAdjustment.sample_simple(originalListOfObjects, sampleSize)
      sampled.length shouldBe sampleSize
      sampled.foreach(s => saveSample(samplingResult, s))
    }

    samplingResult.values.sum / samplingResult.size shouldBe (avgVal +- delta)
    samplingResult.values.foreach { x => x shouldBe (avgVal +- delta) }
  }

}
