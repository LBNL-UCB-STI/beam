package beam.router.skim.event

import beam.agentsim.agents.vehicles.VehicleEmissions.{Emissions, EmissionsProfile}
import beam.router.skim.core.EmissionsSkimmer.{EmissionsSkimmerInternal, EmissionsSkimmerKey}
import beam.router.skim.core.{AbstractSkimmerEvent, AbstractSkimmerInternal, AbstractSkimmerKey}
import beam.sim.BeamServices

case class EmissionsSkimmerEvent(
  time: Double,
  linkId: Int,
  zone: String,
  vehicleType: String,
  emissions: Emissions,
  emissionsProcess: EmissionsProfile.EmissionsProcess,
  travelTime: Double,
  energyConsumption: Double,
  parkingDuration: Double,
  beamServices: BeamServices
) extends AbstractSkimmerEvent(time) {
  override protected val skimName: String = beamServices.beamConfig.beam.router.skim.emissions_skimmer.name

  override def getKey: AbstractSkimmerKey =
    EmissionsSkimmerKey(linkId.toString, vehicleType, (time / 3600).toInt % 24, zone, emissionsProcess)

  override def getSkimmerInternal: AbstractSkimmerInternal =
    EmissionsSkimmerInternal(
      emissions,
      travelTime,
      energyConsumption,
      1,
      beamServices.matsimServices.getIterationNumber + 1
    )
}
