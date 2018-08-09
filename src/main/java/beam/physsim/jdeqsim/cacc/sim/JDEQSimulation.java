/* *********************************************************************** *
 * project: org.matsim.*
 * JavaDEQSim.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2007 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package beam.physsim.jdeqsim.cacc.sim;

import beam.physsim.jdeqsim.cacc.travelTimeFunctions.TravelTimeFunction;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.groups.PlansConfigGroup;
import org.matsim.core.mobsim.framework.Mobsim;
import org.matsim.core.mobsim.jdeqsim.util.Timer;

import javax.inject.Inject;

import java.util.HashMap;


/**
 * The starting point of the whole micro-simulation.
 * @see <a href="http://www.matsim.org/docs/jdeqsim">http://www.matsim.org/docs/jdeqsim</a>
 * @author rashid_waraich
 */
public class JDEQSimulation implements Mobsim {

	private final static Logger log = Logger.getLogger(JDEQSimulation.class);

	private final JDEQSimConfigGroup config;
	protected Scenario scenario;
	private final EventsManager events;
	////////CHANGES/////////
	public static HashMap<String ,Boolean> isCACCVehicle;



	protected final PlansConfigGroup.ActivityDurationInterpretation activityDurationInterpretation;

	@Inject
	public JDEQSimulation(final JDEQSimConfigGroup config, final Scenario scenario, final EventsManager events, final HashMap<String, Boolean> isCACCVehicle, TravelTimeFunction travelTimeFunction) {
		Road.setConfig(config);
		Message.setEventsManager(events);
		Road.setTravelTimeFunction(travelTimeFunction);
		this.isCACCVehicle = isCACCVehicle;
		this.config = config;
		this.scenario = scenario;
		this.events = events;
		this.activityDurationInterpretation = this.scenario.getConfig().plans().getActivityDurationInterpretation();
	}

	@Override
	public void run() {
		Road road;
		events.initProcessing();
		Timer t = new Timer();
		t.startTimer();

		Scheduler scheduler = new Scheduler(new MessageQueue(), config.getSimulationEndTime());
		Road.setAllRoads(new HashMap<Id<Link>, Road>());

		// initialize network

		for (Link link : this.scenario.getNetwork().getLinks().values()) {
			road = new Road(scheduler, link);
			Road.getAllRoads().put(link.getId(), road);
		}



		// TODO: remember which vehicles are cacc or provide from main program
		// use same for doing analysis on how many vehicles are on network which are caccs
		//

		for (Person person : this.scenario.getPopulation().getPersons().values()) {
			new Vehicle(scheduler, person, activityDurationInterpretation,isCACCVehicle.get(person.getId())); // the vehicle registers itself to the scheduler
		}

		scheduler.startSimulation();

		t.endTimer();
		log.info("Time needed for one iteration (only JDEQSimulation part): " + t.getMeasuredTime() + "[ms]");
		events.finishProcessing();
	}
}
