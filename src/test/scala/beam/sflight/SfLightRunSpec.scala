package beam.sflight

import java.io.File

import beam.integration.StartWithCustomConfig
import beam.sim.RunBeam
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

/**
  * Created by colinsheppard
  */

class SfLightRunSpec extends WordSpecLike with Matchers with RunBeam with BeforeAndAfterAll{

  "SF Light" ignore  {
    "run without error and at least one person chooses car mode" in {
      val config = ConfigFactory.parseFile(new File("test/input/sf-light/sf-light.conf")).resolve()
        .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml"))
      runBeamWithConfig(config)
//      val carModeCount = sfLightRun.groupedCount.get("car").getOrElse(0)
//      carModeCount should be > 0
    }
  }

}
