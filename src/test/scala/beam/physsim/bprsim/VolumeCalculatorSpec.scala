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
      val vc = new VolumeCalculator(60, 60)
      val link1 = Id.createLinkId(1)
      val link2 = Id.createLinkId(2)
      vc.vehicleEntered(link1, 1.0)
      vc.vehicleEntered(link1, 1.0)
      vc.vehicleEntered(link1, 1.0)
      vc.vehicleEntered(link1, 2.0)
      vc.vehicleEntered(link1, 3.0)
      vc.vehicleEntered(link1, 4.0)
      vc.vehicleEntered(link2, 1.0)
      vc.vehicleEntered(link2, 2.0)
      vc.vehicleEntered(link2, 3.0)
      vc.vehicleEntered(link2, 4.0)
      vc.vehicleEntered(link2, 4.0)
      vc.getVolume(link1, 4.0) should be(360.0 +- 0.01)
      vc.getVolume(link1, 61.0) should be(120.0 +- 0.01)
      vc.getVolume(link2, 4.0) should be(300.0 +- 0.01)
      vc.getVolume(link2, 62.0) should be(180.0 +- 0.01)
      vc.getVolume(link2, 63.0) should be(0.0 +- 0.01)
    }
  }

}
