package beam.sim

import beam.sim.config.BeamExecutionConfig
import beam.utils.TestConfigUtils.testConfig
import beam.utils.csv.GenericCsvReader
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.core.scenario.MutableScenario
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths

class WritePlansAndStopSimulationTest extends AnyFlatSpec with Matchers with BeamHelper {

  def readActivitiesTypesFromPlan(pathToPlans: String): Set[String] =
    GenericCsvReader
      .readAsSeq[String](pathToPlans) { row => row.get("activityType") }
      .toSet

//  def readModesFromSelectedPlans(pathToPlans: String): Set[String] = {
//    Set.empty[String]
//  }

  def runSimulationAndCatchException(config: Config): String = {
    val (
      beamExecutionConfig: BeamExecutionConfig,
      scenario: MutableScenario,
      beamScenario: BeamScenario,
      services: BeamServices,
      plansMerged: Boolean
    ) = prepareBeamService(config, None)

    val exception: RuntimeException = intercept[RuntimeException] {
      runBeam(
        services,
        scenario,
        beamScenario,
        beamExecutionConfig.outputDirectory,
        plansMerged
      )
    }

    exception.getMessage shouldBe "The simulation was stopped because beam.output.writePlansAndStopSimulation set to true."

    beamExecutionConfig.outputDirectory
  }

  it should "Stop simulation, trow runtime exception and write plans with only Work and Home activities to the output folder." in {
    val config: Config = ConfigFactory
      .parseString(s"""
           |beam.agentsim.simulationName = "beamville_terminated_without_secondary_activities"
           |beam.agentsim.lastIteration = 3
           |beam.output.writePlansAndStopSimulation = true
           |beam.agentsim.agents.plans.inputPlansFilePath = "population-onlyWorkHome.xml"
           |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.generate_secondary_activities = false
           |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.fill_in_modes_from_skims = false
         """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

    val outputDirectory = runSimulationAndCatchException(config)

    val outputDir = Paths.get(outputDirectory).toFile
    val outputFiles = outputDir.listFiles()
    outputFiles.size shouldNot equal(0)
    outputFiles.map(_.getName) should contain("generatedPlans.csv.gz")
    val plansPath = Paths.get(outputDirectory, "generatedPlans.csv.gz").toFile
    val activitiesTypes = readActivitiesTypesFromPlan(plansPath.getPath)
    activitiesTypes shouldBe Set("Work", "Home")
  }

  it should "Stop simulation, trow runtime exception and write plans with different activities without modes to the output folder." in {
    val config = ConfigFactory
      .parseString(s"""
                      |beam.agentsim.simulationName = "beamville_terminated_with_secondary_activities_without_modes"
                      |beam.agentsim.lastIteration = 0
                      |beam.output.writePlansAndStopSimulation = true
                      |beam.agentsim.agents.plans.inputPlansFilePath = "population-onlyWorkHome.xml"
                      |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.generate_secondary_activities = true
                      |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.fill_in_modes_from_skims = false
         """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

    val outputDirectory = runSimulationAndCatchException(config)

    val plansPath = Paths.get(outputDirectory, "generatedPlans.csv.gz").toFile
    val activitiesTypes = readActivitiesTypesFromPlan(plansPath.getPath)
    activitiesTypes.contains("Work") shouldBe true
    activitiesTypes.contains("Home") shouldBe true
    activitiesTypes.size should be > 2
  }

  it should "Stop simulation, trow runtime exception and write plans with different activities with modes to the output folder." in {
    val config = ConfigFactory
      .parseString(s"""
                      |beam.agentsim.simulationName = "beamville_terminated_with_secondary_activities_with_modes"
                      |beam.agentsim.lastIteration = 0
                      |beam.output.writePlansAndStopSimulation = true
                      |beam.agentsim.agents.plans.inputPlansFilePath = "population-onlyWorkHome.xml"
                      |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.generate_secondary_activities = true
                      |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.fill_in_modes_from_skims = true
         """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

    val outputDirectory = runSimulationAndCatchException(config)

    val plansPath = Paths.get(outputDirectory, "generatedPlans.csv.gz").toFile

    val activitiesTypes = readActivitiesTypesFromPlan(plansPath.getPath)
    activitiesTypes.contains("Work") shouldBe true
    activitiesTypes.contains("Home") shouldBe true
    activitiesTypes.size should be > 2
  }
}
