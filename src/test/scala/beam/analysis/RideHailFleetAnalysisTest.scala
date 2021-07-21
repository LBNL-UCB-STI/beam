package beam.analysis

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.sim.metrics.SimulationMetricCollector.SimulationTime
import beam.utils.{BeamVehicleUtils, EventReader}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.util.Try

class Values(values: mutable.Map[Long, Double] = mutable.Map.empty) {

  def apply(key: Long): Double = values.getOrElse(key, 0.0)

  def add(time: SimulationTime, value: Double): Unit = {
    val hour = time.hours
    values.get(hour) match {
      case Some(oldValue) => values(hour) = value + oldValue
      case None           => values(hour) = value
    }
  }

  def print(prefix: String): Unit = {
    //collectedMetrics("rh-ev-cav-count") ("vehicle-state:driving-topickup") (0) shouldBe 0.0 +- precision
    values.toSeq
      .sortBy(_._1)
      .foreach(entry => {
        val (hour, value) = entry
        println(s"$prefix ($hour) shouldBe $value +- precision")
      })

    println("")
  }

  def min: Option[Double] = values.values.map(v => Math.abs(v)).filter(v => v != 0.0) match {
    case Nil        => None
    case collection => Some(collection.min)
  }
}

class Metrics(val metrics: mutable.Map[String, Values] = mutable.Map.empty) extends AnyFlatSpec with Matchers {

  def apply(key: String): Values = metrics.getOrElse(key, new Values())

  def add(time: SimulationTime, value: Double, tags: Map[String, String]): Unit = {
    tags.size should be(1)

    val tag = tags.head._1 + ":" + tags.head._2
    metrics.get(tag) match {
      case Some(values) => values.add(time, value)
      case None =>
        val values = new Values()
        values.add(time, value)
        metrics(tag) = values
    }
  }

  def print(prefix: String): Unit = {
    //collectedMetrics("rh-ev-cav-count") ("vehicle-state:driving-topickup") (0) shouldBe 0.0 +- precision
    metrics.foreach(entry => {
      val (tag, values) = entry
      values.print(prefix + "(\"" + tag + "\")")
    })
  }

  def min: Option[Double] = metrics.values.flatMap(_.min) match {
    case Nil        => None
    case collection => Some(collection.min)
  }
}

class RideHailFleetAnalysisTest extends AnyFlatSpec with Matchers {

  // eventsFileBig may be downloaded from https://beam-outputs.s3.amazonaws.com/output/austin/austin-prod-200k-skims-with-h3-index__2020-04-14_07-06-56_xah/ITERS/it.0/0.events.csv.gz
  val eventsFileBig = ""
  // eventsFileSmall may be obtained from any local sf-light-1k run
  val eventsFileSmall = ""

  val vehicleTypeFile = "test/input/sf-light/vehicleTypes.csv"

