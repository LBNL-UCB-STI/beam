package beam.scoring;

import java.util.HashMap;
import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.Controler;

import beam.controller.EVController;
import beam.events.ArrivalChargingDecisionEvent;
import beam.events.ArrivalChargingDecisionEventHandler;
import beam.events.BeginChargingSessionEvent;
import beam.events.BeginChargingSessionEventHandler;
import beam.events.DepartureChargingDecisionEvent;
import beam.events.DepartureChargingDecisionEventHandler;
import beam.events.EndChargingSessionEvent;
import beam.events.EndChargingSessionEventHandler;
import beam.events.PreChargeEvent;
import beam.events.PreChargeEventHandler;
import beam.events.ReassessDecisionEvent;
import beam.events.ReassessDecisionEventHandler;
import beam.parking.lib.obj.DoubleValueHashMap;
import beam.parking.lib.obj.IntegerValueHashMap;

public class EVScoreDataCollector implements PersonDepartureEventHandler, PersonArrivalEventHandler, ArrivalChargingDecisionEventHandler,
DepartureChargingDecisionEventHandler, BeginChargingSessionEventHandler, EndChargingSessionEventHandler, ReassessDecisionEventHandler, PreChargeEventHandler {

	protected DoubleValueHashMap<Id<Person>> sessionStartTimes;

	// TODO: If needed optimize memory consumption here afterwards and only
	// store information which is really necessary
	protected HashMap<Id<Person>, ArrivalChargingDecisionEvent> lastArrivalChargingEvents;
	protected HashMap<Id<Person>, DepartureChargingDecisionEvent> lastDepartureChargingEvents;
	protected HashMap<Id<Person>, ReassessDecisionEvent> lastReassessDecisionEvents;
	protected HashMap<Id<Person>, PreChargeEvent> lastPrechargeEvent;

	// TODO: if car can be moved after arrival at activity, e.g. to save parking
	// charges (from EV parking to normal parking), we should introduce multiple
	// parking events which we listen to, in order to calculate score
	protected HashMap<Id<Person>, Double> firstDepartureTimeOfDay;
	protected HashMap<Id<Person>, Double> lastArrivalTime;

	protected HashMap<Id<Person>, Double> lastDepartureTime;

	protected HashMap<Id<Person>, Integer> numberOfPlanElements = new HashMap<>();

	protected IntegerValueHashMap<Id<Person>> activityElementIndex;

	protected Map<Id<Person>, ? extends Person> persons;

	public EVScoreDataCollector(EVController controler) {
		controler.getEvents().addHandler(this);

		persons = controler.getScenario().getPopulation().getPersons();

		for (Id<Person> personId : persons.keySet()) {
			numberOfPlanElements.put(personId, persons.get(personId).getSelectedPlan().getPlanElements().size());
		}
	}

	@Override
	public void reset(int iteration) {
		sessionStartTimes = new DoubleValueHashMap<>();
		lastArrivalChargingEvents = new HashMap<>();
		lastDepartureChargingEvents = new HashMap<>();
		lastReassessDecisionEvents=new HashMap<>();

		firstDepartureTimeOfDay = new HashMap<>();
		lastArrivalTime = new HashMap<>();

		activityElementIndex = new IntegerValueHashMap<>();
		lastDepartureTime = new HashMap<>();
		lastPrechargeEvent=new HashMap<>();
	}
	
	@Override
	public void handleEvent(EndChargingSessionEvent event) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void handleEvent(BeginChargingSessionEvent event) {
		sessionStartTimes.put(event.getPersonId(), event.getTime());
	}

	@Override
	public void handleEvent(DepartureChargingDecisionEvent event) {
		lastDepartureChargingEvents.put(event.getPersonId(), event);
		lastArrivalChargingEvents.put(event.getPersonId(), null);
	}

	@Override
	public void handleEvent(ArrivalChargingDecisionEvent event) {
		lastArrivalChargingEvents.put(event.getPersonId(), event);
		lastDepartureChargingEvents.put(event.getPersonId(), null);
	}

	@Override
	public void handleEvent(PersonDepartureEvent event) {
		lastDepartureTime.put(event.getPersonId(), event.getTime());
		
		if (!firstDepartureTimeOfDay.containsKey(event.getPersonId())) {
			firstDepartureTimeOfDay.put(event.getPersonId(), event.getTime());
		}
		
	}

	@Override
	public void handleEvent(PersonArrivalEvent event) {
		Id<Person> personId = event.getPersonId();
		activityElementIndex.increment(personId);
		lastArrivalTime.put(personId, event.getTime());
	}

	@Override
	public void handleEvent(ReassessDecisionEvent event) {
		lastReassessDecisionEvents.put(event.getPersonId(), event);
	}

	protected boolean isLastArrivalOfDay(Id<Person> personId) {
		return activityElementIndex.get(personId) * 2 + 1 == numberOfPlanElements.get(personId);
	}

	@Override
	public void handleEvent(PreChargeEvent event) {
		lastPrechargeEvent.put(event.getPersonId(), event);
	}
	
}
