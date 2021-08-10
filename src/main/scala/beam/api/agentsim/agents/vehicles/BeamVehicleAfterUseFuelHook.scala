package beam.api.agentsim.agents.vehicles

import akka.actor.ActorRef
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.router.model.BeamLeg
import beam.sim.BeamScenario
import beam.utils.NetworkHelper
import org.matsim.core.api.experimental.events.EventsManager

/**
  * At the end of BeamVehicle.useFuel the BeamVehicleAfterUseFuelHook.execute method is called with the parameters shared below.
  * An implementation of this hook needs to be registered through the [[BeamCustomizationAPI]]
  */
trait BeamVehicleAfterUseFuelHook {

  def execute(
    beamLeg: BeamLeg,
    beamScenario: BeamScenario,
    networkHelper: NetworkHelper,
    eventsManager: EventsManager,
    eventBuilder: ActorRef,
    beamVehicle: BeamVehicle
  )

}
