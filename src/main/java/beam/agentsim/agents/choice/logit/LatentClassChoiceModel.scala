package beam.agentsim.agents.choice.logit

import java.util

import beam.agentsim.agents.choice.logit.LatentClassChoiceModel.{LccmData, Mandatory, Nonmandatory, TourType}
import beam.sim.{BeamServices, HasServices}
import org.matsim.core.utils.io.IOUtils
import org.supercsv.cellprocessor.{Optional, ParseDouble}
import org.supercsv.cellprocessor.ift.CellProcessor

import scala.xml.XML
import org.supercsv.io.{CsvBeanReader, CsvMapReader}
import org.supercsv.prefs.CsvPreference
import org.supercsv.cellprocessor.constraint.NotNull

import scala.beans.BeanProperty
import collection.JavaConverters._

/**
  * BEAM
  */
class LatentClassChoiceModel(override val beamServices: BeamServices) extends HasServices with Cloneable {

  val lccmData = parseModeChoiceParams(beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceParametersFile)

  val classMembershipModels: Map[TourType,MulitnomialLogit] = extractClassMembershipModels(lccmData)
  val modeChoiceModels: Map[TourType,Map[String,MulitnomialLogit]] = {
    val mods = extractModeChoiceModels(lccmData)
    mods
  }

  def parseModeChoiceParams(modeChoiceParamsFilePath: String): Vector[LccmData] = {
    val params = XML.loadFile(modeChoiceParamsFilePath)
    val paramsFile = s"${beamServices.beamConfig.beam.inputDirectory}/${(params \\ "modeChoices" \\ "lccm" \\ "parameters").text}"

    val beanReader = new CsvBeanReader(IOUtils.getBufferedReader(paramsFile), CsvPreference.STANDARD_PREFERENCE)
    val header = beanReader.getHeader(true)
    val processors: Array[CellProcessor] = LatentClassChoiceModel.getProcessors

    var row : LccmData = new LccmData()
    var data: Vector[LccmData] = Vector()
    while ( beanReader.read[LccmData](row, header, processors: _*) != null){
      if(row.value != null && !row.value.isNaN) data = data :+ row.clone().asInstanceOf[LccmData]
      row = new LccmData() // Turns out that if beanReader encounters a missing field, it just doesn't do the setField() rather than set it to null.... so we need to mannualy reset after every read
    }
    data
  }

  def extractClassMembershipModels(lccmData: Vector[LccmData]): Map[TourType,MulitnomialLogit] = {
    val classMemData = lccmData.filter(_.model=="classMembership")
    Vector[TourType](Mandatory,Nonmandatory).map{ theTourType =>
      val theData = classMemData.filter( _.tourType.equalsIgnoreCase(theTourType.toString))
      val theAlternatives = new util.LinkedList[String]()
      val theVariables = new util.LinkedList[String]()
      val theValues = new util.LinkedList[java.lang.Double]()
      theAlternatives.addAll(theData.map(_.alternative).asJava)
      theVariables.addAll(theData.map(_.variable).asJava)
      theValues.addAll(theData.map{theRow => java.lang.Double.valueOf(theRow.value)}.asJava)
      (theTourType -> MulitnomialLogit.MulitnomialLogitFactory(theTourType.toString, theVariables,theAlternatives,theValues))
    }.toMap
  }

  def extractModeChoiceModels(lccmData: Vector[LccmData]): Map[TourType,Map[String,MulitnomialLogit]] = {
    val uniqueClasses = lccmData.map(_.latentClass).distinct
    val modeChoiceData = lccmData.filter(_.model=="modeChoice")
    Vector[TourType](Mandatory,Nonmandatory).map{ theTourType =>
      val theTourTypeData = modeChoiceData.filter( _.tourType.equalsIgnoreCase(theTourType.toString))
      (theTourType -> uniqueClasses.map{ theLatentClass =>
        val theData = theTourTypeData.filter(_.latentClass.equalsIgnoreCase(theLatentClass))
        val theAlternatives = new util.LinkedList[String]()
        val theVariables = new util.LinkedList[String]()
        val theValues = new util.LinkedList[java.lang.Double]()
        theAlternatives.addAll(theData.map(_.alternative).asJava)
        theVariables.addAll(theData.map(_.variable).asJava)
        theValues.addAll(theData.map{theRow => java.lang.Double.valueOf(theRow.value)}.asJava)
        (theLatentClass -> MulitnomialLogit.MulitnomialLogitFactory(theTourType.toString, theVariables,theAlternatives,theValues))
      }.toMap)
    }.toMap
  }

  //TODO actually clone this
  override def clone():LatentClassChoiceModel = {
    new LatentClassChoiceModel(beamServices)
  }
}

object LatentClassChoiceModel{
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
    override def clone(): AnyRef = new LccmData(model,tourType,variable,alternative,units,latentClass,value)
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


