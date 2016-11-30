package beam.events.scoring;

import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import beam.events.DepartureChargingDecisionEvent;
import beam.events.IdentifiableDecisionEvent;

public class LegTravelTimeScoreEvent extends EVScoringEvent {

	private int planElementIndex;
	
	public static final String ATTRIBUTE_PLAN_ELEMENT_INDEX="planElementIndex";

	public LegTravelTimeScoreEvent(double time, Id<Person> personId, int planElementIndex, double score) {
		super(time, personId, score);
		this.planElementIndex = planElementIndex;
	}

	@Override
	public String getEventType() {
		return this.getClass().getSimpleName();
	}

	@Override
	public Map<String, String> getAttributes() {
		Map<String, String> attributes = super.getAttributes();
		attributes.put(ATTRIBUTE_PLAN_ELEMENT_INDEX, Integer.toString(planElementIndex));
		return attributes;
	}
	
}
