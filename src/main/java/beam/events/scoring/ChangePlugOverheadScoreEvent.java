package beam.events.scoring;

import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import beam.events.DepartureChargingDecisionEvent;
import beam.events.IdentifiableDecisionEvent;

public class ChangePlugOverheadScoreEvent extends EVScoringEvent implements IdentifiableDecisionEvent {

	private int decisionEventId;
	
	public static final String ATTRIBUTE_DECISION_EVENT_ID=DepartureChargingDecisionEvent.ATTRIBUTE_DECISION_EVENT_ID;
	
	public ChangePlugOverheadScoreEvent(double time, Id<Person> personId, int decisionEventId, double score) {
		super(time, personId, score);
		this.decisionEventId = decisionEventId;
	}

	@Override
	public int getDecisionEventId() {
		return decisionEventId;
	}

	@Override
	public String getEventType() {
		return this.getClass().getSimpleName();
	}

	@Override
	public Map<String, String> getAttributes() {
		Map<String, String> attributes = super.getAttributes();
		attributes.put(ATTRIBUTE_DECISION_EVENT_ID, Integer.toString(decisionEventId));
		return attributes;
	}
	
}
