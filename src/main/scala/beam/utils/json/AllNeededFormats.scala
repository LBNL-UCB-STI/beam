package beam.utils.json

import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{IntermodalUse, RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.model.RoutingModel.TransitStopsInfo
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

object AllNeededFormats {
  implicit val locationFormat: LocationFormat.type = LocationFormat
  implicit val beamModeFormat: Format[BeamMode] = new Format[BeamMode]
  implicit val vehicleIdFormat: IdFormat[Vehicle] = new IdFormat[Vehicle]
  implicit val beamVehicleIdFormat: IdFormat[BeamVehicle] = new IdFormat[BeamVehicle]
  implicit val personIdFormat: IdFormat[Person] = new IdFormat[Person]
  implicit val beamVehicleTypeFormat: IdFormat[BeamVehicleType] = new IdFormat[BeamVehicleType]

  implicit val spaceTimeFormat: Format[SpaceTime] = new Format[SpaceTime]
  implicit val streetVehicleFormat: Format[StreetVehicle] = new Format[StreetVehicle]
  implicit val householdAttributesFormat: Format[HouseholdAttributes] = new Format[HouseholdAttributes]
  implicit val attributesOfIndividualFormat: Format[AttributesOfIndividual] = new Format[AttributesOfIndividual]
  implicit val intermodalUseFormat: Format[IntermodalUse] = new Format[IntermodalUse]
  implicit val routingRequestFormat: Format[RoutingRequest] = new Format[RoutingRequest]

  implicit val transitStopsInfoFormat: Format[TransitStopsInfo] = new Format[TransitStopsInfo]
  implicit val beamPathFormat: Format[BeamPath] = new Format[BeamPath]
  implicit val beamLegFormat: Format[BeamLeg] = new Format[BeamLeg]
  implicit val embodiedBeamLegFormat: Format[EmbodiedBeamLeg] = new Format[EmbodiedBeamLeg]
  implicit val embodiedBeamTripFormat: Format[EmbodiedBeamTrip] = new Format[EmbodiedBeamTrip]
  implicit val routingResponseFormat: Format[RoutingResponse] = new Format[RoutingResponse]

  implicit val manifestFormat: ManifestFormat.type = ManifestFormat
}
