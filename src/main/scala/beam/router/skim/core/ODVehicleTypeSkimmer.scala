package beam.router.skim.core

import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.agentsim.agents.vehicles.{FuelType, VehicleCategory}
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.Skims
import beam.router.skim.readonly.ODVehicleTypeSkims
import beam.sim.config.BeamConfig
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import beam.utils.{OutputDataDescriptor, OutputDataDescriptorObject}
import com.google.inject.Inject
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

  val vehicleCategoriesToGenerateSkim: IndexedSeq[VehicleCategory] =
    beamConfig.beam.router.skim.origin_destination_vehicle_type_skimmer.vehicleCategories
      .split(',')
      .map(_.trim)
      .map(VehicleCategory.fromString)
      .toIndexedSeq

  override protected val skimFileHeader: String =
    "hour,vehicleCategory,primaryFuelType,secondaryFuelType,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,payloadWeightInKg,energy,observations,iterations"
  override protected val skimName: String = ODVehicleTypeSkimmer.name
  override protected val skimType: Skims.SkimType.Value = Skims.SkimType.OD_VEHICLE_TYPE_SKIMMER

  override protected def fromCsv(
    line: collection.Map[String, String]
  ): (AbstractSkimmerKey, AbstractSkimmerInternal) = ODVehicleTypeSkimmer.fromCsv(line)

  override protected def aggregateOverIterations(
    prevIteration: Option[AbstractSkimmerInternal],
    currIteration: Option[AbstractSkimmerInternal]
  ): AbstractSkimmerInternal =
    AbstractSkimmer.aggregateOverIterations[ODVehicleTypeSkimmerInternal](prevIteration, currIteration) { agg =>
      ODVehicleTypeSkimmerInternal(
        travelTimeInS = agg.aggregate(_.travelTimeInS),
        generalizedTimeInS = agg.aggregate(_.generalizedTimeInS),
        cost = agg.aggregate(_.cost),
        generalizedCost = agg.aggregate(_.generalizedCost),
        distanceInM = agg.aggregate(_.distanceInM),
        payloadWeightInKg = agg.aggregate(_.payloadWeightInKg),
        energy = agg.aggregate(_.energy),
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
        cost = agg.aggregate(_.cost),
        generalizedCost = agg.aggregate(_.generalizedCost),
        distanceInM = agg.aggregate(_.distanceInM),
        payloadWeightInKg = agg.aggregate(_.payloadWeightInKg),
        energy = agg.aggregate(_.energy),
        observations = agg.aggregateObservations
      )
    }
}

object ODVehicleTypeSkimmer {
  val name = "od-vehicle-type-skimmer"
  val fileBaseName = "skimsODVehicleType"

  case class ODVehicleTypeSkimmerKey(
    hour: Int,
    vehicleCategory: VehicleCategory,
    primaryFuelType: FuelType,
    secondaryFuelType: FuelType,
    origin: Id[TAZ],
    destination: Id[TAZ]
  ) extends AbstractSkimmerKey {
    override def toCsv: String = productIterator.mkString(",")
  }

  case class ODVehicleTypeSkimmerInternal(
    travelTimeInS: Double,
    generalizedTimeInS: Double,
    cost: Double,
    generalizedCost: Double,
    distanceInM: Double,
    payloadWeightInKg: Double,
    energy: Double,
    observations: Int = 1,
    iterations: Int = 1
  ) extends AbstractSkimmerInternal {

    override def toCsv: String =
      AbstractSkimmer.toCsv(productIterator)
  }

  def fromCsv(
    line: collection.Map[String, String]
  ): (ODVehicleTypeSkimmerKey, ODVehicleTypeSkimmerInternal) = {
    (
      ODVehicleTypeSkimmerKey(
        hour = line("hour").toInt,
        vehicleCategory = VehicleCategory.fromString(line("vehicleCategory")),
        primaryFuelType = FuelType.fromString(line("primaryFuelType")),
        secondaryFuelType = FuelType.fromString(line("secondaryFuelType")),
        origin = line("origTaz").createId,
        destination = line("destTaz").createId
      ),
      ODVehicleTypeSkimmerInternal(
        travelTimeInS = line("travelTimeInS").toDouble,
        generalizedTimeInS = line("generalizedTimeInS").toDouble,
        cost = line("cost").toDouble,
        generalizedCost = line("generalizedCost").toDouble,
        distanceInM = line("distanceInM").toDouble,
        payloadWeightInKg = line.get("payloadWeightInKg").map(_.toDouble).getOrElse(0.0),
        energy = Option(line("energy")).map(_.toDouble).getOrElse(0.0),
        observations = NumberUtils.toInt(line("observations"), 0),
        iterations = NumberUtils.toInt(line("iterations"), 1)
      )
    )
  }

  def odVehicleTypeSkimOutputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("ODVehicleTypeSkimmer", "skimsODVehicleType.csv.gz", iterationLevel = true)(
      """
        hour                              | Hour this statistic applies to
        vehicleCategory                   | Category of the vehicle making the trip
        primaryFuelType                   | Primary fuel type of the vehicle making the trip
        secondaryFuelType                 | Secondary fuel type of the vehicle making the trip
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
        vehicleCategory                   | Category of the vehicle making the trip
        primaryFuelType                   | Primary fuel type of the vehicle making the trip
        secondaryFuelType                 | Secondary fuel type of the vehicle making the trip
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
