package beam.analysis;

import org.matsim.core.api.experimental.events.EventsManager;

import com.google.inject.Inject;

import beam.EVGlobalData;
import beam.events.ArrivalChargingDecisionEvent;
import beam.events.ArrivalChargingDecisionEventHandler;
import beam.events.BeginChargingSessionEvent;
import beam.events.BeginChargingSessionEventHandler;
import beam.events.DepartureChargingDecisionEvent;
import beam.events.DepartureChargingDecisionEventHandler;
import beam.events.EndChargingSessionEvent;
import beam.events.EndChargingSessionEventHandler;
import beam.events.NestedLogitDecisionEvent;
import beam.events.UnplugEvent;
import beam.events.UnplugEventHandler;

public class ChargingLoadProfile implements BeginChargingSessionEventHandler, EndChargingSessionEventHandler,
		DepartureChargingDecisionEventHandler, ArrivalChargingDecisionEventHandler, UnplugEventHandler {
	private EventsManager eventsManager;

	Double writeInteval = 15.0*60.0;
	Double chargingLoadInKw = 0.0;
	Integer numPluggedIn = 0;
	
	@Inject
	ChargingLoadProfile(EventsManager eventsManager) {
		this.eventsManager = eventsManager;
		this.eventsManager.addHandler(this);
		EVGlobalData.data.scheduler.addCallBackMethod(0.0, this ,"writeChargingLoadDataToFile", 0.0, this);
	}

	public void writeChargingLoadDataToFile(){

		// write commands

		// Reschedule this same method to be executed in future
		EVGlobalData.data.scheduler.addCallBackMethod(EVGlobalData.data.now + writeInteval, this ,"writeChargingLoadDataToFile", 0.0, this);
	}

	@Override
	public void reset(int iteration) {
	}

	@Override
	public void handleEvent(BeginChargingSessionEvent event) {
		numPluggedIn++;
//		chargingLoadInKw += event.kWOfPlug();
	}

	@Override
	public void handleEvent(EndChargingSessionEvent event) {
//		chargingLoadInKw -= event.kwOfPlug();
	}

	@Override
	public void handleEvent(DepartureChargingDecisionEvent event) {
	}

	@Override
	public void handleEvent(ArrivalChargingDecisionEvent event) {
	}

	@Override
	public void handleEvent(UnplugEvent event) {
		numPluggedIn--;
	}

}
