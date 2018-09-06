package beam.calibration

import java.io.File

import beam.experiment.{ExperimentDef, ExperimentGenerator}
import beam.tags.Periodic
import com.sigopt.Sigopt
import com.sigopt.exception.APIConnectionError
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.util.{Failure, Success, Try}

class BeamSigoptTunerSpec extends WordSpecLike with Matchers with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    Sigopt.clientToken = Option { System.getenv("SIGOPT_DEV_API_TOKEN") }.getOrElse(
      throw new APIConnectionError(
        "Correct developer client token must be present in environment as SIGOPT_DEV_API Token"
      )
    )
  }

  val TEST_BEAM_EXPERIMENT_LOC = "test/input/beamville/example-calibration/experiment.yml"
  val TEST_BEAM_BENCHMARK_DATA_LOC = "test/input/beamville/example-calibration/benchmark.csv"

  val beamExperimentFile = new File(TEST_BEAM_EXPERIMENT_LOC)

  "BeamSigoptTuner" ignore {
    "create a proper experiment def from the test experiment specification file" taggedAs Periodic in {

      wrapWithTestExperiment { experimentData =>
        val header = experimentData.experimentDef.header
        header.title equals "Example-Experiment"
        header.beamTemplateConfPath equals "test/input/sf-light/sf-light-0.5k.conf"
      }
    }

    "create an experiment in the SigOpt API" taggedAs Periodic in {
      wrapWithTestExperiment { experimentData =>
        {
          val expParams = experimentData.experiment.getParameters
          // First is the rideHailParams
          val rideHailParams = expParams.iterator.next
          rideHailParams.getName equals "beam.agentsim.agents.rideHail.numDriversAsFractionOfPopulation"
          rideHailParams.getBounds.getMax equals 0.1
          rideHailParams.getBounds.getMin equals 0.001
          // Second is transitCapacityParams
          val transitCapacityParams = expParams.iterator.next
          transitCapacityParams.getName equals "beam.agentsim.agents.rideHail.numDriversAsFractionOfPopulation"
          transitCapacityParams.getBounds.getMax equals 0.1
          transitCapacityParams.getBounds.getMin equals 0.001
        }
      }
    }

    "create an experiment and run for 2 iterations" taggedAs Periodic in {
      wrapWithTestExperiment { implicit experimentData =>
        val runner = ExperimentRunner()
        runner.runExperiment(2)
      }
    }
  }

  private def wrapWithTestExperiment(experimentDataFunc: SigoptExperimentData => Any): Unit = {
    Try {
      SigoptExperimentData(
        ExperimentGenerator.loadExperimentDefs(beamExperimentFile),
        beamExperimentFile,
        TEST_BEAM_BENCHMARK_DATA_LOC,
        "None",
        development = true
      )
    } match {
      case Success(e) => experimentDataFunc(e)
      case Failure(t) => t.printStackTrace()
    }
  }
}
