package beam.router.skim
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.sim.vehiclesharing.VehicleManager
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id

import scala.collection.immutable

class CountSkimmer(beamServices: BeamServices, config: BeamConfig.Beam.Router.Skim.Skimmers$Elm)
    extends AbstractSkimmer(beamServices, config) {
  import CountSkimmer._

  override val readOnlySkim: AbstractSkimmerReadOnly = CountSkims(beamServices)

  override protected val skimFileBaseName: String = "skimsCount"
  override protected val skimFileHeader: String = "timeBin,idTaz,hexIndex,idVehManager,variableName,count"

  override def fromCsv(
    line: immutable.Map[String, String]
  ): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    (
      CountSkimmerKey(
        line("timeBin").toInt,
        Id.create(line("idTaz"), classOf[TAZ]),
        line("hexIndex"),
        Id.create(line("idVehManager"), classOf[VehicleManager]),
        line("variableName")
      ),
      CountSkimmerInternal(line("count").toInt)
    )
  }
}

object CountSkimmer extends LazyLogging {
  case class CountSkimmerKey(
    timeBin: Int,
    idTaz: Id[TAZ],
    hexIndex: String,
    idVehManager: Id[VehicleManager],
    valueLabel: String
  ) extends AbstractSkimmerKey {
    override def toCsv: String = timeBin + "," + idTaz + "," + hexIndex + "," + idVehManager + "," + valueLabel
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
