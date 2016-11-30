package beam.scoring.rangeAnxiety;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

public interface InternalRangeAnxityEventHandler {
	public void handleEvent(InternalRangeAnxityEvent event);
	public void reset(int iteration);
	public void generateExternalDailyEvent(Id<Person> personId, double time);
}
