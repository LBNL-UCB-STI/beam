package beam.basicTests;

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
import beam.parking.lib.obj.LinkedListValueHashMap;
import beam.scoring.rangeAnxiety.InternalRangeAnxityEvent;
import beam.scoring.rangeAnxiety.InternalRangeAnxityEventHandler;

public class EVEventCollector
		implements PersonDepartureEventHandler, PersonArrivalEventHandler, ArrivalChargingDecisionEventHandler, DepartureChargingDecisionEventHandler,
		BeginChargingSessionEventHandler, EndChargingSessionEventHandler, ParkWithoutChargingEventHandler, PreChargeEventHandler, ReassessDecisionEventHandler,InternalRangeAnxityEventHandler {

	public  HashMap<Integer, EVEventsCollection> eventCollection=new HashMap<>();
	private int iterationNumber=0;

	public LinkedList<IdentifiableDecisionEvent> getFilteredDecisionEventList(Id<Person> personId, int decisionEventId,
			LinkedListValueHashMap<Id<Person>, ? extends IdentifiableDecisionEvent> identifiableDecisionEvents) {
		LinkedList<IdentifiableDecisionEvent> result = new LinkedList<>();

		for (IdentifiableDecisionEvent identifyableDecisionEvent : identifiableDecisionEvents.get(personId)) {
			if (identifyableDecisionEvent.getDecisionEventId() == decisionEventId) {
				result.add(identifyableDecisionEvent);
			}
		}

		return result;
	}

	public EVEventCollector(){
		reset(0);
	}
	
	@Override
	public void reset(int iteration) {
		iterationNumber=iteration;
		eventCollection.put(iterationNumber, new EVEventsCollection());
	}

	@Override
	public void handleEvent(PreChargeEvent event) {
		eventCollection.get(iterationNumber).preChargeEvents.put(event.getPersonId(), event);
	}

	@Override
	public void handleEvent(ParkWithoutChargingEvent event) {
		eventCollection.get(iterationNumber).parkWithoutChargingEvents.put(event.getPersonId(), event);
	}

	@Override
	public void handleEvent(EndChargingSessionEvent event) {
		eventCollection.get(iterationNumber).endChargingSessionEvents.put(event.getPersonId(), event);
	}

	@Override
	public void handleEvent(BeginChargingSessionEvent event) {
		eventCollection.get(iterationNumber).beginChargingSessionEvents.put(event.getPersonId(), event);
	}

	@Override
	public void handleEvent(ReassessDecisionEvent event) {
		eventCollection.get(iterationNumber).reassessDecisionEvents.put(event.getPersonId(), event);
	}

	@Override
	public void handleEvent(DepartureChargingDecisionEvent event) {
		eventCollection.get(iterationNumber).departureChargingDecisionEvents.put(event.getPersonId(), event);
	}

	@Override
	public void handleEvent(ArrivalChargingDecisionEvent event) {
		eventCollection.get(iterationNumber).arrivalChargingDecisionEvents.put(event.getPersonId(), event);
	}

	@Override
	public void handleEvent(PersonArrivalEvent event) {
		eventCollection.get(iterationNumber).personArrivalEvents.put(event.getPersonId(), event);
	}

	@Override
	public void handleEvent(PersonDepartureEvent event) {
		eventCollection.get(iterationNumber).personDepartureEvents.put(event.getPersonId(), event);
	}
	
	
	@Override
	public void handleEvent(InternalRangeAnxityEvent event) {
		eventCollection.get(iterationNumber).internalRangeAnxityEvents.put(event.getPersonId(), event);
	}

	@Override
	public void generateExternalDailyEvent(Id<Person> personId, double time) {
		// intentionally left empty, as no need to write out 
	}

}
