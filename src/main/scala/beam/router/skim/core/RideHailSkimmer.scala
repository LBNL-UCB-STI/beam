package beam.router.skim.core

import beam.agentsim.events.RideHailReservationConfirmationEvent.{Pooled, RideHailReservationType, Solo}
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.Skims
import beam.router.skim.readonly.RideHailSkims
import beam.sim.config.BeamConfig
import beam.utils.{OutputDataDescriptor, OutputDataDescriptorObject}
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
    "tazId,hour,reservationType,wheelchairRequired,serviceName,waitTime,costPerMile,unmatchedRequestsPercent,accessibleVehiclesPercent,observations,iterations"
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
        waitTime = Option(line("waitTime")).map(_.toDouble).getOrElse(Double.NaN),
        costPerMile = Option(line("costPerMile")).map(_.toDouble).getOrElse(Double.NaN),
        unmatchedRequestsPercent = line("unmatchedRequestsPercent").toDouble,
        accessibleVehiclePercent = line.get("accessibleVehiclePercent").map(_.toDouble).getOrElse {
          logger.warn(
            "accessibleVehiclePercent column is missing from the skim. The value 0.0 has been considered as a default value."
          )
          0.0
        },
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
        waitTime = agg.aggregate(_.waitTime),
        costPerMile = agg.aggregate(_.costPerMile),
        unmatchedRequestsPercent = agg.aggregate(_.unmatchedRequestsPercent),
        accessibleVehiclePercent = agg.aggregate(_.accessibleVehiclePercent),
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
        waitTime = agg.aggregate(_.waitTime),
        costPerMile = agg.aggregate(_.costPerMile),
        unmatchedRequestsPercent = agg.aggregate(_.unmatchedRequestsPercent),
        accessibleVehiclePercent = agg.aggregate(_.accessibleVehiclePercent),
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
    waitTime: Double,
    costPerMile: Double,
    unmatchedRequestsPercent: Double,
    accessibleVehiclePercent: Double,
    observations: Int = 1,
    iterations: Int = 1
  ) extends AbstractSkimmerInternal {
    override def toCsv: String = AbstractSkimmer.toCsv(productIterator)
  }

  def rideHailSkimOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("RidehailSkimmer", s"${fileBaseName}.csv.gz", iterationLevel = true)(
      """
        tazId                     | Id of TAZ this statistic applies to
        hour                      | Hour this statistic applies to
        reservationType           | Reservation type (solo or pooled) this statistic applies to
        wheelchairRequired        | Boolean value indicating whether or not a wheelchair is required
        serviceName               | Service name this statistic applies to
        waitTime                  | Average waiting time
        costPerMile               | Average cost per mile
        unmatchedRequestsPercent  | Average unmatched request percent
        accessibleVehiclesPercent | Average percent of wheelchair accessible vehicles
        observations              | Number of ride-hail requests
        iterations                | Always 1
        """
    )

  def aggregatedRideHailSkimOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("RidehailSkimmer", s"${fileBaseName}_Aggregated.csv.gz", iterationLevel = true)(
      """
        tazId                     | Id of TAZ this statistic applies to
        hour                      | Hour this statistic applies to
        reservationType           | Reservation type (solo or pooled) this statistic applies to
        wheelchairRequired        | Boolean value indicating whether or not a wheelchair is required
        serviceName               | Service name this statistic applies to
        waitTime                  | Average (over last n iterations) waiting time
        costPerMile               | Average (over last n iterations) cost per mile
        unmatchedRequestsPercent  | Average (over last n iterations) unmatched request percent
        accessibleVehiclesPercent | Average (over last n iterations) percent of wheelchair accessible vehicles
        observations              | Average (over last n iterations) number of ride-hail requests
        iterations                | Number of iterations
        """
    )

}
