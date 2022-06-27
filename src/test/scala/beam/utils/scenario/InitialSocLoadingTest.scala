package beam.utils.scenario

import akka.actor._
import akka.testkit.TestKitBase
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.sflight.RouterForTest
import beam.utils.SimRunnerForTest
import beam.utils.TestConfigUtils.testConfig
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import org.scalatest.LoneElement.convertMapToCollectionLoneElementWrapper
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.language.postfixOps

class InitialSocLoadingTest
    extends AnyWordSpecLike
    with TestKitBase
    with SimRunnerForTest
    with RouterForTest
    with Matchers {

  def config: com.typesafe.config.Config = testConfig("beam.sim.test/input/beamville/beam.conf").resolve()

  def outputDirPath: String = basePath + "/" + testOutputDir + "soc_loading"

  lazy implicit val system: ActorSystem = ActorSystem("soc_loading", config)

  "Scenario loader" must {
    "load correct SoC levels for vehicles" in {
      beamScenario.privateVehicleInitialSoc should contain theSameElementsAs Map(
        "3".createId[BeamVehicle] -> 0.765,
        "4".createId[BeamVehicle] -> 0.9
      )
    }
  }
}
