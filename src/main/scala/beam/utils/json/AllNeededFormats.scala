package beam.utils.json

import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{IntermodalUse, RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.model.RoutingModel.TransitStopsInfo
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import org.matsim.vehicles.Vehicle

object AllNeededFormats {
  implicit val locationFormat = LocationFormat
  implicit val beamModeFormat = new Format[BeamMode]
  implicit val vehicleIdFormat = new IdFormat[Vehicle]
  implicit val beamVehicleIdFormat = new IdFormat[BeamVehicle]
  implicit val beamVehicleTypeFormat = new IdFormat[BeamVehicleType]

  implicit val spaceTimeFormat = new Format[SpaceTime]
  implicit val streetVehicleFormat = new Format[StreetVehicle]
  implicit val householdAttributesFormat = new Format[HouseholdAttributes]
  implicit val attributesOfIndividualFormat = new Format[AttributesOfIndividual]
  implicit val intermodalUseFormat = new Format[IntermodalUse]
  implicit val routingRequestFormat = new Format[RoutingRequest]

  implicit val transitStopsInfoFormat = new Format[TransitStopsInfo]
  implicit val beamPathFormat = new Format[BeamPath]
  implicit val beamLegFormat = new Format[BeamLeg]
  implicit val embodiedBeamLegFormat = new Format[EmbodiedBeamLeg]
  implicit val embodiedBeamTripFormat = new Format[EmbodiedBeamTrip]
  implicit val routingResponseFormat = new Format[RoutingResponse]
}
