package beam.analysis

import beam.utils.{VMClassInfo, VMUtils}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class VMInformationCollectorTest extends AnyFlatSpec with Matchers {
  import VMInformationCollectorTest._

  it should "parse class histogram from JFR output" in {
    val parts = VMUtils.parseClassHistogram(vmHeapClassHistogramAtIteration0, 3)
    parts.length shouldBe 3
    val expectedInfos = Seq(
      new VMClassInfo("[D", 121531352, 566190),
      new VMClassInfo("[C", 34284560, 663243),
      new VMClassInfo("[I", 22648216, 204633)
    )

    parts.zip(expectedInfos).foreach { case (ci1: VMClassInfo, ci2: VMClassInfo) =>
      ci1.className shouldBe ci2.className
      ci1.numberOfBytes shouldBe ci2.numberOfBytes
      ci1.countOfInstances shouldBe ci2.countOfInstances
    }
  }

  it should "create a map class to numberOfBytes" in {
    val parts0 = VMUtils.parseClassHistogram(vmHeapClassHistogramAtIteration0, 5)
    val parts1 = VMUtils.parseClassHistogram(vmHeapClassHistogramAtIteration1, 5)
    val parts5 = VMUtils.parseClassHistogram(vmHeapClassHistogramAtIteration5, 5)

    parts0.length shouldBe 5
    parts1.length shouldBe 5
    parts5.length shouldBe 5

    val vmInfoWriter = new VMInformationCollector(null)

    def check(map: mutable.Map[String, ListBuffer[Long]], numberOfRecords: Int, lengthOfIterationValues: Int): Unit = {
      map.size shouldBe numberOfRecords
      map.values.foreach { iterationVals =>
        iterationVals.length shouldBe lengthOfIterationValues
      }
    }

    vmInfoWriter.analyzeVMClassHystogram(parts0, 0)
    check(vmInfoWriter.classNameToBytesPerIteration, 5, 1)

    vmInfoWriter.analyzeVMClassHystogram(parts1, 1)
    check(vmInfoWriter.classNameToBytesPerIteration, 7, 2)

    vmInfoWriter.analyzeVMClassHystogram(parts5, 5)
    check(vmInfoWriter.classNameToBytesPerIteration, 8, 6)
  }

}

object VMInformationCollectorTest {

  val vmHeapClassHistogramAtIteration0: String =
    """ num     #instances         #bytes  class name
      |----------------------------------------------
      |   1:        566190      121531352  [D
      |   2:        663243       34284560  [C
      |   3:        204633       22648216  [I
      |   4:        664155       15939720  java.lang.String
      |   5:        188696       13586112  org.matsim.core.network.LinkImpl
      |   6:        269147       10765880  java.util.LinkedHashMap$Entry
      |   7:        309819        8948312  [Ljava.lang.Object;
      |   8:        275174        6604176  org.matsim.utils.objectattributes.attributable.Attributes
      |   9:        188700        6038464  [[D
      |  10:        276872        5896904  [Ljava.lang.String;
      |""".stripMargin

  val vmHeapClassHistogramAtIteration1: String =
    """

      | num     #instances         #bytes  class name
      |----------------------------------------------
      |   1:       1230409      430900192  [D
      |   2:        416657       85573816  [I
      |   3:        744246       40165784  [C
      |   4:        449627       36985704  [Ljava.lang.Object;
      |   5:        881561       21157464  java.lang.Double
      |   6:        745065       17881560  java.lang.String
      |   7:       1099537       17592592  java.lang.Integer
      |   8:        435770       13944640  java.util.concurrent.ConcurrentHashMap$Node
      |   9:        188696       13586112  org.matsim.core.network.LinkImpl
      |  10:        415506       13296192  java.util.HashMap$Node""".stripMargin

  val vmHeapClassHistogramAtIteration5: String =
    """
      | num     #instances         #bytes  class name
      |----------------------------------------------
      |   1:       2739977      823387872  [D
      |   2:        655071      119849432  [I
      |   3:        756951       41103616  [C
      |   4:        467709       38893888  [Ljava.lang.Object;
      |   5:       1133050       29080352  [[D
      |   6:        816823       26138336  java.util.concurrent.ConcurrentHashMap$Node
      |   7:        805081       25762592  java.util.HashMap$Node
      |   8:        193249       25615144  [Ljava.util.HashMap$Node;
      |   9:        885192       21244608  java.lang.Double
      |  10:        757706       18184944  java.lang.String
      |""".stripMargin

}
