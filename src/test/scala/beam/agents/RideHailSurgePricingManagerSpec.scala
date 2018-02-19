package beam.agents

import beam.agentsim.agents.RideHailSurgePricingManager
import beam.agentsim.infrastructure.TAZTreeMap
import beam.sim.config.BeamConfig
import beam.utils.BeamConfigUtils
import org.scalatest.{Matchers, WordSpecLike}

class RideHailSurgePricingManagerSpec extends WordSpecLike with Matchers{

  val testConfigFileName = "test/input/beamville/beam.conf"
  val config = BeamConfigUtils.parseFileSubstitutingInputDirectory(testConfigFileName)

  val beamConfig: BeamConfig = BeamConfig(config)
  val treeMap: TAZTreeMap = TAZTreeMap.fromCsv(beamConfig.beam.agentsim.taz.file)

  "RideHailSurgePricingManager" must {
    "be correctly initialized" in {
      val rhspm = new RideHailSurgePricingManager(beamConfig, treeMap)
      rhspm.surgePriceBins.size should have size treeMap.tazQuadTree.size()
    }
  }

}
