package beam.router.skim

import beam.router.skim.Skims.SkimType
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * @author Dmitry Openkov
  */
class SkimsSpec extends AnyFlatSpec with Matchers {
  "Skims" must "return all the skims except ActivitySimSkimmer to avoid loading AS skims during warm start" in {
    val beamConfig = BeamConfig(TestConfigUtils.testConfig("test/input/beamville/beam.conf").resolve)
    val skimCfg = beamConfig.beam.router.skim
    val skimFileNames = Skims.skimFileNames(skimCfg)
    skimFileNames should contain allOf (
      SkimType.OD_SKIMMER  -> "skimsOD",
      SkimType.DT_SKIMMER  -> "skimsTravelTimeObservedVsSimulated",
      SkimType.TC_SKIMMER  -> "skimsTransitCrowding",
      SkimType.TAZ_SKIMMER -> "skimsTAZ",
    )
    val (skimTypes, _) = skimFileNames.unzip
    skimTypes should not contain SkimType.AS_SKIMMER
  }
}
