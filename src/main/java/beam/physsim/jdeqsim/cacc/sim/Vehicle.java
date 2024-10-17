package beam.physsim.jdeqsim.cacc.sim;

import java.util.HashMap;

import beam.utils.metrics.TemporalEventCounter;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.groups.PlansConfigGroup;
import org.matsim.core.mobsim.jdeqsim.MessageFactory;
import org.matsim.core.mobsim.jdeqsim.Road;
import org.matsim.core.mobsim.jdeqsim.Scheduler;

public class Vehicle extends org.matsim.core.mobsim.jdeqsim.Vehicle {
    private final Boolean isCACCVehicle;
    private final TemporalEventCounter<Id<Link>> doubleParkingCounter;
	private Leg legEnded;
	private double legEndedAtTime;

	//CACC : Cooperative Adaptive Cruise Control
	public Vehicle(Scheduler scheduler, Person ownerPerson, PlansConfigGroup.ActivityDurationInterpretation activityDurationInterpretation,
				   Boolean isCACCVehicle, HashMap<Id<Link>, Road> allRoads, MessageFactory messageFactory,
				   TemporalEventCounter<Id<Link>> doubleParkingCounter) {

		super(scheduler, ownerPerson, activityDurationInterpretation, allRoads, messageFactory);
		this.isCACCVehicle=isCACCVehicle;
        this.doubleParkingCounter = doubleParkingCounter;
    }

	public Boolean isCACCVehicle() {
		return isCACCVehicle;
	}

	@Override
	public void scheduleEndLegMessage(double scheduleTime, Road road) {
		super.scheduleEndLegMessage(scheduleTime, road);
		legEnded = getCurrentLeg();
		legEndedAtTime = scheduleTime;
	}

	@Override
	public void scheduleStartingLegMessage(double scheduleTime, Road road) {
		super.scheduleStartingLegMessage(scheduleTime, road);
		boolean dp = legEnded != null
				&& Boolean.TRUE.equals(legEnded.getAttributes().getAttribute("ended_with_double_parking"));
		if (dp) {
			doubleParkingCounter.addTemporalEvent(getCurrentLinkId(), legEndedAtTime, scheduleTime);
		}
	}
}