  val vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType] =
    BeamVehicleUtils.readBeamVehicleTypeFile(vehicleTypeFile)

  vehicleTypes shouldNot be(empty)

  val collectedMetrics: mutable.Map[String, Metrics] = mutable.Map.empty

  def writeIteration(
    metricName: String,
    time: SimulationTime,
    metricValue: Double = 1.0,
    tags: Map[String, String] = Map.empty,
    overwriteIfExist: Boolean = false
  ): Unit = {

    overwriteIfExist should be(true)

    collectedMetrics.get(metricName) match {
      case Some(metrics) => metrics.add(time, metricValue, tags)
      case None =>
        val metrics = new Metrics()
        metrics.add(time, metricValue, tags)
        collectedMetrics(metricName) = metrics
    }
  }

  def testBig(metrics: mutable.Map[String, Metrics]): Unit = {
    //    RideHailFleetAnalysisTestData.testExpectedOutputBig1(metrics)
    //    RideHailFleetAnalysisTestData.testExpectedOutputBig2(metrics)
    //    RideHailFleetAnalysisTestData.testExpectedOutputBig3(metrics)
    //    RideHailFleetAnalysisTestData.testExpectedOutputBig4(metrics)
    //    RideHailFleetAnalysisTestData.testExpectedOutputBig5(metrics)
    //    RideHailFleetAnalysisTestData.testExpectedOutputBig6(metrics)
    //    RideHailFleetAnalysisTestData.testExpectedOutputBig7(metrics)
    //    RideHailFleetAnalysisTestData.testExpectedOutputBig8(metrics)
  }

  def testSmall(metrics: mutable.Map[String, Metrics]): Unit = {
    //    RideHailFleetAnalysisTestData.testExpectedOutputSmall1(metrics)
    //    RideHailFleetAnalysisTestData.testExpectedOutputSmall2(metrics)
    //    RideHailFleetAnalysisTestData.testExpectedOutputSmall3(metrics)
    //    RideHailFleetAnalysisTestData.testExpectedOutputSmall4(metrics)
    //    RideHailFleetAnalysisTestData.testExpectedOutputSmall5(metrics)
    //    RideHailFleetAnalysisTestData.testExpectedOutputSmall6(metrics)
    //    RideHailFleetAnalysisTestData.testExpectedOutputSmall7(metrics)
    //    RideHailFleetAnalysisTestData.testExpectedOutputSmall8(metrics)
  }

  def test(process: Event => Unit, doItFast: Boolean = false, printTestCases: Boolean = false): Unit = {
    throw new IllegalArgumentException(
      """In order to use these tests you need to generate content of RideHailFleetAnalysisTestData
        |based on events files you are going to use for tests.
        |To do so, you need to specify eventsFileBig and/or eventsFileSmall and run 'test' method
        |with `printTestCases` set to true. That will print you all methods for RideHailFleetAnalysisTestData.
        |Then you will need to copy the printed methods into RideHailFleetAnalysisTestData class body.""".stripMargin
    )

    collectedMetrics.clear()

    val (it, toClose) =
      if (doItFast) EventReader.fromCsvFile(eventsFileSmall, _ => true)
      else EventReader.fromCsvFile(eventsFileBig, _ => true)

    try {
      it.foreach { event =>
        process(event)
      }
    } finally {
      Try(toClose.close())
    }

    collectedMetrics shouldNot be(empty)

    if (doItFast) testSmall(collectedMetrics)
    else testBig(collectedMetrics)

    println("done")

    if (printTestCases) {

      collectedMetrics.values.flatMap(_.min) match {
        case Nil        => println("there is no MIN value at all, only zeros")
        case collection => println(s"min value is: ${collection.min}")
      }

      def funcNameGet: String = if (doItFast) "testExpectedOutputSmall" else "testExpectedOutputBig"

      var counter = 1
      collectedMetrics.foreach(entry => {
        val (metricName, metrics) = entry
        println()
        println(s"def $funcNameGet$counter(collectedMetrics: mutable.Map[String, Metrics]): Unit = {")

        //collectedMetrics("rh-ev-cav-count") ("vehicle-state:driving-topickup") (0) shouldBe 0.0 +- precision
        metrics.print("    collectedMetrics(\"" + metricName + "\")")

        println("}")
        counter += 1
      })
    }
  }

  //  "V2 fleet analysis" must "return expected metrics for BIG events file" in {
  //    val RHFleetEventsAnalysis = new RideHailFleetAnalysisInternalV2(vehicleTypes, writeIteration)
  //    test(event => RHFleetEventsAnalysis.processStats(event), doItFast = false, printTestCases = false)
  //  }
  //
  //  "V2 fleet analysis" must "return expected metrics for SMALL events file" ignore {
  //    val RHFleetEventsAnalysis = new RideHailFleetAnalysisInternalV2(vehicleTypes, writeIteration)
  //    test(event => RHFleetEventsAnalysis.processStats(event), doItFast = true, printTestCases = false)
  //  }
  //
  //  "1. V2 fleet analysis" must "return expected values" ignore {
  //    val RHFleetEventsAnalysis = new RideHailFleetAnalysisInternalV2(vehicleTypes, writeIteration)
  //    test(event => RHFleetEventsAnalysis.processStats(event))
  //  }
  //
  //  "1. fleet analysis" must "return expected values" ignore {
  //    val RHFleetEventsAnalysis = new RideHailFleetAnalysisInternal(vehicleTypes, writeIteration)
  //    test(event => RHFleetEventsAnalysis.processStats(event))
  //  }
  //
  //  "2. V2 fleet analysis" must "return expected values" ignore {
  //    val RHFleetEventsAnalysis = new RideHailFleetAnalysisInternalV2(vehicleTypes, writeIteration)
  //    test(event => RHFleetEventsAnalysis.processStats(event))
  //  }
  //
  //  "2. fleet analysis" must "return expected values" ignore {
  //    val RHFleetEventsAnalysis = new RideHailFleetAnalysisInternal(vehicleTypes, writeIteration)
  //    test(event => RHFleetEventsAnalysis.processStats(event))
  //  }

  "doubles" must "be compared with tolerance" ignore {
    val precision = 0.000000001
    3601.4623522256534 shouldBe 3601.4623522256525 +- precision
  }

  "sync operation" must "return expected values" ignore {

    class MyType(private var content: Double = 0.0) {
      def getContent: Double = { synchronized { content } }

      def Add(secondMyTypeObject: MyType): Unit = {
        content = content + secondMyTypeObject.getContent
      }
    }

    def mapFunc(value: Int): MyType = {
      val content = (0 to value by 2).map(_ * 3.0 / 5.0).sum
      new MyType(content)
    }

    def foldFunc(accum: MyType, collectionItem: MyType): MyType = {
      val funcInfo = s"collection item: ${collectionItem.getContent}"

      val (retVal, changed) = {
        if (collectionItem.getContent % 2 < 0.5 && accum.hashCode() != collectionItem.hashCode()) {
          accum.Add(collectionItem)
          (accum, "          ")
        } else
          (accum, " val SAME ")
      }

      val hashInfo =
        if (accum.hashCode() == collectionItem.hashCode())
          "hash SAME "
        else
          "          "
      println(
        s"${accum.hashCode()} $hashInfo $changed ret acc val: ${retVal.getContent} $funcInfo"
      ) // acc hash: ${accum.hashCode()} threadId: ${Thread.currentThread().getId}")
      retVal
    }

    val regularSumInitValue = new MyType()
    val parallelSumInitValue = new MyType()

    def getParallelSeq = (0 to 100).par.map(mapFunc)
    val parallelSum = getParallelSeq.fold(parallelSumInitValue)(foldFunc)
    val regularSum = getParallelSeq.seq.fold(regularSumInitValue)(foldFunc)

    regularSum.getContent shouldBe parallelSum.getContent

    println(s"regular sum is: ${regularSum.getContent}")
  }

  "sync operation for Double" must "return expected values" ignore {
    def mapFunc(value: Int): Double = (0 to value by 2).map(_ * 3.0 / 5.0).sum

    def foldFunc(accum: Double, collectionItem: Double): Double = {
      if (collectionItem % 2 < 0.5) accum + collectionItem
      else accum
    }

    def getParallelSeq = (0 to 10000).par.map(mapFunc)

    val regularSum = getParallelSeq.seq
      .fold(0.0)(foldFunc)
    val parallelSum = getParallelSeq
      .fold(0.0)(foldFunc)

    println(regularSum)
    regularSum shouldBe parallelSum
  }
}
