package beam.calibration

import java.io.File

import beam.experiment.{ExperimentDef, ExperimentGenerator}
import beam.tags.Periodic
import com.sigopt.Sigopt
import com.sigopt.exception.APIConnectionError
import com.sigopt.model.Experiment
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.util.{Failure, Success, Try}

class BeamSigoptTunerTest extends WordSpecLike with Matchers with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    if (System.getenv("SIGOPT_DEV_ID") != null) Sigopt.clientToken = System.getenv("SIGOPT_DEV_ID")
    else throw new APIConnectionError("Correct developer client token must be present in environment as SIGOPT_DEV_ID")
  }

  val TEST_BEAM_EXPERIMENT_LOC = "test/input/beamville/example-experiment/experiment.yml"

  "BeamSigoptTuner" must {
    "create a proper experiment def from the test experiment specification file" taggedAs Periodic in {
      val beamExperimentFile = new File(TEST_BEAM_EXPERIMENT_LOC)
      val testExperimentDef: ExperimentDef = ExperimentGenerator.loadExperimentDefs(beamExperimentFile)
      val header = testExperimentDef.header
      header.title equals "Example-Experiment"
      header.beamTemplateConfPath equals "test/input/beamville/beam.conf"
    }

    "create an experiment in the SigOpt API" taggedAs Periodic in {
      wrapWithTestExperiment { experiment => {
        val expParams = experiment.getParameters
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


      "create a config based on assignments" taggedAs Periodic in {
        wrapWithTestExperiment { experiment =>

        }
      }
    }
  }

  private def wrapWithTestExperiment(experimentFunc: Experiment => AnyVal): Unit = {
    val beamExperimentFile = new File(TEST_BEAM_EXPERIMENT_LOC)
    val testExperimentDef = ExperimentGenerator.loadExperimentDefs(beamExperimentFile)
    Try {
      BeamSigoptTuner.createExperiment(SigoptExperimentData(testExperimentDef,beamExperimentFile))
    } match {
      case Success(e) => experimentFunc(e)
      case Failure(t) => t.printStackTrace()
    }
  }




}
