package beam.playground.beamSimAkkaProtoType;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import beam.playground.beamSimAkkaProtoType.chargingInfrastructure.ChargingInfrastructureManager;
import beam.playground.beamSimAkkaProtoType.scheduler.Scheduler;
import beam.playground.beamSimAkkaProtoType.scheduler.StartSimulationMessage;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup;
import org.matsim.core.scenario.ScenarioUtils;

public class BeamSimAkkaMain {

    public static void main(String[] args) {
        // TODO: read Plans
        // TODO: introduce infrastructure (3 times more chargers than cars).


//		Config config = ConfigUtils.loadConfig(
//		"C:/Users/rwaraich/git/matsim_1/examples/scenarios/equil/config2000.xml");

        Config config = ConfigUtils.loadConfig(
                "C:/Users/rwaraich/git/matsim_1/examples/scenarios/equil/config_plans1.xml");


        Scenario scenario = ScenarioUtils.loadScenario(config);

        Person personOne = scenario.getPopulation().getPersons().get(Id.createPersonId("1"));


        clonePerson(scenario, GlobalLibAndConfig.numberOfPeopleInSimulation, personOne);
        //// Person personTwo= createPerson(scenario,1,personOne);


        JDEQSimConfigGroup jdeqSimConfigGroup = new JDEQSimConfigGroup();

        ActorSystem system = ActorSystem.create("AgentSim");

        ActorRef chargingInfrastructureManager = system.actorOf(Props.create(ChargingInfrastructureManager.class, GlobalLibAndConfig.numberOfPeopleInSimulation), "chargingInfrastructureManager");

        ActorRef scheduler = system.actorOf(Props.create(Scheduler.class, scenario.getPopulation(), chargingInfrastructureManager), "scheduler");

        scheduler.tell(new StartSimulationMessage(), ActorRef.noSender());

        system.awaitTermination();
    }

    public static void clonePerson(Scenario scenario, int numberOfPeople, Person templatePerson) {
        for (int i = 0; i < numberOfPeople; i++) {

            Population population = scenario.getPopulation();
            PopulationFactory populationFactory = population.getFactory();

            Person person = populationFactory.createPerson(Id.create("_" + i, Person.class));
            Plan plan = templatePerson.getSelectedPlan();
            person.addPlan(plan);
            person.setSelectedPlan(plan);
            population.addPerson(person);
        }
    }

}
