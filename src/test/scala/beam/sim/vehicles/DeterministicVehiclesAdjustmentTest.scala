package beam.sim.vehicles

import beam.utils.UniformRealDistributionEnhanced
import org.scalactic.Tolerance.convertNumericToPlusOrMinusWrapper
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

import scala.collection.mutable

class DeterministicVehiclesAdjustmentTest extends AnyFunSuiteLike {
  val realDistribution = new UniformRealDistributionEnhanced

  val sampleSize: Int = 2
  val numberOfObjectsInTheList: Int = 9
  val numberOfLoops: Int = 4 * 1000000

  val minListValue: Int = 42
  val maxListValue: Int = math.max(numberOfObjectsInTheList, sampleSize) + minListValue
  val originalListOfObjects: List[Int] = (minListValue until maxListValue).toList

  val avgVal: Int = numberOfLoops / originalListOfObjects.length * sampleSize
  val delta: Int = (avgVal * 0.02).floor.toInt

  def saveSample(samplingResult: mutable.HashMap[Int, Int], sampledNumber: Int): Unit = {
    samplingResult(sampledNumber) = samplingResult.getOrElse(sampledNumber, 0) + 1
  }

  test("test one sample") {
    val realDistribution = new UniformRealDistributionEnhanced
    val sampled = DeterministicVehiclesAdjustment.sample(realDistribution, originalListOfObjects, sampleSize)
    sampled.length shouldBe sampleSize
  }

  test("test multiple sample calls") {
    val samplingResult = mutable.HashMap.empty[Int, Int]

    for (_ <- 1 to numberOfLoops) {
      val sampled = DeterministicVehiclesAdjustment.sample(realDistribution, originalListOfObjects, sampleSize)
      sampled.length shouldBe sampleSize
      sampled.foreach(s => saveSample(samplingResult, s))
    }

    samplingResult.values.sum / samplingResult.size shouldBe (avgVal +- delta)
    samplingResult.values.foreach { x => x shouldBe (avgVal +- delta) }
  }

  test("test multiple sampleSimple calls") {
    val samplingResult = mutable.HashMap.empty[Int, Int]

    for (_ <- 1 to numberOfLoops) {
      val sampled = DeterministicVehiclesAdjustment.sampleSimple(realDistribution, originalListOfObjects, sampleSize)
      sampled.length shouldBe sampleSize
      sampled.foreach(s => saveSample(samplingResult, s))
    }

    samplingResult.values.sum / samplingResult.size shouldBe (avgVal +- delta)
    samplingResult.values.foreach { x => x shouldBe (avgVal +- delta) }
  }

  test("test multiple sampleForLongList calls") {
    val samplingResult = mutable.HashMap.empty[Int, Int]

    for (_ <- 1 to numberOfLoops) {
      val sampled =
        DeterministicVehiclesAdjustment.sampleForLongList(realDistribution, originalListOfObjects, sampleSize)
      sampled.length shouldBe sampleSize
      sampled.foreach(s => saveSample(samplingResult, s))
    }

    samplingResult.values.sum / samplingResult.size shouldBe (avgVal +- delta)
    samplingResult.values.foreach { x => x shouldBe (avgVal +- delta) }
  }

  test("test multiple sampleSmart calls") {
    val samplingResult = mutable.HashMap.empty[Int, Int]

    for (_ <- 1 to numberOfLoops) {
      val sampled = DeterministicVehiclesAdjustment.sampleSmart(realDistribution, originalListOfObjects, sampleSize)
      sampled.length shouldBe sampleSize
      sampled.foreach(s => saveSample(samplingResult, s))
    }

    samplingResult.values.sum / samplingResult.size shouldBe (avgVal +- delta)
    samplingResult.values.foreach { x => x shouldBe (avgVal +- delta) }
  }
}
