package beam.calibration.utils

import beam.calibration.BeamSigoptTuner
import com.sigopt.Sigopt
import com.sigopt.model.{Experiment, Pagination, Suggestion}
import com.typesafe.scalalogging.LazyLogging

import scala.collection.JavaConverters._

object DeleteSuggestion extends LazyLogging {
  val deleteSuggestionId: String = "21226774"

  Sigopt.clientToken = SigOptApiToken.getClientAPIToken

  val experiment = new Experiment("51962")

  val suggestions: Pagination[Suggestion] = experiment.suggestions().list().call()

  def deleteSuggestion(experimentId: String, suggestionId: String): Unit = {
    BeamSigoptTuner.fetchExperiment(experimentId) match {
      case Some(_experiment) =>
        _experiment.suggestions().delete(suggestionId).call()
      case None =>
        logger.info(s"Experiment with id $experimentId not found")
    }
  }

  def deleteSuggestions(experimentId: String, suggestions: List[String]): Unit = {
    BeamSigoptTuner.fetchExperiment(experimentId) match {
      case Some(_experiment) =>
        suggestions.foreach { suggestionId =>
          _experiment.suggestions().delete(suggestionId).call()
        }
      case None =>
        logger.info(s"Experiment with id $experimentId not found")
    }
  }

  def listSuggestions(experimentId: String): Unit = {
    BeamSigoptTuner.fetchExperiment(experimentId) match {
      case Some(_experiment) =>
        val data = _experiment.suggestions().list().call().getData.asScala
        data.foreach(println)
        if (data.isEmpty) {
          logger.info(s"Experiment with id $experimentId has no suggestion")
        }
      case None =>
        logger.info(s"Experiment with id $experimentId not found")
    }
  }

  def deleteAllOpenSuggestions(experimentId: String): Unit = {
    BeamSigoptTuner.fetchExperiment(experimentId) match {
      case Some(_experiment) =>
        val data = _experiment.suggestions().list().call().getData.asScala
        if (data.isEmpty) {
          logger.info(s"Experiment with id $experimentId has no suggestion")
        }
        data.filter(_.getState == "open").foreach { d =>
          logger.info("DELETING SUGGESTION ID ({}) - {}", d.getId, d)
          _experiment.suggestions().delete(d.getId).call()
        }
      case None =>
        logger.info(s"Experiment with id $experimentId not found")
    }
  }

  def main(args: Array[String]): Unit = {

    //    val experimentId = "52024"
    //    val suggestionId = "21233364";
    //    listSuggestions(experimentId)
    //    deleteSuggestion(experimentId, suggestionId)
    //    listSuggestions(experimentId)

    ///
    val experimentId = "52783"
    listSuggestions(experimentId)
    deleteAllOpenSuggestions(experimentId)

  }
}
