package beam.calibration

import java.io.File
import beam.experiment.ExperimentApp
import beam.tags.Periodic
import com.sigopt.Sigopt
import com.sigopt.exception.APIConnectionError
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers

import scala.util.{Failure, Success, Try}

class BeamSigoptTunerSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll with LazyLogging {

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    Sigopt.clientToken = Option {
      System.getenv("SIGOPT_DEV_API_TOKEN")
    }.getOrElse(
      throw new APIConnectionError(
        "Correct developer client token must be present in environment as SIGOPT_DEV_API Token "
      )
    )
  }

  val TEST_BEAM_EXPERIMENT_LOC = "test/input/beamville/example-calibration/experiment.yml"
  val TEST_BEAM_BENCHMARK_DATA_LOC = "test/input/beamville/example-calibration/benchmark.csv"

  "BeamSigoptTuner" ignore {
    "create a proper experiment def from the test experiment specification file" taggedAs Periodic in {

      wrapWithTestExperiment { experimentData =>
        val header = experimentData.experimentDef.header
        header.title shouldEqual "Example-Experiment"
        header.beamTemplateConfPath shouldEqual "test/input/sf-light/sf-light-0.5k.conf"
      }
    }

    "create an experiment in the SigOpt API" taggedAs Periodic in {
      wrapWithTestExperiment { experimentData =>
        {
          val expParams = experimentData.experiment.getParameters
          // First is the rideHailParams
          val rideHailParams = expParams.iterator.next
          rideHailParams.getName shouldEqual "beam.agentsim.agents.rideHail.initialization.procedural.numDriversAsFractionOfPopulation"
          rideHailParams.getBounds.getMax shouldEqual 0.1
          rideHailParams.getBounds.getMin shouldEqual 0.001
          // Second is transitCapacityParams
          val transitCapacityParams = expParams.iterator.next
          transitCapacityParams.getName shouldEqual "beam.agentsim.agents.rideHail.initialization.procedural.numDriversAsFractionOfPopulation"
          transitCapacityParams.getBounds.getMax shouldEqual 0.1
          transitCapacityParams.getBounds.getMin shouldEqual 0.001
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
      val file = new File(TEST_BEAM_BENCHMARK_DATA_LOC)
      SigoptExperimentData(
        ExperimentApp.loadExperimentDefs(file),
        TEST_BEAM_BENCHMARK_DATA_LOC,
        "None",
        development = true
      )
    } match {
      case Success(e) => experimentDataFunc(e)
      case Failure(t) => logger.error("exception occurred due to ", t)
    }
  }
}
