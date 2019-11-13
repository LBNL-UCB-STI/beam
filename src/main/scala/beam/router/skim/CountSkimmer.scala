package beam.router.skim
import beam.agentsim.infrastructure.taz.{H3TAZ, TAZ}
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.sim.vehiclesharing.VehicleManager
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id

import scala.collection.immutable

class CountSkimmer(beamServices: BeamServices, config: BeamConfig.Beam.Router.Skim$Elm) extends AbstractSkimmer(beamServices, config) {
  import CountSkimmer._
  CountSkimmer.h3taz = beamServices.beamScenario.h3taz

  override protected def skimFileBaseName: String = CountSkimmer.fileBaseName
  override protected def skimFileHeader: String = CountSkimmer.csvLineHeader
  override protected def getEventType: String = CountSkimmer.eventType

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
  override protected def publishReadOnlySkims(): Unit = {
    CountSkimmer.rdOnlyPastSkims = pastSkims
      .map(_.map(kv => kv._1.asInstanceOf[CountSkimmerKey] -> kv._2.asInstanceOf[CountSkimmerInternal]))
      .toArray
    CountSkimmer.rdOnlyAggregatedSkim =
      aggregatedSkim.map(kv => kv._1.asInstanceOf[CountSkimmerKey] -> kv._2.asInstanceOf[CountSkimmerInternal])
  }

}

object CountSkimmer extends LazyLogging {

  private val csvLineHeader: String = "timeBin,idTaz,hexIndex,idVehManager,variableName,count"
  private[skim] val eventType: String = "CountSkimmerEvent"
  private val fileBaseName: String = "skimsCount"
  private var rdOnlyPastSkims: Array[immutable.Map[CountSkimmerKey, CountSkimmerInternal]] = Array()
  private var rdOnlyAggregatedSkim: immutable.Map[CountSkimmerKey, CountSkimmerInternal] = immutable.Map()
  private var h3taz: H3TAZ = _

  def getLatestSkim(
    timeBin: Int,
    idTaz: Id[TAZ],
    hexIndex: String,
    idVehMng: Id[VehicleManager],
    variableName: String
  ): Option[CountSkimmerInternal] = {
    rdOnlyPastSkims.headOption
      .flatMap(_.get(CountSkimmerKey(timeBin, idTaz, hexIndex, idVehMng, variableName)))
      .asInstanceOf[Option[CountSkimmerInternal]]
  }

  def getLatestSkim(
    timeBin: Int,
    hexIndex: String,
    idVehMng: Id[VehicleManager],
    variableName: String
  ): Option[CountSkimmerInternal] = {
    getLatestSkim(timeBin, h3taz.getTAZ(hexIndex), hexIndex, idVehMng, variableName)
  }

  def getLatestSkimByTAZ(
    timeBin: Int,
    idTaz: Id[TAZ],
    idVehMng: Id[VehicleManager],
    variableName: String
  ): Option[CountSkimmerInternal] = {
    h3taz
      .getHRHex(idTaz)
      .flatMap(hexIndex => getLatestSkim(timeBin, idTaz, hexIndex, idVehMng, variableName))
      .foldLeft[Option[CountSkimmerInternal]](None) {
        case (acc, skimInternal) =>
          acc match {
            case Some(skim) => Some(CountSkimmerInternal(skim.count + skimInternal.count))
            case _          => Some(skimInternal)
          }
      }
  }

  def getAggregatedSkim(
    timeBin: Int,
    idTaz: Id[TAZ],
    hexIndex: String,
    idVehMng: Id[VehicleManager],
    variableName: String
  ): Option[CountSkimmerInternal] = {
    rdOnlyAggregatedSkim
      .get(CountSkimmerKey(timeBin, idTaz, hexIndex, idVehMng, variableName))
      .asInstanceOf[Option[CountSkimmerInternal]]
  }

  // *********************
  // Cases
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
