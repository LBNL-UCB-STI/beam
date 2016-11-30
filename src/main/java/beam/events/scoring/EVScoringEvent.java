package beam.events.scoring;

import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.internal.HasPersonId;

import beam.EVGlobalData;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.events.DepartureChargingDecisionEvent;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;

public abstract class EVScoringEvent extends Event {

	private Id<Person> personId;
	private double score;

	public static final String ATTRIBUTE_SCORE="score";
	public static final String ATTRIBUTE_PERSON = DepartureChargingDecisionEvent.ATTRIBUTE_PERSON;
	
	public EVScoringEvent(double time, Id<Person> personId, double score) {
		super(time);
		this.setPersonId(personId);
		this.setScore(score);
	}

	@Override
	public Map<String, String> getAttributes() {
		final Map<String, String> attributes = super.getAttributes();
		attributes.put(ATTRIBUTE_PERSON,getPersonId().toString());
		attributes.put(ATTRIBUTE_SCORE, Double.toString(getScore()));
		return attributes;
	}

	public double getScore() {
		return score;
	}

	private void setScore(double score) {
		this.score = score;
	}

	public Id<Person> getPersonId() {
		return personId;
	}

	private void setPersonId(Id<Person> personId) {
		this.personId = personId;
	}

}
