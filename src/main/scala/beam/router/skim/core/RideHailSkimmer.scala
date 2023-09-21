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
    "tazId,hour,reservationType,wheelchairRequired,serviceName,waitTimeForRequests,costPerMileForRequests,unmatchedRequestsPercent,waitTimeForQuotes,costPerMileForQuotes,unmatchedQuotesPercent,accessibleVehiclesPercent,numberOfReservationsRequested,numberOfReservationsReturned,numberOfQuotesRequested,numberOfQuotesReturned,observations,iterations"
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
        line.get("wheelchairRequired").map(_.toBoolean).getOrElse {
          logger.warn(
            "wheelchairRequired column is missing from the skim. The value False has been considered as a default value."
          )
          false
        },
        serviceName = line.getOrElse("serviceName", "GlobalRHM")
      ),
      RidehailSkimmerInternal(
        waitTimeForRequests = Option(line("waitTimeForRequests")).map(_.toDouble).getOrElse(Double.NaN),
        costPerMileForRequests = Option(line("costPerMileForRequests")).map(_.toDouble).getOrElse(Double.NaN),
        unmatchedRequestsPercent = line("unmatchedRequestsPercent").toDouble,
        waitTimeForQuotes = Option(line("waitTimeForQuotes")).map(_.toDouble).getOrElse(Double.NaN),
        costPerMileForQuotes = Option(line("costPerMileForQuotes")).map(_.toDouble).getOrElse(Double.NaN),
        unmatchedQuotesPercent = line("unmatchedQuotesPercent").toDouble,
        accessibleVehiclePercent = line.get("accessibleVehiclePercent").map(_.toDouble).getOrElse {
          logger.warn(
            "accessibleVehiclePercent column is missing from the skim. The value 0.0 has been considered as a default value."
          )
          0.0
        },
        numberOfReservationsRequested = line("numberOfReservationsRequested").toInt,
        numberOfReservationsReturned = line("numberOfReservationsReturned").toInt,
        numberOfQuotesRequested = line("numberOfQuotesRequested").toInt,
        numberOfQuotesReturned = line("numberOfQuotesReturned").toInt,
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
        waitTimeForRequests =
          agg.aggregate(_.waitTimeForRequests, (x: RidehailSkimmerInternal) => x.numberOfReservationsReturned),
        costPerMileForRequests =
          agg.aggregate(_.costPerMileForRequests, (x: RidehailSkimmerInternal) => x.numberOfReservationsReturned),
        unmatchedRequestsPercent =
          agg.aggregate(_.unmatchedRequestsPercent, (x: RidehailSkimmerInternal) => x.numberOfReservationsRequested),
        waitTimeForQuotes =
          agg.aggregate(_.waitTimeForQuotes, (x: RidehailSkimmerInternal) => x.numberOfQuotesReturned),
        costPerMileForQuotes =
          agg.aggregate(_.costPerMileForQuotes, (x: RidehailSkimmerInternal) => x.numberOfQuotesReturned),
        unmatchedQuotesPercent =
          agg.aggregate(_.unmatchedQuotesPercent, (x: RidehailSkimmerInternal) => x.numberOfQuotesRequested),
        accessibleVehiclePercent =
          agg.aggregate(_.accessibleVehiclePercent, (x: RidehailSkimmerInternal) => x.numberOfQuotesRequested),
        numberOfReservationsRequested = agg.aggregate(_.numberOfReservationsRequested, weighted = false),
        numberOfReservationsReturned = agg.aggregate(_.numberOfReservationsReturned, weighted = false),
        numberOfQuotesRequested = agg.aggregate(_.numberOfQuotesRequested, weighted = false),
        numberOfQuotesReturned = agg.aggregate(_.numberOfQuotesReturned, weighted = false),
        observations = agg.aggregate(_.observations, weighted = false),
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
          agg.aggregate(_.waitTimeForRequests, (x: RidehailSkimmerInternal) => x.numberOfReservationsReturned),
        costPerMileForRequests =
          agg.aggregate(_.costPerMileForRequests, (x: RidehailSkimmerInternal) => x.numberOfReservationsReturned),
        unmatchedRequestsPercent =
          agg.aggregate(_.unmatchedRequestsPercent, (x: RidehailSkimmerInternal) => x.numberOfReservationsRequested),
        waitTimeForQuotes =
          agg.aggregate(_.waitTimeForQuotes, (x: RidehailSkimmerInternal) => x.numberOfQuotesReturned),
        costPerMileForQuotes =
          agg.aggregate(_.costPerMileForQuotes, (x: RidehailSkimmerInternal) => x.numberOfQuotesReturned),
        unmatchedQuotesPercent =
          agg.aggregate(_.unmatchedQuotesPercent, (x: RidehailSkimmerInternal) => x.numberOfQuotesRequested),
        accessibleVehiclePercent =
          agg.aggregate(_.accessibleVehiclePercent, (x: RidehailSkimmerInternal) => x.numberOfQuotesRequested),
        numberOfReservationsRequested = agg.aggregate(_.numberOfReservationsRequested, weighted = false),
        numberOfReservationsReturned = agg.aggregate(_.numberOfReservationsReturned, weighted = false),
        numberOfQuotesRequested = agg.aggregate(_.numberOfQuotesRequested, weighted = false),
        numberOfQuotesReturned = agg.aggregate(_.numberOfQuotesReturned, weighted = false),
        observations = agg.aggregate(_.observations, weighted = false)
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
    numberOfQuotesRequested: Int,
    numberOfQuotesReturned: Int,
    observations: Int = 1,
    iterations: Int = 1
  ) extends AbstractSkimmerInternal {
    override def toCsv: String = AbstractSkimmer.toCsv(productIterator)
  }

}
