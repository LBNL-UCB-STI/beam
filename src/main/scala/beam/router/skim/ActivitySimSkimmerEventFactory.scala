package beam.router.skim

import beam.router.model.EmbodiedBeamTrip
import beam.router.skim.core.{AbstractSkimmerEvent, AbstractSkimmerEventFactory}
import beam.sim.config.BeamConfig

class ActivitySimSkimmerEventFactory(beamConfig: BeamConfig) extends AbstractSkimmerEventFactory {
  val activitySimSkimmerName: String = beamConfig.beam.router.skim.activity_sim_skimmer.name

  override def createEvent(
    origin: String,
    destination: String,
    eventTime: Double,
    trip: EmbodiedBeamTrip,
    generalizedTimeInHours: Double,
    generalizedCost: Double,
    energyConsumption: Double
  ): AbstractSkimmerEvent = ActivitySimSkimmerEvent(
    origin,
    destination,
    eventTime,
    trip,
    generalizedTimeInHours,
    generalizedCost,
    energyConsumption,
    activitySimSkimmerName
  )

  def createEvent(
    origin: String,
    destination: String,
    eventTime: Double,
    trip: EmbodiedBeamTrip,
    generalizedTimeInHours: Double,
    generalizedCost: Double,
    energyConsumption: Double,
    pathTypeOverride: Option[ActivitySimPathType],
    costOverrideInDollars: Option[Double],
    bridgeTollInCents: Double = 0.0,
    valueTollInCents: Double = 0.0
  ): AbstractSkimmerEvent = ActivitySimSkimmerEvent(
    origin,
    destination,
    eventTime,
    trip,
    generalizedTimeInHours,
    generalizedCost,
    energyConsumption,
    activitySimSkimmerName,
    pathTypeOverride,
    costOverrideInDollars,
    bridgeTollInCents,
    valueTollInCents
  )
}
