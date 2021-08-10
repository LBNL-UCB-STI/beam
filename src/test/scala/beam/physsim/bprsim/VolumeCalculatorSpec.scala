package beam.physsim.bprsim

import org.matsim.api.core.v01.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.math.BigDecimal.RoundingMode

/**
  * @author Dmitry Openkov
  */
class VolumeCalculatorSpec extends AnyWordSpecLike with Matchers {
  "Volume Calculator" must {
    "calculate volume for links" in {
      val vc = new VolumeCalculator(10)
      val link1 = Id.createLinkId(1)
      vc.vehicleEntered(link1, 1.0, isCACC = false)
      vc.vehicleEntered(link1, 1.0, isCACC = false)
      vc.vehicleEntered(link1, 1.0, isCACC = false)
      vc.vehicleEntered(link1, 2.0, isCACC = false)
      vc.vehicleEntered(link1, 3.0, isCACC = false)
      vc.vehicleEntered(link1, 4.0, isCACC = false)
      vc.getVolumeAndCACCShare(link1, 4.0) should be(2160.0, 0)
      vc.vehicleEntered(link1, 11.0, isCACC = false)
      vc.getVolumeAndCACCShare(link1, 11.0) should be(1440.0, 0)
    }
    "calculate volume for links with multiple events at the end" in {
      val vc = new VolumeCalculator(10)
      val link1 = Id.createLinkId(1)
      vc.vehicleEntered(link1, 1.0, isCACC = false)
      vc.vehicleEntered(link1, 2.0, isCACC = false)
      vc.vehicleEntered(link1, 3.0, isCACC = false)
      vc.vehicleEntered(link1, 4.0, isCACC = false)
      vc.vehicleEntered(link1, 4.0, isCACC = false)
      vc.getVolumeAndCACCShare(link1, 4.0) should be(1800.0, 0)
      vc.vehicleEntered(link1, 13.99, isCACC = false)
      vc.getVolumeAndCACCShare(link1, 13.99) should be(1080.0, 0)
      vc.vehicleEntered(link1, 15.0, isCACC = false)
      vc.getVolumeAndCACCShare(link1, 15.0) should be(720.0, 0)
    }
    "remove old events correctly" in {
      val vc = new VolumeCalculator(10)
      val link1 = Id.createLinkId(1)
      vc.vehicleEntered(link1, 1.0, isCACC = false)
      vc.vehicleEntered(link1, 1.0, isCACC = false)
      vc.vehicleEntered(link1, 1.0, isCACC = false)
      vc.vehicleEntered(link1, 2.0, isCACC = false)
      vc.vehicleEntered(link1, 3.0, isCACC = false)
      vc.vehicleEntered(link1, 4.0, isCACC = false)
      vc.getVolumeAndCACCShare(link1, 4.0) should be(2160.0, 0)
      vc.vehicleEntered(link1, 10.0, isCACC = false)
      vc.getVolumeAndCACCShare(link1, 10.0) should be(2520.0, 0)
    }
    "work correctly with a larger time window" in {
      val vc = new VolumeCalculator(60)
      val link1 = Id.createLinkId(1)
      vc.vehicleEntered(link1, 1.0, isCACC = false)
      vc.vehicleEntered(link1, 1.0, isCACC = false)
      vc.vehicleEntered(link1, 1.0, isCACC = false)
      vc.vehicleEntered(link1, 2.0, isCACC = false)
      vc.vehicleEntered(link1, 3.0, isCACC = false)
      vc.vehicleEntered(link1, 4.0, isCACC = false)
      vc.getVolumeAndCACCShare(link1, 4.0) should be(360.0, 0)
      vc.vehicleEntered(link1, 10.0, isCACC = false)
      vc.getVolumeAndCACCShare(link1, 10.0) should be(420.0, 0)
      vc.vehicleEntered(link1, 11.0, isCACC = false)
      vc.getVolumeAndCACCShare(link1, 11.0) should be(480.0, 0)
      vc.vehicleEntered(link1, 20.0, isCACC = false)
      vc.getVolumeAndCACCShare(link1, 20.0) should be(540.0, 0)
      vc.vehicleEntered(link1, 30.0, isCACC = false)
      vc.getVolumeAndCACCShare(link1, 30.0) should be(600.0, 0)
      vc.vehicleEntered(link1, 55.0, isCACC = false)
      vc.getVolumeAndCACCShare(link1, 55.0) should be(660.0, 0)
      vc.vehicleEntered(link1, 60.0, isCACC = false)
      vc.getVolumeAndCACCShare(link1, 60.0) should be(480.0, 0)
      vc.vehicleEntered(link1, 63.0, isCACC = false)
      vc.getVolumeAndCACCShare(link1, 63.0) should be(420.0, 0)
    }
    "calculate CACC share" in {
      import VolumeCalculatorSpec.ReachTuple
      val vc = new VolumeCalculator(60)
      val link1 = Id.createLinkId(1)
      vc.vehicleEntered(link1, 1.0, isCACC = false)
      vc.vehicleEntered(link1, 1.0, isCACC = false)
      vc.vehicleEntered(link1, 1.0, isCACC = true)
      vc.vehicleEntered(link1, 2.0, isCACC = true)
      vc.vehicleEntered(link1, 3.0, isCACC = false)
      vc.vehicleEntered(link1, 4.0, isCACC = true)
      vc.getVolumeAndCACCShare(link1, 4.0).comparable should be(360.0, 0.5)
      vc.vehicleEntered(link1, 10.0, isCACC = false)
      vc.getVolumeAndCACCShare(link1, 10.0).comparable should be(420.0, 0.42857)
      vc.vehicleEntered(link1, 11.0, isCACC = true)
      vc.getVolumeAndCACCShare(link1, 11.0).comparable should be(480.0, 0.5)
      vc.vehicleEntered(link1, 20.0, isCACC = false)
      vc.getVolumeAndCACCShare(link1, 20.0).comparable should be(540.0, 0.44444)
      vc.vehicleEntered(link1, 30.0, isCACC = true)
      vc.getVolumeAndCACCShare(link1, 30.0).comparable should be(600.0, 0.5)
      vc.vehicleEntered(link1, 55.0, isCACC = true)
      vc.getVolumeAndCACCShare(link1, 55.0).comparable should be(660.0, 0.54545)
      vc.vehicleEntered(link1, 60.0, isCACC = true)
      vc.getVolumeAndCACCShare(link1, 60.0).comparable should be(480.0, 0.625)
      vc.vehicleEntered(link1, 63.0, isCACC = true)
      vc.getVolumeAndCACCShare(link1, 63.0).comparable should be(420.0, 0.71429)
    }
    "calculate volume for the events in the past" in {
      val vc = new VolumeCalculator(10)
      val link1 = Id.createLinkId(1)
      vc.vehicleEntered(link1, 1.0, isCACC = false)
      vc.vehicleEntered(link1, 1.0, isCACC = false)
      vc.vehicleEntered(link1, 1.0, isCACC = false)
      vc.vehicleEntered(link1, 2.0, isCACC = false)
      vc.vehicleEntered(link1, 3.0, isCACC = false)
      vc.vehicleEntered(link1, 4.0, isCACC = false)
      vc.getVolumeAndCACCShare(link1, 4.0) should be(2160.0, 0)
      vc.vehicleEntered(link1, 10.0, isCACC = false)
      vc.getVolumeAndCACCShare(link1, 10.0) should be(2520.0, 0)
      vc.vehicleEntered(link1, 5.0, isCACC = false)
      vc.getVolumeAndCACCShare(link1, 5.0) should be(5040.0, 0)
    }
  }
}

object VolumeCalculatorSpec {

  implicit class ReachTuple(val t: (Double, Double)) extends AnyVal {
    def comparable: (Double, Double) = (t._1, BigDecimal(t._2).setScale(5, RoundingMode.HALF_UP).toDouble)
  }
}
