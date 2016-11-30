package beam.scoring;

import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.Controler;

import beam.EVGlobalData;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.controller.EVController;
import beam.events.ArrivalChargingDecisionEvent;
import beam.events.ArrivalChargingDecisionEventHandler;
import beam.events.BeginChargingSessionEvent;
import beam.events.BeginChargingSessionEventHandler;
import beam.events.DepartureChargingDecisionEvent;
import beam.events.DepartureChargingDecisionEventHandler;
import beam.events.EndChargingSessionEvent;
import beam.events.EndChargingSessionEventHandler;
import beam.events.ParkWithoutChargingEvent;
import beam.events.ParkWithoutChargingEventHandler;
import beam.events.PreChargeEvent;
import beam.events.PreChargeEventHandler;
import beam.events.ReassessDecisionEvent;
import beam.events.ReassessDecisionEventHandler;
import beam.events.scoring.ChangePlugOverheadScoreEvent;
import beam.events.scoring.ChargingCostScoreEvent;
import beam.events.scoring.LegTravelTimeScoreEvent;
import beam.events.scoring.ParkingScoreEvent;
import beam.parking.lib.DebugLib;
import beam.parking.lib.GeneralLib;
import beam.parking.lib.obj.DoubleValueHashMap;
import beam.parking.lib.obj.IntegerValueHashMap;
import beam.scoring.rangeAnxiety.RangeAnxityInternalExternalEventTranslator;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugType;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;
import beam.transEnergySim.vehicles.api.VehicleWithBattery;

public class EVScoreEventGenerator extends RangeAnxityInternalExternalEventTranslator {
	private static final Logger log = Logger.getLogger(EVScoreEventGenerator.class);

	public EVScoreEventGenerator(EVController controler) {
		super(controler);
	}

	@Override
	public void handleEvent(EndChargingSessionEvent event) {
		super.handleEvent(event);
		double sessionStartTime = sessionStartTimes.get(event.getPersonId());
		double sessionEndTime = event.getTime();
		double sessionDuration = GeneralLib.getIntervalDuration(sessionStartTime, sessionEndTime);

		String chargingSiteId = null;
		String plugTypeName = null;
		Integer decisionEventId = null;
		DepartureChargingDecisionEvent lastDepartureChargingDecisionEvent = lastDepartureChargingEvents.get(event.getPersonId());
		ArrivalChargingDecisionEvent lastArrivalChargingDecisionEvent = lastArrivalChargingEvents.get(event.getPersonId());

		if (!(lastDepartureChargingDecisionEvent == null && lastArrivalChargingDecisionEvent == null)) {

			if (lastDepartureChargingDecisionEvent != null && lastArrivalChargingDecisionEvent != null) {
				// remove this check after testing is robust
				DebugLib.stopSystemAndReportInconsistency("inconsistency in agent: " + event.getPersonId());
			} else if (lastDepartureChargingDecisionEvent != null) {
				chargingSiteId = lastDepartureChargingDecisionEvent.getChargingSiteId();
				plugTypeName = lastDepartureChargingDecisionEvent.getPlugType();
				decisionEventId = lastDepartureChargingDecisionEvent.getDecisionEventId();
			} else if (lastArrivalChargingDecisionEvent != null) {
				chargingSiteId = lastArrivalChargingDecisionEvent.getChargingSiteId();
				plugTypeName = lastArrivalChargingDecisionEvent.getPlugType();
				decisionEventId = lastArrivalChargingDecisionEvent.getDecisionEventId();
			}

			if(chargingSiteId!=null){
				double chargingCost;
				double chargingScore;

				ChargingSite chargingSite = EVGlobalData.data.chargingInfrastructureManager.getChargingSite(chargingSiteId);
				ChargingPlugType chargingPlug = EVGlobalData.data.chargingInfrastructureManager.getChargingPlugTypeByName(plugTypeName);
				VehicleWithBattery vehicle = PlugInVehicleAgent.getAgent(event.getPersonId()).getVehicleWithBattery();
				chargingCost = chargingSite.getChargingCost(sessionStartTime, sessionDuration, chargingPlug, vehicle);
				chargingScore = EVGlobalData.data.BETA_CHARGING_COST * chargingCost;

				EVGlobalData.data.eventLogger
						.processEvent(new ChargingCostScoreEvent(sessionEndTime, event.getPersonId(), decisionEventId, chargingScore));
			}
		}
	}

	private boolean isHomeCharging(String chargingSiteId) {
		return Integer.parseInt(chargingSiteId) < 0;
	}

