package beam.playground.physicalSimProtoType.AkkaJDEQSim;

import java.util.HashMap;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup;
import org.matsim.core.mobsim.jdeqsim.util.Timer;
import org.matsim.core.scenario.ScenarioUtils;

import akka.actor.Actor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import beam.playground.beamSimAkkaProtoType.GlobalLibAndConfig;
import beam.playground.beamSimAkkaProtoType.chargingInfrastructure.ChargingInfrastructureManager;
import beam.playground.beamSimAkkaProtoType.scheduler.Scheduler;
import beam.playground.beamSimAkkaProtoType.scheduler.StartSimulationMessage;

public class PhysSimMain {

	public static void main(String[] args) {
		Config config = ConfigUtils.loadConfig(
				"C:/Users/rwaraich/git/matsim_1/examples/scenarios/equil/config_plans1.xml");
		
		Scenario scenario = ScenarioUtils.loadScenario(config);
		
		Person personOne = scenario.getPopulation().getPersons().get(Id.createPersonId("1"));

		clonePerson(scenario, GlobalLibAndConfig.numberOfPeopleInSimulation, personOne);
		
		ActorSystem system = ActorSystem.create("AgentSim");
		
		ActorRef scheduler = system.actorOf(Props.create(Scheduler.class,scenario.getPopulation()),"scheduler");
        
		createRoads(scheduler, scenario, system);
		createVehicles(scheduler, scenario, system);
		
		scheduler.tell(new StartSimulationMessage(), ActorRef.noSender());
        
        system.awaitTermination();
        
	}
	
	public static void clonePerson(Scenario scenario, int numberOfPeople, Person templatePerson) {
		for (int i = 0; i < numberOfPeople; i++) {
			
			Population population = scenario.getPopulation();
			PopulationFactory populationFactory = population.getFactory();

			Person person = populationFactory.createPerson(Id.create("_"+i, Person.class));
			Plan plan=templatePerson.getSelectedPlan();
			person.addPlan(plan);
			person.setSelectedPlan(plan);
			population.addPerson(person);
		}
	}
	
	private static void createRoads(ActorRef scheduler, Scenario scenario, ActorSystem system){
		ActorRef road;
		for (Link link : scenario.getNetwork().getLinks().values()) {
			road = system.actorOf(Props.create(Road.class,scheduler, link),"link: " + link.getId());
			Road.addRoad(link.getId(), road);
		}
	}
	
	private static void createVehicles(ActorRef scheduler, Scenario scenario, ActorSystem system){
		for (Person person : scenario.getPopulation().getPersons().values()) {
			system.actorOf(Props.create(Vehicle.class,scheduler, person, scenario.getConfig().plans().getActivityDurationInterpretation()),"vehicle: " + person.getId());
		}
	}
	
	
}
