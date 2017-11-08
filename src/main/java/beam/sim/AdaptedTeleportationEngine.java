package beam.sim;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.qsim.InternalInterface;
import org.matsim.core.mobsim.qsim.interfaces.DepartureHandler;
import org.matsim.core.mobsim.qsim.interfaces.MobsimEngine;

import beam.EVGlobalData;
import beam.charging.vehicle.PlugInVehicleAgent;

import javax.inject.Inject;

/**
 * Includes all agents that have transportation modes unknown to the
 * NetsimEngine (often all != "car") or have two activities on the same link
 */
public final class AdaptedTeleportationEngine implements DepartureHandler, MobsimEngine{
	private static final Logger log = Logger.getLogger(AdaptedTeleportationEngine.class);
	private InternalInterface internalInterface;

	@Inject
	public AdaptedTeleportationEngine(Scenario scenario, EventsManager eventsManager) {
	}

	@Override
	public boolean handleDeparture(double now, MobsimAgent agent, Id<Link> linkId) {
		PlugInVehicleAgent pevAgent = PlugInVehicleAgent.getAgent(agent.getId());
		// TODO: doesn't this need to be checked if current trip is ev trip?
		// 
		
		if(pevAgent!=null){
			pevAgent.handleDeparture();
			return true;
		}else{
			return false;
		}
	}

	@Override
	public void doSimStep(double now) {
		EVGlobalData.data.now = now;
		EVGlobalData.data.scheduler.doSimStep(now);
	}

	@Override
	public void onPrepareSim() {
		PlugInVehicleAgent.setInternalInterface(internalInterface);
	}

	@Override
	public void afterSim() {
		// TODO decide if we need to throw PersonStuckEvent, if so, we need to get the mobsim agents somehow
		
//		double now = internalInterface.getMobsim().getSimTimer().getTimeOfDay();
//		for (Tuple<Double, MobsimAgent> entry : internalInterface.getMobsim().getScenario().getPopulation().getPersons().values().iterator().next().) {
//			MobsimAgent agent = entry.getSecond();
//			eventsManager.processEvent(new PersonStuckEvent(now, agent.getId(), agent.getDestinationLinkId(), agent.getMode()));
//		}
//		teleportationList.clear();
	}

	@Override
	public void setInternalInterface(InternalInterface internalInterface) {
		this.internalInterface = internalInterface;
	}

	public InternalInterface getInternalInterface() {
		return this.internalInterface;
	}

}