package beam.router.skim.core

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.Skims
import beam.router.skim.readonly.FreightSkims
import beam.sim.config.BeamConfig
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.core.controler.MatsimServices

/**
  * @author Dmitry Openkov
  */
class FreightSkimmer @Inject() (
  matsimServices: MatsimServices,
  beamConfig: BeamConfig
) extends AbstractSkimmer(beamConfig, matsimServices.getControlerIO) {
  import FreightSkimmer._
  override protected[skim] val readOnlySkim = new FreightSkims
  override protected val skimFileBaseName: String = FreightSkimmer.fileBaseName

  override protected val skimFileHeader =
    "tazId,hour,numberOfLoadings,numberOfUnloadings,costPerMile,walkAccessDistanceInM,parkingCostPerHour,observations,iterations"
  override protected val skimName: String = FreightSkimmer.name
  override protected val skimType: Skims.SkimType.Value = Skims.SkimType.FREIGHT_SKIMMER

  override protected def fromCsv(
    line: collection.Map[String, String]
  ): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    (
      FreightSkimmerKey(
        tazId = line("tazId").createId,
        hour = line("hour").toInt
      ),
      FreightSkimmerInternal(
        numberOfLoadings = line("numberOfLoadings").toDouble,
        numberOfUnloadings = line("numberOfUnloadings").toDouble,
        costPerMile = line("costPerMile").toDouble,
        walkAccessDistanceInM = line("walkAccessDistanceInM").toDouble,
        parkingCostPerHour = line("parkingCostPerHour").toDouble,
        observations = line("observations").toInt,
        iterations = line("iterations").toInt
      )
    )
  }

  override protected def aggregateOverIterations(
    prevIteration: Option[AbstractSkimmerInternal],
    currIteration: Option[AbstractSkimmerInternal]
  ): AbstractSkimmerInternal =
    AbstractSkimmer.aggregateOverIterations[FreightSkimmerInternal](prevIteration, currIteration) { agg =>
      FreightSkimmerInternal(
        numberOfLoadings = agg.aggregate(_.numberOfLoadings),
        numberOfUnloadings = agg.aggregate(_.numberOfUnloadings),
        costPerMile = agg.aggregate(_.costPerMile),
        walkAccessDistanceInM = agg.aggregate(_.walkAccessDistanceInM),
        parkingCostPerHour = agg.aggregate(_.parkingCostPerHour),
        observations = agg.aggregate(_.observations),
        iterations = agg.aggregateObservations
      )
    }

  override protected def aggregateWithinIteration(
    prevObservation: Option[AbstractSkimmerInternal],
    currObservation: AbstractSkimmerInternal
  ): AbstractSkimmerInternal =
    AbstractSkimmer.aggregateWithinIteration[FreightSkimmerInternal](prevObservation, currObservation) { agg =>
      FreightSkimmerInternal(
        numberOfLoadings = agg.sum(_.numberOfLoadings),
        numberOfUnloadings = agg.sum(_.numberOfUnloadings),
        costPerMile = agg.aggregate(_.costPerMile),
        walkAccessDistanceInM = agg.aggregate(_.walkAccessDistanceInM),
        parkingCostPerHour = agg.aggregate(_.parkingCostPerHour),
        observations = agg.aggregateObservations
      )
    }
}

object FreightSkimmer extends LazyLogging {
  val name = "freight-skimmer"
  val fileBaseName = "skimsFreight"

  case class FreightSkimmerKey(tazId: Id[TAZ], hour: Int) extends AbstractSkimmerKey {
    override def toCsv: String = productIterator.mkString(",")
  }

  case class FreightSkimmerInternal(
                                     numberOfLoadings: Double,
                                     numberOfUnloadings: Double,
                                     costPerMile: Double,
                                     walkAccessDistanceInM: Double,
                                     parkingCostPerHour: Double,
                                     observations: Int = 1,
                                     iterations: Int = 1
  ) extends AbstractSkimmerInternal {
    override def toCsv: String = AbstractSkimmer.toCsv(productIterator)
  }
}
