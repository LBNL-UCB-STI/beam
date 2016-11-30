package beam.scoring.rangeAnxiety;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import beam.EVGlobalData;
import beam.events.scoring.RangeAnxietyScoreEvent;
import beam.parking.lib.obj.DoubleValueHashMap;

public class LogRangeAnxityScoringEventsAtEndOfDay implements InternalRangeAnxityEventHandler {
	// TODO: implement this class in the end as we want it.

	public double BETA_SOC = -1.0;

	DoubleValueHashMap<Id<Person>> scores;

	@Override
	public void handleEvent(InternalRangeAnxityEvent event) {
		scores.incrementBy(event.getPersonId(), event.getSoc() * EVGlobalData.data.RANGE_ANXITY_SAMPLING_INTERVAL_IN_SECONDS * BETA_SOC);
	}

	@Override
	public void reset(int iteration) {
		scores = new DoubleValueHashMap<>();
	}

	@Override
	public void generateExternalDailyEvent(Id<Person> personId, double time) {
		EVGlobalData.data.eventLogger.processEvent(new RangeAnxietyScoreEvent(time, personId, scores.get(personId)));
	}

}
