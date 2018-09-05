package beam.calibration.utils

import beam.calibration.utils.ListSuggestions.suggestions
import beam.utils.DebugLib
import com.sigopt.Sigopt
import com.sigopt.model.{Experiment, Pagination, Suggestion}
import scala.collection.JavaConverters._

object DeleteSuggestion {
  val deleteSuggestionId: String = "21226774"

  Sigopt.clientToken = SigOptApiToken.getClientAPIToken

  val experiment = new Experiment("51962")

  val suggestions: Pagination[Suggestion] = experiment.suggestions().list().call()

}
