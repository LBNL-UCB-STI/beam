package beam.router.skim
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id

import scala.collection.immutable

class CountSkimmer(beamServices: BeamServices, config: BeamConfig.Beam.Router.Skim.Skimmers$Elm)
    extends AbstractSkimmer(beamServices, config) {
  import CountSkimmer._

  override val readOnlySkim: AbstractSkimmerReadOnly = CountSkims(beamServices)

  override protected val skimType: String = config.count_skimmer.get.skimType
  override protected val skimFileBaseName: String = config.count_skimmer.get.skimFileBaseName
  override protected val skimFileHeader: String = "time,taz,hex,groupId,label,count"

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
      CountSkimmerInternal(line("count").toInt)
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

  case class CountSkimmerInternal(count: Int) extends AbstractSkimmerInternal {
    override def aggregateOverIterations(
      nbOfIterations: Int,
      newSkim: Option[_ <: AbstractSkimmerInternal]
    ): AbstractSkimmerInternal = {
      newSkim match {
        case Some(skim: CountSkimmerInternal) =>
          CountSkimmerInternal(((this.count * nbOfIterations) + skim.count) / (nbOfIterations + 1))
        case _ =>
          CountSkimmerInternal((this.count * nbOfIterations) / (nbOfIterations + 1))
      }
    }
    override def aggregateByKey(newSkim: Option[_ <: AbstractSkimmerInternal]): AbstractSkimmerInternal = {
      CountSkimmerInternal(this.count + newSkim.map(_.asInstanceOf[CountSkimmerInternal].count).getOrElse(0))
    }
    override def toCsv: String = count.toString
  }
}
