package beam.router.skim.core

import beam.agentsim.events.RideHailReservationConfirmationEvent.{Pooled, RideHailReservationType, Solo}
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.Skims
import beam.router.skim.readonly.RideHailSkims
import beam.sim.config.BeamConfig
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.core.controler.MatsimServices

/**
  * @author Dmitry Openkov
  */
class RideHailSkimmer @Inject() (
  matsimServices: MatsimServices,
  beamConfig: BeamConfig
) extends AbstractSkimmer(beamConfig, matsimServices.getControlerIO) {
  import RideHailSkimmer._
  override protected[skim] val readOnlySkim = new RideHailSkims
  override protected val skimFileBaseName: String = RideHailSkimmer.fileBaseName

  override protected val skimFileHeader =
    "tazId,hour,reservationType,wheelchairRequired,serviceName,waitTimeForRequests,costPerMileForRequests,unmatchedRequestsPercent,waitTimeForQuotes,costPerMileForQuotes,unmatchedQuotesPercent,accessibleVehiclesPercent,numberOfQuotesRequested,numberOfQuotesReturned,observations,iterations"
  override protected val skimName: String = RideHailSkimmer.name
  override protected val skimType: Skims.SkimType.Value = Skims.SkimType.RH_SKIMMER

  override protected def fromCsv(
    line: collection.Map[String, String]
  ): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    (
      RidehailSkimmerKey(
        tazId = line("tazId").createId,
        hour = line("hour").toInt,
        reservationType = if (line("reservationType").equalsIgnoreCase("pooled")) Pooled else Solo,
        line("wheelchairRequired").toBoolean,
        serviceName = line.getOrElse("serviceName", "GlobalRHM")
      ),
      RidehailSkimmerInternal(
        waitTimeForRequests = Option(line("waitTimeForRequests")).map(_.toDouble).getOrElse(Double.NaN),
        costPerMileForRequests = Option(line("costPerMileForRequests")).map(_.toDouble).getOrElse(Double.NaN),
        unmatchedRequestsPercent = line("unmatchedRequestsPercent").toDouble,
        waitTimeForQuotes = Option(line("waitTimeForQuotes")).map(_.toDouble).getOrElse(Double.NaN),
        costPerMileForQuotes = Option(line("costPerMileForQuotes")).map(_.toDouble).getOrElse(Double.NaN),
        unmatchedQuotesPercent = line("unmatchedQuotesPercent").toDouble,
        accessibleVehiclePercent = line("accessibleVehiclePercent").toDouble,
        numberOfReservationsRequested = line("numberOfQuotesRequested").toInt,
        numberOfReservationsReturned = line("numberOfQuotesReturned").toInt,
        observations = line("observations").toInt,
        iterations = line("iterations").toInt
      )
    )
  }

  override protected def aggregateOverIterations(
    prevIteration: Option[AbstractSkimmerInternal],
    currIteration: Option[AbstractSkimmerInternal]
  ): AbstractSkimmerInternal =
    AbstractSkimmer.aggregateOverIterations[RidehailSkimmerInternal](prevIteration, currIteration) { agg =>
      RidehailSkimmerInternal(
        waitTimeForRequests = agg.aggregate(_.waitTimeForRequests),
        costPerMileForRequests = agg.aggregate(_.costPerMileForRequests),
        unmatchedRequestsPercent = agg.aggregate(_.unmatchedRequestsPercent),
        waitTimeForQuotes = agg.aggregate(_.waitTimeForQuotes),
        costPerMileForQuotes = agg.aggregate(_.costPerMileForQuotes),
        unmatchedQuotesPercent = agg.aggregate(_.unmatchedQuotesPercent),
        accessibleVehiclePercent = agg.aggregate(_.accessibleVehiclePercent),
        numberOfReservationsRequested = agg.aggregate(_.numberOfReservationsRequested),
        numberOfReservationsReturned = agg.aggregate(_.numberOfReservationsReturned),
        observations = agg.aggregate(_.observations),
        iterations = agg.aggregateObservations
      )
    }

  override protected def aggregateWithinIteration(
    prevObservation: Option[AbstractSkimmerInternal],
    currObservation: AbstractSkimmerInternal
  ): AbstractSkimmerInternal =
    AbstractSkimmer.aggregateWithinIteration[RidehailSkimmerInternal](prevObservation, currObservation) { agg =>
      RidehailSkimmerInternal(
        waitTimeForRequests =
          agg.aggregate(_.waitTimeForRequests, (x: RidehailSkimmerInternal) => x.numberOfReservationsRequested),
        costPerMileForRequests = agg.aggregate(_.costPerMileForRequests),
        unmatchedRequestsPercent = agg.aggregate(_.unmatchedRequestsPercent),
        waitTimeForQuotes = agg.aggregate(_.waitTimeForQuotes),
        costPerMileForQuotes = agg.aggregate(_.costPerMileForQuotes),
        unmatchedQuotesPercent = agg.aggregate(_.unmatchedQuotesPercent),
        accessibleVehiclePercent = agg.aggregate(_.accessibleVehiclePercent),
        numberOfReservationsRequested = agg.aggregate(_.numberOfReservationsRequested),
        numberOfReservationsReturned = agg.aggregate(_.numberOfReservationsReturned),
        observations = agg.aggregateObservations
      )
    }
}

object RideHailSkimmer extends LazyLogging {
  val name = "ridehail-skimmer"
  val fileBaseName = "skimsRidehail"

  case class RidehailSkimmerKey(
    tazId: Id[TAZ],
    hour: Int,
    reservationType: RideHailReservationType,
    wheelchairRequired: Boolean,
    serviceName: String
  ) extends AbstractSkimmerKey {
    override def toCsv: String = productIterator.mkString(",")
  }

  case class RidehailSkimmerInternal(
    waitTimeForRequests: Double,
    costPerMileForRequests: Double,
    unmatchedRequestsPercent: Double,
    waitTimeForQuotes: Double,
    costPerMileForQuotes: Double,
    accessibleVehiclePercent: Double,
    unmatchedQuotesPercent: Double,
    numberOfReservationsRequested: Int,
    numberOfReservationsReturned: Int,
    observations: Int = 1,
    iterations: Int = 1
  ) extends AbstractSkimmerInternal {
    override def toCsv: String = AbstractSkimmer.toCsv(productIterator)
  }

}
