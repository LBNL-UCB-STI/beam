package beam.agentsim.agents.choice.logit

import beam.agentsim.agents.choice.logit.LatentClassChoiceModel.{LccmData, Mandatory, NonMandatory, TourType}
import beam.sim.BeamServices
import org.matsim.core.utils.io.IOUtils
import org.supercsv.cellprocessor.constraint.NotNull
import org.supercsv.cellprocessor.ift.CellProcessor
import org.supercsv.cellprocessor.{Optional, ParseDouble}
import org.supercsv.io.CsvBeanReader
import org.supercsv.prefs.CsvPreference

import scala.beans.BeanProperty
import scala.collection.mutable

class LatentClassChoiceModel(val beamServices: BeamServices) {

  private val lccmData: Seq[LccmData] = parseModeChoiceParams(
    beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.lccm.filePath
  )

  val classMembershipModels: Map[TourType, MultinomialLogit[String, String]] = extractClassMembershipModels(
    lccmData
  )

  val modeChoiceModels: Map[TourType, Map[String, MultinomialLogit[String, String]]] = {
    extractModeChoiceModels(lccmData)
  }

  private def parseModeChoiceParams(lccmParamsFileName: String): Seq[LccmData] = {
    val beanReader = new CsvBeanReader(
      IOUtils.getBufferedReader(lccmParamsFileName),
      CsvPreference.STANDARD_PREFERENCE
    )
    val firstLineCheck = true
    val header = beanReader.getHeader(firstLineCheck)
    val processors: Array[CellProcessor] = LatentClassChoiceModel.getProcessors

    val result = mutable.ArrayBuffer[LccmData]()
    var row: LccmData = newEmptyRow()
    while (beanReader.read[LccmData](row, header, processors: _*) != null) {
      if (Option(row.value).isDefined && !row.value.isNaN)
        result += row.clone().asInstanceOf[LccmData]
      row = newEmptyRow()
    }
    result
  }

  private def newEmptyRow(): LccmData = new LccmData()

  private def extractClassMembershipModels(
    lccmData: Seq[LccmData]
  ): Map[TourType, MultinomialLogit[String, String]] = {
    val classMemData = lccmData.filter(_.model == "classMembership")
    Vector[TourType](Mandatory, NonMandatory).map { theTourType =>
      val theData = classMemData.filter(_.tourType.equalsIgnoreCase(theTourType.toString))
      val utilityFunctions: Iterable[(String, Map[String, UtilityFunctionOperation])] = for {
        data          <- theData
        alternativeId <- data.alternative
      } yield {
        (alternativeId.toString, Map(data.variable -> UtilityFunctionOperation(data.variable, data.value)))
      }
      theTourType -> MultinomialLogit(utilityFunctions.toMap)
    }.toMap
  }

  /*
   * We use presence of ASC to indicate whether an alternative should be added to the MNL model. So even if an alternative is a base alternative,
   * it should be given an ASC with value of 0.0 in order to be added to the choice set.
   */
  def extractModeChoiceModels(
    lccmData: Seq[LccmData]
  ): Map[TourType, Map[String, MultinomialLogit[String, String]]] = {
    val uniqueClasses = lccmData.map(_.latentClass).distinct
    val modeChoiceData = lccmData.filter(_.model == "modeChoice")
    Vector[TourType](Mandatory, NonMandatory).map { theTourType: TourType =>
      val theTourTypeData = modeChoiceData.filter(_.tourType.equalsIgnoreCase(theTourType.toString))
      theTourType -> uniqueClasses.map { theLatentClass =>
        val theData = theTourTypeData.filter(_.latentClass.equalsIgnoreCase(theLatentClass))

        val utilityFunctions: Iterable[(String, Map[String, UtilityFunctionOperation])] = for {
          data          <- theData
          alternativeId <- data.alternative
        } yield {
          (alternativeId.toString, Map(data.variable -> UtilityFunctionOperation(data.variable, data.value)))
        }

        theLatentClass -> MultinomialLogit(
          utilityFunctions.toMap
        )
      }.toMap
    }.toMap
  }

}

object LatentClassChoiceModel {

  private def getProcessors: Array[CellProcessor] = {
    Array[CellProcessor](
      new NotNull, // model
      new NotNull, // tourType
      new NotNull, // variable
      new NotNull, // alternative
      new NotNull, // units
      new Optional, // latentClass
      new Optional(new ParseDouble()) // value
    )
  }

  class LccmData(
    @BeanProperty var model: String = "",
    @BeanProperty var tourType: String = "",
    @BeanProperty var variable: String = "",
    @BeanProperty var alternative: String = "",
    @BeanProperty var units: String = "",
    @BeanProperty var latentClass: String = "",
    @BeanProperty var value: Double = Double.NaN
  ) extends Cloneable {
    override def clone(): AnyRef =
      new LccmData(model, tourType, variable, alternative, units, latentClass, value)
  }

  sealed trait TourType

  case object Mandatory extends TourType

  case object NonMandatory extends TourType
}
