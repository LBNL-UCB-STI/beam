package beam.physsim.bprsim

import org.matsim.api.core.v01.Id
import org.scalatest.{Matchers, WordSpecLike}

/**
  *
  * @author Dmitry Openkov
  */
class VolumeCalculatorSpec extends WordSpecLike with Matchers {
  "Volume Calculator" must {
    "calculate volume for links" in {
      val vc = new VolumeCalculator(10)
      val link1 = Id.createLinkId(1)
      vc.vehicleEntered(link1, 1.0)
      vc.vehicleEntered(link1, 1.0)
      vc.vehicleEntered(link1, 1.0)
      vc.vehicleEntered(link1, 2.0)
      vc.vehicleEntered(link1, 3.0)
      vc.vehicleEntered(link1, 4.0)
      vc.getVolume(link1, 4.0) should be(2160.0)
      vc.vehicleEntered(link1, 11.0)
      vc.getVolume(link1, 11.0) should be(1440.0)
    }
    "calculate volume for links with multiple events at the end" in {
      val vc = new VolumeCalculator(10)
      val link1 = Id.createLinkId(1)
      vc.vehicleEntered(link1, 1.0)
      vc.vehicleEntered(link1, 2.0)
      vc.vehicleEntered(link1, 3.0)
      vc.vehicleEntered(link1, 4.0)
      vc.vehicleEntered(link1, 4.0)
      vc.getVolume(link1, 4.0) should be(1800.0)
      vc.vehicleEntered(link1, 13.99)
      vc.getVolume(link1, 13.99) should be(1080.0)
      vc.vehicleEntered(link1, 15.0)
      vc.getVolume(link1, 15.0) should be(720.0)
    }
    "remove old events correctly" in {
      val vc = new VolumeCalculator(10)
      val link1 = Id.createLinkId(1)
      vc.vehicleEntered(link1, 1.0)
      vc.vehicleEntered(link1, 1.0)
      vc.vehicleEntered(link1, 1.0)
      vc.vehicleEntered(link1, 2.0)
      vc.vehicleEntered(link1, 3.0)
      vc.vehicleEntered(link1, 4.0)
      vc.getVolume(link1, 4.0) should be(2160.0)
      vc.vehicleEntered(link1, 10.0)
      vc.getVolume(link1, 10.0) should be(2520.0)
    }
    "work correctly with a larger time window" in {
      val vc = new VolumeCalculator(60)
      val link1 = Id.createLinkId(1)
      vc.vehicleEntered(link1, 1.0)
      vc.vehicleEntered(link1, 1.0)
      vc.vehicleEntered(link1, 1.0)
      vc.vehicleEntered(link1, 2.0)
      vc.vehicleEntered(link1, 3.0)
      vc.vehicleEntered(link1, 4.0)
      vc.getVolume(link1, 4.0) should be(360.0)
      vc.vehicleEntered(link1, 10.0)
      vc.getVolume(link1, 10.0) should be(420.0)
      vc.vehicleEntered(link1, 11.0)
      vc.getVolume(link1, 11.0) should be(480.0)
      vc.vehicleEntered(link1, 20.0)
      vc.getVolume(link1, 20.0) should be(540.0)
      vc.vehicleEntered(link1, 30.0)
      vc.getVolume(link1, 30.0) should be(600.0)
      vc.vehicleEntered(link1, 55.0)
      vc.getVolume(link1, 55.0) should be(660.0)
      vc.vehicleEntered(link1, 60.0)
      vc.getVolume(link1, 60.0) should be(480.0)
      vc.vehicleEntered(link1, 63.0)
      vc.getVolume(link1, 63.0) should be(420.0)
    }
    "calculate volume for the events in the past" in {
      val vc = new VolumeCalculator(10)
      val link1 = Id.createLinkId(1)
      vc.vehicleEntered(link1, 1.0)
      vc.vehicleEntered(link1, 1.0)
      vc.vehicleEntered(link1, 1.0)
      vc.vehicleEntered(link1, 2.0)
      vc.vehicleEntered(link1, 3.0)
      vc.vehicleEntered(link1, 4.0)
      vc.getVolume(link1, 4.0) should be(2160.0)
      vc.vehicleEntered(link1, 10.0)
      vc.getVolume(link1, 10.0) should be(2520.0)
      vc.vehicleEntered(link1, 5.0)
      vc.getVolume(link1, 5.0) should be(5040.0)
    }
  }

}
