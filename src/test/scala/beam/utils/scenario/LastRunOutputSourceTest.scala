package beam.utils.scenario

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.Paths

class LastRunOutputSourceTest extends AnyWordSpecLike with Matchers {

  "LastRunOutputSource findLastRunOutputPlans" should {
    "return None when no such path exists" in {
      val path = LastRunOutputSource.findLastRunOutputPlans(Paths.get("/not_existing_path"), "")

      path should be(None, None)
    }

    "return None when path exists but no plan files inside" in {
      val path = LastRunOutputSource.findLastRunOutputPlans(Paths.get("test/test-resources/beam/agentsim"), "")

      path should be(None, None)
    }

    "find path when last iteration path path when csv plans exist" in {
      val expectedPath = Paths.get("test/test-resources/beam/agentsim/plans/beamville_1__1/ITERS/it.2/2.plans.csv.gz")
      val outputPath = Paths.get("test/test-resources/beam/agentsim/plans")

      val path = LastRunOutputSource.findLastRunOutputPlans(outputPath, "beamville_1")

      path should be(Some(expectedPath), None)
    }

    "find path when last iteration path path when xml plans exist" in {
      val expectedExperiencedPath =
        Paths.get("test/test-resources/beam/agentsim/plans/beamville__2/ITERS/it.3/3.experienced_plans.xml.gz")
      val expectedOutputPath = Paths.get("test/test-resources/beam/agentsim/plans/beamville__2/output_plans.xml.gz")
      val outputPath = Paths.get("test/test-resources/beam/agentsim/plans")

      val path = LastRunOutputSource.findLastRunOutputPlans(outputPath, "beamville__")

      path should be(Some(expectedOutputPath), Some(expectedExperiencedPath))
    }
  }
}
