package beam.sim

import beam.sim.config.BeamExecutionConfig
import beam.utils.TestConfigUtils.testConfig
import beam.utils.csv.GenericCsvReader
import com.typesafe.config.ConfigFactory
import org.matsim.core.scenario.MutableScenario
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sourcecode.File

import java.nio.file.Paths

class WritePlansAndStopSimulationTest extends AnyFlatSpec with Matchers with BeamHelper {

  def readActivitiesTypesFromPlan(path: String): Set[String] =
    GenericCsvReader
      .readAsSeq[String](path) { row => row.get("activityType") }
      .toSet

  it should "Stop simulation, trow runtime exception and write plans with only Work and Home activities to the output folder." in {
    val config = ConfigFactory
      .parseString(s"""
           |beam.agentsim.lastIteration = 3
           |beam.output.writePlansAndStopSimulation = true
           |beam.agentsim.agents.plans.inputPlansFilePath = "population-onlyWorkHome.xml"
           |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.generate_secondary_activities = false
           |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.fill_in_modes_from_skims = false
         """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

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

    val outputDir = Paths.get(beamExecutionConfig.outputDirectory).toFile
    val outputFiles = outputDir.listFiles()
    outputFiles.size shouldNot equal(0)
    outputFiles.map(_.getName) should contain("generatedPlans.csv.gz")
    val plansPath = Paths.get(beamExecutionConfig.outputDirectory, "generatedPlans.csv.gz").toFile
    val activitiesTypes = readActivitiesTypesFromPlan(plansPath.getPath)
    activitiesTypes shouldBe Set("Work", "Home")
  }

  it should "Write plans with generated secondary activities." in {
    val config = ConfigFactory
      .parseString(s"""
                      |beam.agentsim.lastIteration = 0
                      |beam.output.writePlansAndStopSimulation = true
                      |beam.agentsim.agents.plans.inputPlansFilePath = "population-onlyWorkHome.xml"
                      |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.generate_secondary_activities = true
                      |beam.agentsim.agents.tripBehaviors.mulitnomialLogit.fill_in_modes_from_skims = true
         """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

    val (
      beamExecutionConfig: BeamExecutionConfig,
      scenario: MutableScenario,
      beamScenario: BeamScenario,
      services: BeamServices,
      plansMerged: Boolean
    ) = prepareBeamService(config, None)

    val _ = intercept[RuntimeException] {
      runBeam(
        services,
        scenario,
        beamScenario,
        beamExecutionConfig.outputDirectory,
        plansMerged
      )
    }

    val outputDir = Paths.get(beamExecutionConfig.outputDirectory).toFile
    val outputFiles = outputDir.listFiles()
    outputFiles.size shouldNot equal(0)
    outputFiles.map(_.getName) should contain("generatedPlans.csv.gz")
    val plansPath = Paths.get(beamExecutionConfig.outputDirectory, "generatedPlans.csv.gz").toFile
    val activitiesTypes = readActivitiesTypesFromPlan(plansPath.getPath)
    activitiesTypes.contains("Work") shouldBe true
    activitiesTypes.contains("Home") shouldBe true
    activitiesTypes.size should be > 2
  }
}
