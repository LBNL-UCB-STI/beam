package beam.router.skim
import beam.router.model.EmbodiedBeamTrip
import beam.router.skim.core.{AbstractSkimmerEvent, AbstractSkimmerEventFactory}
import beam.router.skim.event.ODSkimmerEvent

class ODSkimmerEventFactory extends AbstractSkimmerEventFactory {
  override def createEvent(
    origin: String,
    destination: String,
    eventTime: Double,
    trip: EmbodiedBeamTrip,
    generalizedTimeInHours: Double,
    generalizedCost: Double,
    energyConsumption: Double
  ): AbstractSkimmerEvent = ODSkimmerEvent(
    origin,
    destination,
    eventTime,
    trip,
    generalizedTimeInHours,
    generalizedCost,
    energyConsumption,
    // If you change this name, make sure it is properly reflected in `AbstractSkimmer.handleEvent`
    skimName = "od-skimmer"
  )
}
