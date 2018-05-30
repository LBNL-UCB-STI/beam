package beam.calibration.impl.example

import java.net.URI

import beam.calibration.api.ObjectiveFunction
import beam.calibration.impl.example.ModeChoiceObjectiveFunction.ModeChoiceStats
import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser._
import org.apache.http.client.fluent.{Content, Request}

import scala.io.Source
import scala.util.Try


class ModeChoiceObjectiveFunction(benchmarkDataFileLoc: String) extends ObjectiveFunction {

  implicit val modeChoiceDataDecoder: Decoder[ModeChoiceStats] = deriveDecoder[ModeChoiceStats]

  override def evaluateFromRun(runDataFileLoc: String): Double = {
    val benchmarkData = if(benchmarkDataFileLoc.contains("http://")){
      getStatsFromMTC(new URI(runDataFileLoc))
    }else{
      getStatsFromFile(benchmarkDataFileLoc)
    }
    val runData = getStatsFromFile(runDataFileLoc)
    compareStats(benchmarkData,runData)
  }

  def compareStats(benchmarkData:Map[String,Double],runData: Map[String,Double]): Double={
    benchmarkData.map{case(mode,share)=> runData(mode)-share }.sum
  }

  def getStatsFromFile(fileLoc: String): Map[String, Double] = {
    Source.fromFile(fileLoc).getLines().drop(1).map{_.split(",")}.map(arr=>
      arr(0)->arr(1).toDouble).toMap
  }

  def getStatsFromMTC(mtcBenchmarkEndPoint: URI): Map[String, Double] = {
    (for {mtcContent <- getMTCContent(mtcBenchmarkEndPoint)
          parsedData <- parseMTCRequest(mtcContent)
          modeChoiceStatList <- jsonToModechoiceStats(parsedData)
    } yield {
      modeChoiceStatList.map { stat => stat.mode -> stat.share }.toMap
    }).get
  }

  def getMTCContent(benchmarkDataFileLoc:URI): Try[String] = {
    Try {
      val content: Content = Request.Get(benchmarkDataFileLoc).execute
        .returnContent
      content.asString()
    }
  }

  def parseMTCRequest(rawResponseData: String): Try[Json] = {
    parse(rawResponseData).toTry
  }

  def jsonToModechoiceStats(modechoiceJson: Json): Try[List[ModeChoiceStats]] = {
    Try{modechoiceJson.as[List[ModeChoiceStats]].right.get}
  }

}

object ModeChoiceObjectiveFunction {

  case class ModeChoiceStats(year: String, source: String, region: String, share: Double, mode: String, data_type: String)


}


