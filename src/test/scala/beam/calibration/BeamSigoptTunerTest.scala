package beam.calibration

import java.io.File

import beam.experiment.ExperimentGenerator
import beam.tags.Periodic
import com.sigopt.Sigopt
import com.sigopt.exception.APIConnectionError
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.util.{Failure, Success, Try}

class BeamSigoptTunerTest extends WordSpecLike with Matchers with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    if (System.getenv("SIGOPT_DEV_ID") != null) Sigopt.clientToken = System.getenv("SIGOPT_DEV_ID")
    else throw new APIConnectionError("Correct developer client token must be present in environment as SIGOPT_DEV_ID")
  }

  val TEST_BEAM_EXPERIMENT_LOC = "test/input/beamville/example-experiment/experiment.yml"
  val beamExperimentFile = new File(TEST_BEAM_EXPERIMENT_LOC)



  "BeamSigoptTuner" must {
    "create a proper experiment def from the test experiment specification file" taggedAs Periodic in {

     wrapWithTestExperiment { experimentData =>
        val header = experimentData.experimentDef.header
        header.title equals "Example-Experiment"
        header.beamTemplateConfPath equals "test/input/beamville/beam.conf"
      }
    }

    "create an experiment in the SigOpt API" taggedAs Periodic in {
      wrapWithTestExperiment { experimentData => {
        val expParams = experimentData.experiment.getParameters
        // First is the rideHailParams
        val rideHailParams = expParams.iterator.next
        rideHailParams.getName equals "beam.agentsim.agents.rideHailing.numDriversAsFractionOfPopulation"
        rideHailParams.getBounds.getMax equals 0.1
        rideHailParams.getBounds.getMin equals 0.001
        // Second is transitCapacityParams
        val transitCapacityParams = expParams.iterator.next
        transitCapacityParams.getName equals "beam.agentsim.agents.rideHailing.numDriversAsFractionOfPopulation"
        transitCapacityParams.getBounds.getMax equals 0.1
        transitCapacityParams.getBounds.getMin equals 0.001
      }
      }
    }
      "create a config based on assignments" taggedAs Periodic in {
        wrapWithTestExperiment { implicit experimentData =>
          val suggestion = experimentData.experiment.suggestions.create.call
          val runner = ExperimentRunner()
          val newRunConfig = runner.createConfigBasedOnSuggestion(suggestion)

        }
      }

  }

  private def wrapWithTestExperiment(experimentDataFunc: SigoptExperimentData => Any): Unit = {
    Try {SigoptExperimentData(ExperimentGenerator.loadExperimentDefs(beamExperimentFile), beamExperimentFile)} match {
      case Success(e) => experimentDataFunc(e)
      case Failure(t) => t.printStackTrace()
    }
  }


}
