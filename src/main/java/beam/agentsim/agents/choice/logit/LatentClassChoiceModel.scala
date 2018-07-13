package beam.agentsim.agents.choice.logit

import beam.agentsim.agents.choice.logit.LatentClassChoiceModel.{
  LccmData,
  Mandatory,
  Nonmandatory,
  TourType
}
import beam.agentsim.agents.choice.logit.MultinomialLogit.MnlData
import beam.sim.{BeamServices, HasServices}
import org.matsim.core.utils.io.IOUtils
import org.supercsv.cellprocessor.constraint.NotNull
import org.supercsv.cellprocessor.ift.CellProcessor
import org.supercsv.cellprocessor.{Optional, ParseDouble}
import org.supercsv.io.CsvBeanReader
import org.supercsv.prefs.CsvPreference

import scala.beans.BeanProperty
import scala.collection.mutable

/**
  * BEAM
  */
class LatentClassChoiceModel(override val beamServices: BeamServices) extends HasServices {
  private val lccmData: IndexedSeq[LccmData] = parseModeChoiceParams(
    beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.lccm.paramFile
  )

  val classMembershipModels: Map[TourType, MultinomialLogit] = extractClassMembershipModels(
    lccmData
  )

  val modeChoiceModels: Map[TourType, Map[String, MultinomialLogit]] = {
    val mods = extractModeChoiceModels(lccmData)
    mods
  }

  def parseModeChoiceParams(lccmParamsFileFile: String): IndexedSeq[LccmData] = {
    val beanReader = new CsvBeanReader(
      IOUtils.getBufferedReader(lccmParamsFileFile),
      CsvPreference.STANDARD_PREFERENCE
    )
    val header = beanReader.getHeader(true)
    val processors: Array[CellProcessor] = LatentClassChoiceModel.getProcessors

    var row: LccmData = new LccmData()
    val data = mutable.ArrayBuffer[LccmData]()
    while (beanReader.read[LccmData](row, header, processors: _*) != null) {
      if (Option(row.value).isDefined && !row.value.isNaN)
        data += row.clone().asInstanceOf[LccmData]
      row = new LccmData() // Turns out that if beanReader encounters a missing field, it just doesn't do the setField() rather than set it to null.... so we need to mannualy reset after every read
    }
    data
  }

  def extractClassMembershipModels(
    lccmData: IndexedSeq[LccmData]
  ): Map[TourType, MultinomialLogit] = {
    val classMemData = lccmData.filter(_.model == "classMembership")
    Vector[TourType](Mandatory, Nonmandatory).map { theTourType =>
      val theData = classMemData.filter(_.tourType.equalsIgnoreCase(theTourType.toString))

      val mnlData = theData.map { theDat =>
        new MnlData(
          theDat.alternative,
          theDat.variable,
          if (theDat.variable.equalsIgnoreCase("asc")) { "intercept" } else { "multiplier" },
          theDat.value
        )
      }

      theTourType -> MultinomialLogit(mnlData)
    }.toMap
  }

  /*
   * We use presence of ASC to indicate whether an alternative should be added to the MNL model. So even if an alternative is a base alterantive,
   * it should be given an ASC with value of 0.0 in order to be added to the choice set.
   */
  def extractModeChoiceModels(
    lccmData: IndexedSeq[LccmData]
  ): Map[TourType, Map[String, MultinomialLogit]] = {
    val uniqueClasses = lccmData.map(_.latentClass).distinct
    val modeChoiceData = lccmData.filter(_.model == "modeChoice")
    Vector[TourType](Mandatory, Nonmandatory).map { theTourType =>
      val theTourTypeData = modeChoiceData.filter(_.tourType.equalsIgnoreCase(theTourType.toString))
      theTourType -> uniqueClasses.map { theLatentClass =>
        val theData = theTourTypeData.filter(_.latentClass.equalsIgnoreCase(theLatentClass))
        val mnlData = theData.map { theDat =>
          new MnlData(
            theDat.alternative,
            theDat.variable,
            if (theDat.variable.equalsIgnoreCase("asc")) { "intercept" } else { "multiplier" },
            theDat.value
          )
        }
        val altsToInclude =
          mnlData.filter(_.paramName.equalsIgnoreCase("asc")).map(_.alternative).distinct
        theLatentClass -> MultinomialLogit(
          mnlData.filter(mnlRow => altsToInclude.contains(mnlRow.alternative))
        )
      }.toMap
    }.toMap
  }

}

object LatentClassChoiceModel {

  sealed trait TourType

  case object Mandatory extends TourType

  case object Nonmandatory extends TourType

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

  import org.supercsv.cellprocessor.ift.CellProcessor

  private def getProcessors = {
    Array[CellProcessor](
      new NotNull, // model
      new NotNull, // tourType
      new NotNull, // variable
      new NotNull, // alternative
      new NotNull, // untis
      new Optional, // latentClass
      new Optional(new ParseDouble()) // value
    )
  }
}
