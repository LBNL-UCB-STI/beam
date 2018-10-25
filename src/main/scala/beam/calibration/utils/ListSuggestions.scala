package beam.calibration.utils

import beam.utils.DebugLib
import com.sigopt.Sigopt
import com.sigopt.model.{Experiment, Pagination, Suggestion}

import scala.collection.JavaConverters._

object ListSuggestions extends App {

  Sigopt.clientToken = SigOptApiToken.getClientAPIToken

  val experiment = new Experiment("52783")

  val suggestions: Pagination[Suggestion] = experiment.suggestions().list().call()

  val data = suggestions.getData.asScala

  DebugLib.emptyFunctionForSettingBreakPoint()

  for (suggestion <- data) {
    println(suggestion)
    // TODO: Rajnikant: print separately open, closed
  }
}
