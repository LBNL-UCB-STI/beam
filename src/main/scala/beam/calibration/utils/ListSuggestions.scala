package beam.calibration.utils

import beam.utils.DebugLib
import com.sigopt.Sigopt
import com.sigopt.exception.APIConnectionError
import com.sigopt.model.{Experiment, Pagination, Suggestion}
import scala.collection.JavaConverters._

object ListSuggestions extends App {

  Sigopt.clientToken = SigOptApiToken.getClientAPIToken

  val experiment = new Experiment("51962")

  val suggestions: Pagination[Suggestion] = experiment.suggestions().list().call()

  val data = suggestions.getData().asScala

  DebugLib.emptyFunctionForSettingBreakPoint()

  for (suggestion <- data) {
    println(suggestion)
  }
}
