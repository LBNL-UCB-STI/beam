package beam.basicTests;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.population.Person;

import beam.events.ArrivalChargingDecisionEvent;
import beam.events.BeginChargingSessionEvent;
import beam.events.DepartureChargingDecisionEvent;
import beam.events.EndChargingSessionEvent;
import beam.events.ParkWithoutChargingEvent;
import beam.events.PreChargeEvent;
import beam.events.ReassessDecisionEvent;
import beam.parking.lib.obj.LinkedListValueHashMap;
import beam.scoring.rangeAnxiety.InternalRangeAnxityEvent;

public class EVEventsCollection {

	public LinkedListValueHashMap<Id<Person>, PersonDepartureEvent> personDepartureEvents = new LinkedListValueHashMap<Id<Person>, PersonDepartureEvent>();
	public LinkedListValueHashMap<Id<Person>, PersonArrivalEvent> personArrivalEvents = new LinkedListValueHashMap<Id<Person>, PersonArrivalEvent>();
	public LinkedListValueHashMap<Id<Person>, ArrivalChargingDecisionEvent> arrivalChargingDecisionEvents = new LinkedListValueHashMap<Id<Person>, ArrivalChargingDecisionEvent>();
	public LinkedListValueHashMap<Id<Person>, ReassessDecisionEvent> reassessDecisionEvents = new LinkedListValueHashMap<Id<Person>, ReassessDecisionEvent>();
	public LinkedListValueHashMap<Id<Person>, DepartureChargingDecisionEvent> departureChargingDecisionEvents = new LinkedListValueHashMap<Id<Person>, DepartureChargingDecisionEvent>();
	public LinkedListValueHashMap<Id<Person>, BeginChargingSessionEvent> beginChargingSessionEvents = new LinkedListValueHashMap<Id<Person>, BeginChargingSessionEvent>();
	public LinkedListValueHashMap<Id<Person>, EndChargingSessionEvent> endChargingSessionEvents = new LinkedListValueHashMap<Id<Person>, EndChargingSessionEvent>();
	public LinkedListValueHashMap<Id<Person>, ParkWithoutChargingEvent> parkWithoutChargingEvents = new LinkedListValueHashMap<Id<Person>, ParkWithoutChargingEvent>();
	public LinkedListValueHashMap<Id<Person>, PreChargeEvent> preChargeEvents = new LinkedListValueHashMap<Id<Person>, PreChargeEvent>();
	public LinkedListValueHashMap<Id<Person>, InternalRangeAnxityEvent> internalRangeAnxityEvents = new LinkedListValueHashMap<Id<Person>, InternalRangeAnxityEvent>();
	
	public int getTotalNumberOfEvents() {
		
		int numberOfEvents=0;
		
		for (Id<Person> personId: personDepartureEvents.getKeySet()){
			numberOfEvents+=personDepartureEvents.get(personId).size();
		}
		
		for (Id<Person> personId: personArrivalEvents.getKeySet()){
			numberOfEvents+=personArrivalEvents.get(personId).size();
		}
		
		for (Id<Person> personId: arrivalChargingDecisionEvents.getKeySet()){
			numberOfEvents+=arrivalChargingDecisionEvents.get(personId).size();
		}
		
		for (Id<Person> personId: reassessDecisionEvents.getKeySet()){
			numberOfEvents+=reassessDecisionEvents.get(personId).size();
		}
		
		for (Id<Person> personId: departureChargingDecisionEvents.getKeySet()){
			numberOfEvents+=departureChargingDecisionEvents.get(personId).size();
		}
		
		for (Id<Person> personId: beginChargingSessionEvents.getKeySet()){
			numberOfEvents+=beginChargingSessionEvents.get(personId).size();
		}
		
		for (Id<Person> personId: endChargingSessionEvents.getKeySet()){
			numberOfEvents+=endChargingSessionEvents.get(personId).size();
		}
		
		for (Id<Person> personId: parkWithoutChargingEvents.getKeySet()){
			numberOfEvents+=parkWithoutChargingEvents.get(personId).size();
		}
		
		for (Id<Person> personId: preChargeEvents.getKeySet()){
			numberOfEvents+=preChargeEvents.get(personId).size();
		}
		
		for (Id<Person> personId: internalRangeAnxityEvents.getKeySet()){
			numberOfEvents+=internalRangeAnxityEvents.get(personId).size();
		}
		
		return numberOfEvents;
	}
	
}
