package beam.calibration.utils

import com.sigopt.Sigopt
import com.sigopt.model.{Experiment, Pagination, Suggestion}

import scala.collection.JavaConverters._

object ListExperiments extends App {

  Sigopt.clientToken = SigOptApiToken.getClientAPIToken

  val experiments: Pagination[Experiment] = Experiment.list().call()

  for (experiment: Experiment <- experiments.getData.asScala) {

    val suggestions: Pagination[Suggestion] = experiment.suggestions().list().call()

    val data = suggestions.getData.asScala

    println(experiment.getId + ", " + experiment.getName)

    for (suggestion <- data) {
      println(suggestion.getId + " (" + suggestion.getState + ")")
    }

  }

}
