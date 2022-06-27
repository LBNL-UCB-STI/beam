package beam.sim

import beam.sim.config.BeamExecutionConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.core.scenario.MutableScenario
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths

class WritePlansAndStopSimulationTest extends AnyFlatSpec with Matchers with BeamHelper {
  it should "Stop simulation, trow runtime exception and write plans to the output folder." in {
    val config = ConfigFactory
      .parseString(s"""
           |beam.agentsim.lastIteration = 3
           |beam.outputs.events.fileOutputFormats = "xml,csv"
           |beam.outputs.writePlansInterval = 3
           |beam.physsim.writePlansInterval = 0
           |beam.output.writePlansAndStopSimulation = true
         """.stripMargin)
      .withFallback(testConfig("beam.sim.test/input/beamville/beam.conf"))
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
  }
}