	@Override
	public void handleEvent(PersonArrivalEvent event) {
		super.handleEvent(event);
		Id<Person> personId = event.getPersonId();

		handleLegTravelTime(event, personId);

		if (isLastArrivalOfDay(personId)) {
			double lastArrivalTimeOfDay = event.getTime();
			ArrivalChargingDecisionEvent lastArrivalChargingDecisionEvent = lastArrivalChargingEvents.get(personId);

			if (!(lastArrivalChargingDecisionEvent==null)){
				String chargingSiteId = lastArrivalChargingDecisionEvent.getChargingSiteId();

				double parkingScore;

				// TODO: change choice to enumeration to make it type safe
				if (lastArrivalChargingDecisionEvent.getChoice().equalsIgnoreCase("charge")) {
					double parkingDuration = GeneralLib.getIntervalDuration(lastArrivalTimeOfDay, firstDepartureTimeOfDay.get(event.getPersonId()));
					parkingScore = getParkingScore(personId, chargingSiteId, parkingDuration);
				} else if (lastArrivalChargingDecisionEvent.getChoice().equalsIgnoreCase("park")) {
					// TODO: here it is assumed that there is no parking score if no
					// charging is present
					// this could be changed in phase 2
					parkingScore = 0;
				} else {
					DebugLib.stopSystemAndReportInconsistency();
					parkingScore = 0;
				}

				EVGlobalData.data.eventLogger.processEvent(new ParkingScoreEvent(lastArrivalTimeOfDay, event.getPersonId(),
						activityElementIndex.get(event.getPersonId()) * 2, parkingScore));
			}

		}

	}

	private void handleLegTravelTime(PersonArrivalEvent event, Id<Person> personId) {
		double legTravelTimeScore = GeneralLib.getIntervalDuration(lastDepartureTime.get(personId), event.getTime()) * EVGlobalData.data.BETA_LEG_TRAVEL_TIME;

		EVGlobalData.data.eventLogger.processEvent(
				new LegTravelTimeScoreEvent(event.getTime(), event.getPersonId(), (activityElementIndex.get(personId) * 2) - 1, legTravelTimeScore));
	}

	@Override
	public void handleEvent(PersonDepartureEvent event) {
		super.handleEvent(event);
		Id<Person> personId = event.getPersonId();

		if (lastArrivalTime.containsKey(event.getPersonId())) {
			ArrivalChargingDecisionEvent lastArrivalChargingDecisionEvent = lastArrivalChargingEvents.get(event.getPersonId());

			if(lastArrivalChargingDecisionEvent==null){
				log.info("Unexpected null value for lastArrivalChargingDecisionEvent");
				return;
			}
			String chargingSiteId = lastArrivalChargingDecisionEvent.getChargingSiteId();

			double parkingScore = 0;

			// TODO: change choice to enumeration to make it type safe
			if (lastArrivalChargingDecisionEvent.getChoice().equalsIgnoreCase("charge")) {
				double parkingDuration = GeneralLib.getIntervalDuration(lastArrivalTime.get(event.getPersonId()), event.getTime());
				parkingScore = getParkingScore(personId, chargingSiteId, parkingDuration);
			} else if (lastArrivalChargingDecisionEvent.getChoice().equalsIgnoreCase("park")) {
				// TODO: here it is assumed that there is no parking score if no
				// charging is present
				// this could be changed in phase 2
				parkingScore = 0;
			} else {
				DebugLib.stopSystemAndReportInconsistency();
				parkingScore = 0;
			}

			EVGlobalData.data.eventLogger.processEvent(
					new ParkingScoreEvent(event.getTime(), event.getPersonId(), activityElementIndex.get(event.getPersonId()) * 2, parkingScore));
		}
	}

	private double getParkingScore(Id<Person> personId, String chargingSiteId, double parkingDuration) {
		double parkingScore;
		ChargingSite chargingSite = EVGlobalData.data.chargingInfrastructureManager.getChargingSite(chargingSiteId);
		double parkingCost = chargingSite.getParkingCost(lastArrivalTime.get(personId), parkingDuration);

		Coord activityCoordinate = ((Activity) persons.get(personId).getSelectedPlan().getPlanElements().get(activityElementIndex.get(personId) * 2))
				.getCoord();
		double parkingDistance = GeneralLib.getDistance(chargingSite.getCoord(), activityCoordinate)
				* EVGlobalData.data.WALK_DISTANCE_ADJUSTMENT_FACTOR;
		double parkingWalkTime = parkingDistance / EVGlobalData.data.AVERAGE_WALK_SPEED_TO_CHARGING_STATION * 2;

		parkingScore = EVGlobalData.data.BETA_PARKING_COST * parkingCost + parkingWalkTime * EVGlobalData.data.BETA_PARKING_WALK_TIME;
		return parkingScore;
	}

	@Override
	public void handleEvent(BeginChargingSessionEvent event) {
		super.handleEvent(event);
		
		PreChargeEvent preChargeEvent = lastPrechargeEvent.get(event.getPersonId());
		if (preChargeEvent.getTime() != event.getTime()) {
			// plug change detected
			EVGlobalData.data.eventLogger.processEvent(new ChangePlugOverheadScoreEvent(event.getTime(), event.getPersonId(),
					preChargeEvent.getDecisionEventId(), EVGlobalData.data.OVERHEAD_SCORE_PLUG_CHANGE));
		}
	}

}
