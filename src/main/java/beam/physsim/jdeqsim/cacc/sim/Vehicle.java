package beam.physsim.jdeqsim.cacc.sim;

import java.util.HashMap;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.groups.PlansConfigGroup;
import org.matsim.core.mobsim.jdeqsim.MessageFactory;
import org.matsim.core.mobsim.jdeqsim.Road;
import org.matsim.core.mobsim.jdeqsim.Scheduler;

public class Vehicle extends org.matsim.core.mobsim.jdeqsim.Vehicle {
    private final Boolean isCACCVehicle;

	//CACC : Cooperative Adaptive Cruise Control
	public Vehicle(Scheduler scheduler, Person ownerPerson, PlansConfigGroup.ActivityDurationInterpretation activityDurationInterpretation,
				   Boolean isCACCVehicle, HashMap<Id<Link>, Road> allRoads, MessageFactory messageFactory) {

		super(scheduler, ownerPerson, activityDurationInterpretation, allRoads, messageFactory);
		this.isCACCVehicle=isCACCVehicle;
	}

	public Boolean isCACCVehicle() {
		return isCACCVehicle;
	}
}
