package beam.scoring;

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
import beam.events.scoring.ChangePlugOverheadScoreEvent;
import beam.events.scoring.ChargingCostScoreEvent;
import beam.events.scoring.LegTravelTimeScoreEvent;
import beam.events.scoring.ParkingScoreEvent;
import beam.events.scoring.RangeAnxietyScoreEvent;
import beam.parking.lib.obj.LinkedListValueHashMap;
import beam.scoring.rangeAnxiety.InternalRangeAnxityEvent;

public class EVScoringEventsCollection {

	public LinkedListValueHashMap<Id<Person>, ChargingCostScoreEvent> chargingCostScoreEvents = new LinkedListValueHashMap<Id<Person>, ChargingCostScoreEvent>();
	public LinkedListValueHashMap<Id<Person>, ParkingScoreEvent> parkingScoreEvents = new LinkedListValueHashMap<Id<Person>, ParkingScoreEvent>();
	public LinkedListValueHashMap<Id<Person>, LegTravelTimeScoreEvent> legTravelTimeScoreEvents = new LinkedListValueHashMap<Id<Person>, LegTravelTimeScoreEvent>();
	public LinkedListValueHashMap<Id<Person>, RangeAnxietyScoreEvent> rangeAnxietyScoreEvents = new LinkedListValueHashMap<Id<Person>, RangeAnxietyScoreEvent>();
	public LinkedListValueHashMap<Id<Person>, ChangePlugOverheadScoreEvent> changePlugOverheadEvents = new LinkedListValueHashMap<Id<Person>, ChangePlugOverheadScoreEvent>();
	
	
	public int getTotalNumberOfEvents() {
		
		int numberOfEvents=0;
		
		for (Id<Person> personId: chargingCostScoreEvents.getKeySet()){
			numberOfEvents+=chargingCostScoreEvents.get(personId).size();
		}
		
		for (Id<Person> personId: parkingScoreEvents.getKeySet()){
			numberOfEvents+=parkingScoreEvents.get(personId).size();
		}
		
		for (Id<Person> personId: legTravelTimeScoreEvents.getKeySet()){
			numberOfEvents+=legTravelTimeScoreEvents.get(personId).size();
		}
		
		for (Id<Person> personId: rangeAnxietyScoreEvents.getKeySet()){
			numberOfEvents+=rangeAnxietyScoreEvents.get(personId).size();
		}
		
		for (Id<Person> personId: changePlugOverheadEvents.getKeySet()){
			numberOfEvents+=changePlugOverheadEvents.get(personId).size();
		}
		
		return numberOfEvents;
	}
	
}
