package beam.events.scoring;

import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import beam.events.IdentifiableDecisionEvent;

public class RangeAnxietyScoreEvent extends EVScoringEvent {

	public RangeAnxietyScoreEvent(double time, Id<Person> personId, double score) {
		super(time, personId, score);
	}

	@Override
	public String getEventType() {
		return this.getClass().getSimpleName();
	}

	@Override
	public Map<String, String> getAttributes() {
		Map<String, String> attributes = super.getAttributes();
		return attributes;
	}
	
}
