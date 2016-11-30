package beam.scoring.rangeAnxiety;

import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Person;

import beam.events.DepartureChargingDecisionEvent;


// TODO: due to proper separation of internal and external events in logger, this could be designed differently => refactor.
public class InternalRangeAnxityEvent extends Event {

	// TODO: make variable private and use getters and setters
	private Id<Person> personId;
	private double soc;
	double socAtTime;
	
	public static final String ATTRIBUTE_SOC=DepartureChargingDecisionEvent.ATTRIBUTE_SOC;
	public static final String ATTRIBUTE_PERSON = DepartureChargingDecisionEvent.ATTRIBUTE_PERSON;
	public static final String ATTRIBUTE_SOC_AT_TIME = "socAtTime";
	
	public InternalRangeAnxityEvent(Id<Person> personId, double eventGenerationTime, double socAtTime, double soc) {
		super(eventGenerationTime);
		this.setPersonId(personId);
		this.socAtTime = socAtTime;
		this.setSoc(soc);
	}

	@Override
	public String getEventType() {
		return this.getClass().getSimpleName();
	}

	@Override
	public Map<String, String> getAttributes() {
		final Map<String, String> attributes = super.getAttributes();
		attributes.put(ATTRIBUTE_PERSON, getPersonId().toString());
		attributes.put(ATTRIBUTE_SOC_AT_TIME, Double.toString(socAtTime));
		attributes.put(ATTRIBUTE_SOC, Double.toString(getSoc()));
		return attributes;
	}

	public Id<Person> getPersonId() {
		return personId;
	}

	private void setPersonId(Id<Person> personId) {
		this.personId = personId;
	}

	public double getSoc() {
		return soc;
	}

	private void setSoc(double soc) {
		this.soc = soc;
	}

	
}
