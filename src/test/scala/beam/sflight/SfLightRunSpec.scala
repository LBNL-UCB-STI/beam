package beam.sflight

import beam.integration.StartWithCustomConfig
import beam.sim.RunBeam
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

/**
  * Created by colinsheppard
  */

class SfLightRunSpec extends WordSpecLike with Matchers with RunBeam with BeforeAndAfterAll{

  "SF Light" ignore  {
    "run without error and at least one person chooses car mode" in {
      val sfLightRun = new StartWithCustomConfig(configFileToLoad = "test/input/sf-light/sf-light.conf")
      val carModeCount = sfLightRun.groupedCount.get("car").getOrElse(0)
      carModeCount should be > 0
    }
  }

}
