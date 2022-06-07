package beam.sim

import beam.sim.config.BeamExecutionConfig
import beam.utils.TestConfigUtils.testConfig
import beam.utils.csv.GenericCsvReader
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.scenario.MutableScenario
import org.scalatest.AppendedClues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LookForClosestHourInODSkims extends AnyFlatSpec with Matchers with BeamHelper with AppendedClues {

  def readLegModesCountFromPlan(pathToPlans: String): Map[String, Int] = {
    val modes = GenericCsvReader
      .readAsSeq[(Boolean, String)](pathToPlans) { row => (row.get("planSelected").toBoolean, row.get("legMode")) }
      .filter { case (planSelected, legMode) => planSelected && legMode != null }
      .map { case (_, legMode) => legMode }

    modes.groupBy(identity).mapValues(_.size)
  }

  def getPathToPlans(outputDir: String, iterationNumber: Int): String = {
    val outputDirectoryHierarchy =
      new OutputDirectoryHierarchy(outputDir, OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles)
    outputDirectoryHierarchy.getIterationFilename(iterationNumber, "plans.csv.gz")
  }

  def runSimulationAndGetOutputDirectory(config: Config): String = {
    val (
      beamExecutionConfig: BeamExecutionConfig,
      scenario: MutableScenario,
      beamScenario: BeamScenario,
      services: BeamServices,
      plansMerged: Boolean
    ) = prepareBeamService(config, None)

    runBeam(
      services,
      scenario,
      beamScenario,
      beamExecutionConfig.outputDirectory,
      plansMerged
    )

    beamExecutionConfig.outputDirectory
  }

  it should "Create certain percentage of RH legs in plans without look_for_closest_hour." in {
    val config: Config = ConfigFactory
      .parseString(s"""
           |beam.agentsim.simulationName = "beamville_without_look_for_closest_hour"
           |beam.agentsim.lastIteration = 0
           |beam.agentsim.agents.plans.inputPlansFilePath = "population-onlyWorkHome.xml"
           |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.generate_secondary_activities = true
           |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.fill_in_modes_from_skims = true
           |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.look_for_closest_hour_in_OD_skims_for_fill_in_modes = false
           |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.min_hour_for_closest_hour_in_OD_skims_request = 3
           |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.max_hour_for_closest_hour_in_OD_skims_request = 5
           |beam.warmStart.type = "full"
           |beam.warmStart.path = "test/test-resources/warmstart_beamville_OD_rh_only_hour_4.zip"
         """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

    val outputDirectory = runSimulationAndGetOutputDirectory(config)
    val plansPath = getPathToPlans(outputDirectory, 0)
    val legModesCount = readLegModesCountFromPlan(plansPath)
    val allModesToRideHailRatio: Double = legModesCount.getOrElse("ride_hail", 0) / legModesCount.values.sum.toDouble
    allModesToRideHailRatio should be < 0.2
  }

  it should "Create certain percentage of RH legs in plans with look_for_closest_hour" in {
    val config: Config = ConfigFactory
      .parseString(s"""
                      |beam.agentsim.simulationName = "beamville_without_look_for_closest_hour"
                      |beam.agentsim.lastIteration = 0
                      |beam.agentsim.agents.plans.inputPlansFilePath = "population-onlyWorkHome.xml"
                      |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.generate_secondary_activities = true
                      |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.fill_in_modes_from_skims = true
                      |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.look_for_closest_hour_in_OD_skims_for_fill_in_modes = true
                      |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.min_hour_for_closest_hour_in_OD_skims_request = 3
                      |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.max_hour_for_closest_hour_in_OD_skims_request = 5
                      |beam.warmStart.type = "full"
                      |beam.warmStart.path = "test/test-resources/warmstart_beamville_OD_rh_only_hour_4.zip"
         """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

    val outputDirectory = runSimulationAndGetOutputDirectory(config)
    val plansPath = getPathToPlans(outputDirectory, 0)
    val legModesCount = readLegModesCountFromPlan(plansPath)
    val allModesToRideHailRatio: Double = legModesCount.getOrElse("ride_hail", 0) / legModesCount.values.sum.toDouble
    allModesToRideHailRatio should be > 0.7
  }
}
