package beam.integration

import beam.sim.BeamHelper
import beam.sim.config.BeamConfig
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.population.io.PopulationReader
import org.matsim.core.scenario.ScenarioUtils
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.collection.JavaConverters._
import scala.io.Source

/**
  * Created by zeeshan on 06/10/2018
  *
  */
class ReplanningExpBetaModeChoiceSpec
    extends WordSpecLike
    with BeforeAndAfterAll
    with Matchers
    with BeamHelper
    with IntegrationSpecCommon {

  val param = ConfigFactory.parseString(
    """
      |{
      | matsim.modules.strategy.parameterset = [
      |   {type = strategysettings, disableAfterIteration = -1, strategyName = ClearRoutes , weight = 0.0},
      |   {type = strategysettings, disableAfterIteration = -1, strategyName = ClearModes , weight = 0.2}
      |   {type = strategysettings, disableAfterIteration = -1, strategyName = TimeMutator , weight = 0.0},
      |   {type = strategysettings, disableAfterIteration = -1, strategyName = SelectExpBeta , weight = 0.8},
      | ]
      |}
    """.stripMargin
  )

  private lazy val config: Config = baseConfig
    .withValue("matsim.modules.strategy.maxAgentPlanMemorySize", ConfigValueFactory.fromAnyRef(4))
    .withValue("matsim.modules.strategy.Module_1", ConfigValueFactory.fromAnyRef("SelectExpBeta"))
    .withValue("matsim.modules.strategy.Module_2", ConfigValueFactory.fromAnyRef("ClearRoutes"))
    .withValue("matsim.modules.strategy.Module_3", ConfigValueFactory.fromAnyRef("ClearModes"))
    .withValue("matsim.modules.strategy.ModuleProbability_1", ConfigValueFactory.fromAnyRef(0.8))
    .withValue("matsim.modules.strategy.ModuleProbability_2", ConfigValueFactory.fromAnyRef(0.1))
    .withValue("matsim.modules.strategy.ModuleProbability_3", ConfigValueFactory.fromAnyRef(0.1))
    .withValue("matsim.modules.controler.lastIteration", ConfigValueFactory.fromAnyRef(10))
    .withFallback(param)
    .resolve()

  lazy val beamConfig = BeamConfig(config)
  var matsimConfig: org.matsim.core.config.Config = _

  override protected def beforeAll(): Unit = {
    matsimConfig = runBeamWithConfig(config)._1
  }

  "Replanning ExpBeta" must {
    "increase plans over iterations" in {
      lazy val it0PlansCount = getTotalPlans(0)
      lazy val it5PlansCount = getTotalPlans(5)
      lazy val it10PlansCount = getTotalPlans(10)

      it0PlansCount should be < it5PlansCount
      it5PlansCount should be < it10PlansCount
    }

  }

  private def getTotalPlans(iterationNum: Int) = {
    val scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig())
    new PopulationReader(scenario).readFile(
      s"${matsimConfig.controler().getOutputDirectory}/ITERS/it.$iterationNum/$iterationNum.plans.xml.gz"
    )

    scenario.getPopulation.getPersons.values().asScala.map(_.getPlans.size()).sum
  }

  private def getAvgAvgScore(iterationNum: Int) = {
    val bufferedSource = Source.fromFile(s"${matsimConfig.controler().getOutputDirectory}/scorestats.txt")
    val itScores = bufferedSource.getLines.toList.find(_.startsWith(s"$iterationNum\t"))
    bufferedSource.close

    itScores.flatMap(_.split("\t").map(_.trim).lift(3).map(_.toDouble))
  }
  private def getAvgBestScore(iterationNum: Int) = {
    val bufferedSource = Source.fromFile(s"${matsimConfig.controler().getOutputDirectory}/scorestats.txt")
    val itScores = bufferedSource.getLines.toList.find(_.startsWith(s"$iterationNum\t"))
    bufferedSource.close

    itScores.flatMap(_.split("\t").map(_.trim).lift(4).map(_.toDouble))
  }
}
