package beam.physsim.jdeqsim.cacc.sim;

import java.util.List;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.groups.PlansConfigGroup;
import org.matsim.core.mobsim.jdeqsim.DeadlockPreventionMessage;
import org.matsim.core.mobsim.jdeqsim.MessageFactory;
import org.matsim.core.mobsim.jdeqsim.Scheduler;
import org.matsim.core.mobsim.jdeqsim.SimUnit;
import org.matsim.core.population.routes.NetworkRoute;


public class Vehicle extends org.matsim.core.mobsim.jdeqsim.Vehicle {

	private static final Logger log = Logger.getLogger(Vehicle.class);
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
