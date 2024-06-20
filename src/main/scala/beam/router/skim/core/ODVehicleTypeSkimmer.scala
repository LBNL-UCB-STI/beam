package beam.router.skim.core

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.Skims
import beam.router.skim.readonly.ODVehicleTypeSkims
import beam.sim.config.BeamConfig
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import beam.utils.{OutputDataDescriptor, OutputDataDescriptorObject}
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.lang3.math.NumberUtils
import org.matsim.api.core.v01.Id
import org.matsim.core.controler.MatsimServices

/**
  * @author Dmitry Openkov
  */
class ODVehicleTypeSkimmer @Inject() (
  matsimServices: MatsimServices,
  beamConfig: BeamConfig
) extends AbstractSkimmer(beamConfig, matsimServices.getControlerIO) {
  import ODVehicleTypeSkimmer._
  override protected[skim] val readOnlySkim = new ODVehicleTypeSkims
  override protected val skimFileBaseName: String = ODVehicleTypeSkimmer.fileBaseName

  override protected val skimFileHeader: String =
    "hour,vehicleType,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,payloadWeightInKg,energy,observations,iterations"
  override protected val skimName: String = ODVehicleTypeSkimmer.name
  override protected val skimType: Skims.SkimType.Value = Skims.SkimType.OD_VEHICLE_TYPE_SKIMMER

  override protected def fromCsv(
    line: collection.Map[String, String]
  ): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    (
      ODVehicleTypeSkimmerKey(
        hour = line("hour").toInt,
        vehicleType = line("vehicleType").createId,
        origin = line("origTaz").createId,
        destination = line("destTaz").createId
      ),
      ODVehicleTypeSkimmerInternal(
        travelTimeInS = line("travelTimeInS").toDouble,
        generalizedTimeInS = line("generalizedTimeInS").toDouble,
        generalizedCost = line("generalizedCost").toDouble,
        distanceInM = line("distanceInM").toDouble,
        cost = line("cost").toDouble,
        energy = Option(line("energy")).map(_.toDouble).getOrElse(0.0),
        payloadWeightInKg = line.get("payloadWeightInKg").map(_.toDouble).getOrElse(0.0),
        observations = NumberUtils.toInt(line("observations"), 0),
        iterations = NumberUtils.toInt(line("iterations"), 1)
      )
    )
  }

  override protected def aggregateOverIterations(
    prevIteration: Option[AbstractSkimmerInternal],
    currIteration: Option[AbstractSkimmerInternal]
  ): AbstractSkimmerInternal =
    AbstractSkimmer.aggregateOverIterations[ODVehicleTypeSkimmerInternal](prevIteration, currIteration) { agg =>
      ODVehicleTypeSkimmerInternal(
        travelTimeInS = agg.aggregate(_.travelTimeInS),
        generalizedTimeInS = agg.aggregate(_.generalizedTimeInS),
        generalizedCost = agg.aggregate(_.generalizedCost),
        distanceInM = agg.aggregate(_.distanceInM),
        cost = agg.aggregate(_.cost),
        energy = agg.aggregate(_.energy),
        payloadWeightInKg = agg.aggregate(_.payloadWeightInKg),
        observations = agg.aggregate(_.observations),
        iterations = agg.aggregateObservations
      )
    }

  override protected def aggregateWithinIteration(
    prevObservation: Option[AbstractSkimmerInternal],
    currObservation: AbstractSkimmerInternal
  ): AbstractSkimmerInternal =
    AbstractSkimmer.aggregateWithinIteration[ODVehicleTypeSkimmerInternal](prevObservation, currObservation) { agg =>
      ODVehicleTypeSkimmerInternal(
        travelTimeInS = agg.aggregate(_.travelTimeInS),
        generalizedTimeInS = agg.aggregate(_.generalizedTimeInS),
        generalizedCost = agg.aggregate(_.generalizedCost),
        distanceInM = agg.aggregate(_.distanceInM),
        cost = agg.aggregate(_.cost),
        energy = agg.aggregate(_.energy),
        payloadWeightInKg = agg.aggregate(_.payloadWeightInKg),
        observations = agg.aggregateObservations
      )
    }
}

object ODVehicleTypeSkimmer extends LazyLogging {
  val name = "od-vehicle-type-skimmer"
  val fileBaseName = "skimsODVehicleType"

  case class ODVehicleTypeSkimmerKey(
    hour: Int,
    vehicleType: Id[BeamVehicleType],
    origin: Id[TAZ],
    destination: Id[TAZ]
  ) extends AbstractSkimmerKey {
    override def toCsv: String = productIterator.mkString(",")
  }

  case class ODVehicleTypeSkimmerInternal(
    travelTimeInS: Double,
    generalizedTimeInS: Double,
    generalizedCost: Double,
    distanceInM: Double,
    cost: Double,
    payloadWeightInKg: Double,
    energy: Double,
    observations: Int = 1,
    iterations: Int = 1
  ) extends AbstractSkimmerInternal {
    override def toCsv: String = AbstractSkimmer.toCsv(productIterator)
  }

  def odVehicleTypeSkimOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("ODVehicleTypeSkimmer", "skimsODVehicleType.csv.gz", iterationLevel = true)(
      """
        hour                              | Hour this statistic applies to
        vehicleType                       | Type of the vehicle making the trip
        origTaz                           | TAZ id of trip origin
        destTaz                           | TAZ id of trip destination
        travelTimeInS                     | Average travel time in seconds
        generalizedTimeInS                | Average generalized travel time in seconds
        cost                              | Average trip total cost
        generalizedCost                   | Average trip generalized cost
        distanceInM                       | Average trip distance in meters
        payloadWeightInKg                 | Average payload weight (if it's not a freight trip then it is zero)
        energy                            | Average energy consumed in Joules
        observations                      | Number of events
        iterations                        | Number of iterations (always 1)
        """
    )

  def aggregatedODVehicleTypeSkimOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("ODVehicleTypeSkimmer", "skimsODVehicleType_Aggregated.csv.gz", iterationLevel = true)(
      """
        hour                              | Hour this statistic applies to
        vehicleType                       | Trip mode
        origTaz                           | TAZ id of trip origin
        destTaz                           | TAZ id of trip destination
        travelTimeInS                     | Average (over last n iterations) travel time in seconds
        generalizedTimeInS                | Average generalized travel time in seconds
        cost                              | Average trip total cost
        generalizedCost                   | Average trip generalized cost
        distanceInM                       | Average trip distance in meters
        payloadWeightInKg                 | Average payload weight (if it's not a freight trip then it is zero)
        energy                            | Average energy consumed in Joules
        observations                      | Number of events
        iterations                        | Number of iterations which data is used here
        """
    )
}
