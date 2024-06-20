package beam.router.skim.event

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.model.EmbodiedBeamTrip
import beam.router.skim.SkimsUtils
import beam.router.skim.core.ODVehicleTypeSkimmer.{ODVehicleTypeSkimmerInternal, ODVehicleTypeSkimmerKey}
import beam.router.skim.core.{AbstractSkimmerEvent, AbstractSkimmerInternal, AbstractSkimmerKey, ODVehicleTypeSkimmer}
import beam.sim.BeamServices
import beam.utils.MathUtils.doubleToInt
import org.matsim.api.core.v01.Id

/**
  * @author Dmitry Openkov
  */
class ODVehicleTypeSkimmerEvent(
  eventTime: Double,
  origin: Id[TAZ],
  destination: Id[TAZ],
  vehicleTypeId: Id[BeamVehicleType],
  distanceInM: Double,
  travelTimeInS: Double,
  generalizedTimeInHours: Double,
  generalizedCost: Double,
  cost: Double,
  energyConsumption: Double,
  maybePayloadWeightInKg: Option[Double]
) extends AbstractSkimmerEvent(eventTime) {

  override protected val skimName: String = ODVehicleTypeSkimmer.name

  override val getKey: AbstractSkimmerKey =
    ODVehicleTypeSkimmerKey(SkimsUtils.timeToBin(doubleToInt(eventTime)), vehicleTypeId, origin, destination)

  override val getSkimmerInternal: AbstractSkimmerInternal =
    ODVehicleTypeSkimmerInternal(
      travelTimeInS,
      generalizedTimeInHours * 3600,
      generalizedCost,
      distanceInM,
      cost,
      maybePayloadWeightInKg.getOrElse(0),
      energyConsumption
    )
}

object ODVehicleTypeSkimmerEvent {

  def apply(
    eventTime: Double,
    beamServices: BeamServices,
    vehicleTypeId: Id[BeamVehicleType],
    trip: EmbodiedBeamTrip,
    generalizedTimeInHours: Double,
    generalizedCost: Double,
    maybePayloadWeightInKg: Option[Double],
    energyConsumption: Double
  ): ODVehicleTypeSkimmerEvent = {
    import beamServices._
    val correctedTrip = ODSkimmerEvent.correctTrip(trip, trip.tripClassifier)
    val beamLegs = correctedTrip.beamLegs
    val origLeg = beamLegs.head
    val origCoord = geo.wgs2Utm(origLeg.travelPath.startPoint.loc)
    val origTaz = beamScenario.tazTreeMap
      .getTAZ(origCoord.getX, origCoord.getY)
      .tazId
    val destLeg = beamLegs.last
    val destCoord = geo.wgs2Utm(destLeg.travelPath.endPoint.loc)
    val destTaz = beamScenario.tazTreeMap
      .getTAZ(destCoord.getX, destCoord.getY)
      .tazId
    val distanceInM = beamLegs.map(_.travelPath.distanceInM).sum
    val travelTime = correctedTrip.totalTravelTimeInSecs.toDouble
    new ODVehicleTypeSkimmerEvent(
      eventTime,
      origTaz,
      destTaz,
      vehicleTypeId,
      if (distanceInM > 0.0) distanceInM else 1.0,
      travelTime,
      generalizedTimeInHours,
      generalizedCost,
      correctedTrip.costEstimate,
      energyConsumption,
      maybePayloadWeightInKg
    )
  }
}
