package beam.scoring;

import java.util.HashMap;
import java.util.LinkedList;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.population.Person;

import beam.events.ArrivalChargingDecisionEvent;
import beam.events.ArrivalChargingDecisionEventHandler;
import beam.events.BeginChargingSessionEvent;
import beam.events.BeginChargingSessionEventHandler;
import beam.events.DepartureChargingDecisionEvent;
import beam.events.DepartureChargingDecisionEventHandler;
import beam.events.EndChargingSessionEvent;
import beam.events.EndChargingSessionEventHandler;
import beam.events.IdentifiableDecisionEvent;
import beam.events.ParkWithoutChargingEvent;
import beam.events.ParkWithoutChargingEventHandler;
import beam.events.PreChargeEvent;
import beam.events.PreChargeEventHandler;
import beam.events.ReassessDecisionEvent;
import beam.events.ReassessDecisionEventHandler;
import beam.events.scoring.ChangePlugOverheadScoreEvent;
import beam.events.scoring.ChangePlugOverheadScoreEventHandler;
import beam.events.scoring.ChargingCostScoreEvent;
import beam.events.scoring.ChargingCostScoreEventHandler;
import beam.events.scoring.LegTravelTimeScoreEvent;
import beam.events.scoring.LegTravelTimeScoreEventHandler;
import beam.events.scoring.ParkingScoreEvent;
import beam.events.scoring.ParkingScoreEventHandler;
import beam.events.scoring.RangeAnxietyScoreEvent;
import beam.events.scoring.RangeAnxietyScoreEventHandler;
import beam.parking.lib.obj.LinkedListValueHashMap;
import beam.scoring.rangeAnxiety.InternalRangeAnxityEvent;
import beam.scoring.rangeAnxiety.InternalRangeAnxityEventHandler;

public class EVScoringEventCollector
		implements ChargingCostScoreEventHandler, ParkingScoreEventHandler, LegTravelTimeScoreEventHandler, RangeAnxietyScoreEventHandler, ChangePlugOverheadScoreEventHandler {

	public  HashMap<Integer, EVScoringEventsCollection> eventScoringEventsCollection=new HashMap<>();
	
	private int iterationNumber=0;

	@Override
	public void reset(int iteration) {
		iterationNumber=iteration;
		eventScoringEventsCollection.put(iterationNumber, new EVScoringEventsCollection());
	}

	@Override
	public void handleEvent(ChargingCostScoreEvent event) {
		eventScoringEventsCollection.get(iterationNumber).chargingCostScoreEvents.put(event.getPersonId(), event);
	}

	@Override
	public void handleEvent(ParkingScoreEvent event) {
		eventScoringEventsCollection.get(iterationNumber).parkingScoreEvents.put(event.getPersonId(), event);
	}

	@Override
	public void handleEvent(LegTravelTimeScoreEvent event) {
		eventScoringEventsCollection.get(iterationNumber).legTravelTimeScoreEvents.put(event.getPersonId(), event);
	}

	@Override
	public void handleEvent(RangeAnxietyScoreEvent event) {
		eventScoringEventsCollection.get(iterationNumber).rangeAnxietyScoreEvents.put(event.getPersonId(), event);
	}

	@Override
	public void handleEvent(ChangePlugOverheadScoreEvent event) {
		eventScoringEventsCollection.get(iterationNumber).changePlugOverheadEvents.put(event.getPersonId(), event);
	}

	

	
	

}
