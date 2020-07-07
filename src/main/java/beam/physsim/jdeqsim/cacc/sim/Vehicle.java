package beam.physsim.jdeqsim.cacc.sim;

import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.groups.PlansConfigGroup;
import org.matsim.core.mobsim.jdeqsim.Scheduler;

public class Vehicle extends org.matsim.core.mobsim.jdeqsim.Vehicle {
    private final Boolean isCACCVehicle;

	//CACC : Cooperative Adaptive Cruise Control
	public Vehicle(Scheduler scheduler, Person ownerPerson, PlansConfigGroup.ActivityDurationInterpretation activityDurationInterpretation, Boolean isCACCVehicle) {

		super(scheduler, ownerPerson, activityDurationInterpretation);
		this.isCACCVehicle=isCACCVehicle;
	}

	public Boolean isCACCVehicle() {
		return isCACCVehicle;
	}
}
