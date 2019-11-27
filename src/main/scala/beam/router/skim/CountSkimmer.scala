package beam.router.skim
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id

import scala.collection.immutable

class CountSkimmer(beamServices: BeamServices, config: BeamConfig.Beam.Router.Skim)
    extends AbstractSkimmer(beamServices, config) {
  import CountSkimmer._

  override lazy val readOnlySkim: AbstractSkimmerReadOnly = CountSkims(beamServices)

  override protected val skimName: String = config.count_skimmer.name
  override protected val skimFileBaseName: String = config.count_skimmer.fileBaseName
  override protected val skimFileHeader: String =
    "time,taz,hex,groupId,label,sumValue,meanValue,numObservations,numIteration"

  override def fromCsv(
    line: immutable.Map[String, String]
  ): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    (
      CountSkimmerKey(
        line("time").toInt,
        Id.create(line("taz"), classOf[TAZ]),
        line("hex"),
        line("groupId"),
        line("label")
      ),
      CountSkimmerInternal(
        line("sumValue").toDouble,
        line("meanValue").toDouble,
        line("numObservations").toInt,
        line("numIteration").toInt
      )
    )
  }

  override protected def aggregateOverIterations(
    prevIteration: Option[AbstractSkimmerInternal],
    currIteration: Option[AbstractSkimmerInternal]
  ): AbstractSkimmerInternal = {
    val prevSkim = prevIteration
      .map(_.asInstanceOf[CountSkimmerInternal])
      .getOrElse(CountSkimmerInternal(0, 0, numObservations = 0, numIteration = 0)) // no skim means no observation
    val currSkim = currIteration
      .map(_.asInstanceOf[CountSkimmerInternal])
      .getOrElse(CountSkimmerInternal(0, 0, numObservations = 0, numIteration = 1)) // no current skim means 0 observation
    CountSkimmerInternal(
      sumValue = (prevSkim.sumValue * prevSkim.numIteration + currSkim.sumValue * currSkim.numIteration) / (prevSkim.numIteration + currSkim.numIteration),
      meanValue = (prevSkim.meanValue * prevSkim.numIteration + currSkim.meanValue * currSkim.numIteration) / (prevSkim.numIteration + currSkim.numIteration),
      numObservations = (prevSkim.numObservations * prevSkim.numIteration + currSkim.numObservations * currSkim.numIteration) / (prevSkim.numIteration + currSkim.numIteration),
      numIteration = prevSkim.numIteration + currSkim.numIteration
    )
  }

  override protected def aggregateWithinAnIteration(
    prevObservation: Option[AbstractSkimmerInternal],
    currObservation: AbstractSkimmerInternal
  ): AbstractSkimmerInternal = {
    val prevSkim = prevObservation
      .map(_.asInstanceOf[CountSkimmerInternal])
      .getOrElse(CountSkimmerInternal(0, 0, numObservations = 0, numIteration = 0))
    val currSkim = currObservation.asInstanceOf[CountSkimmerInternal]
    CountSkimmerInternal(
      sumValue = prevSkim.sumValue + currSkim.sumValue,
      meanValue = (prevSkim.meanValue * prevSkim.numObservations + currSkim.meanValue * currSkim.numObservations) / (prevSkim.numObservations + currSkim.numObservations),
      numObservations = prevSkim.numObservations + currSkim.numObservations,
      numIteration = beamServices.matsimServices.getIterationNumber + 1
    )
  }
}

object CountSkimmer extends LazyLogging {
  case class CountSkimmerKey(
    time: Int,
    taz: Id[TAZ],
    hex: String,
    groupId: String,
    label: String
  ) extends AbstractSkimmerKey {
    override def toCsv: String = time + "," + taz + "," + hex + "," + groupId + "," + label
  }
  case class CountSkimmerInternal(sumValue: Double, meanValue: Double, numObservations: Int, numIteration: Int = 0)
      extends AbstractSkimmerInternal {
    override def toCsv: String = sumValue + "," + meanValue + "," + numObservations + "," + numIteration
  }
}
