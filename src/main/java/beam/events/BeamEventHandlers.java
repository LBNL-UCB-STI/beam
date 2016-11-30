package beam.events;

import org.matsim.core.api.experimental.events.EventsManager;

import com.google.inject.Inject;

public class BeamEventHandlers implements PreChargeEventHandler, BeginChargingSessionEventHandler, EndChargingSessionEventHandler,
		DepartureChargingDecisionEventHandler, ArrivalChargingDecisionEventHandler, UnplugEventHandler, NestedLogitDecisionEventHandler{
	private EventsManager eventsManager;

	@Inject
	BeamEventHandlers(EventsManager eventsManager) {
		this.eventsManager = eventsManager;
		this.eventsManager.addHandler(this);
	}

	@Override
	public void reset(int iteration) {
	}

	@Override
	public void handleEvent(PreChargeEvent event) {
	}

	@Override
	public void handleEvent(BeginChargingSessionEvent event) {
	}

	@Override
	public void handleEvent(EndChargingSessionEvent event) {
	}

	@Override
	public void handleEvent(DepartureChargingDecisionEvent event) {
	}

	@Override
	public void handleEvent(ArrivalChargingDecisionEvent event) {
	}

	@Override
	public void handleEvent(UnplugEvent event) {
	}

	@Override
	public void handleEvent(NestedLogitDecisionEvent event) {
	}
}
