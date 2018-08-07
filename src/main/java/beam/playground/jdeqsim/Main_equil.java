package beam.playground.jdeqsim;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup;
import org.matsim.core.mobsim.qsim.ActivityEngine;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.agents.DefaultAgentFactory;
import org.matsim.core.mobsim.qsim.agents.PopulationAgentSource;
import org.matsim.core.mobsim.qsim.jdeqsimengine.JDEQSimModule;
import org.matsim.core.mobsim.qsim.qnetsimengine.QNetsimEngine;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.Random;

public class Main_equil {
    //	static int numberOfPeopleInSimulation = 1000;
    static double startTimeVariance = 0;
    static double flowCapFactor = 1;
    static double linkCapacity = 10;
    static double gapTravelSpeed = 7.5;
    static boolean isQSim = true;

    @Deprecated // See beam.agentsim.sim.AgentsimServices
    public static void main(String[] args) {

        Random rand = new Random();

        Config config = ConfigUtils.loadConfig(
                "C:/Users/rwaraich/git/matsim_1/examples/scenarios/equil/config.xml");

        Scenario scenario = ScenarioUtils.loadScenario(config);

        Network network = scenario.getNetwork();

        Person personOne = scenario.getPopulation().getPersons().get(Id.createPersonId("1"));


        //clonePerson(scenario, numberOfPeopleInSimulation, personOne);
        // Person personTwo= createPerson(scenario,1,personOne);

        for (Person person : scenario.getPopulation().getPersons().values()) {
            Activity firstActivity = (Activity) person.getSelectedPlan().getPlanElements().get(0);
            firstActivity.setEndTime(firstActivity.getEndTime() + startTimeVariance * rand.nextDouble());
        }

        System.out.println(scenario.getPopulation().getPersons().size());

        EventsManager eventsManager = EventsUtils.createEventsManager(scenario.getConfig());
        eventsManager.initProcessing();

//		LinkStatsCSVWriter linkStats = new LinkStatsCSVWriter(
//				"C://Users//Admin//Documents//Neuer Ordner (13)//examples//equil//linkData.txt",
//				scenario,flowCapFactor);
//		eventsManager.addHandler(linkStats);


        QSim qsim = new QSim(scenario, eventsManager);
        if (isQSim) {

            ActivityEngine activityEngine = new ActivityEngine(eventsManager, qsim.getAgentCounter());
            qsim.addMobsimEngine(activityEngine);
            qsim.addActivityHandler(activityEngine);

            QNetsimEngine netsimEngine = new QNetsimEngine(qsim);
            qsim.addMobsimEngine(netsimEngine);
            qsim.addDepartureHandler(netsimEngine.getDepartureHandler());

        } else {
            JDEQSimConfigGroup configGroup = ConfigUtils.addOrGetModule(qsim.getScenario().getConfig(), JDEQSimConfigGroup.NAME, JDEQSimConfigGroup.class);
            configGroup.setFlowCapacityFactor(flowCapFactor);
            configGroup.setGapTravelSpeed(gapTravelSpeed);

            JDEQSimModule.configure(qsim);
        }


        PopulationAgentSource agentSource = new PopulationAgentSource(scenario.getPopulation(),
                new DefaultAgentFactory(qsim), qsim);
        qsim.addAgentSource(agentSource);
        qsim.run();

        //linkStats.closeFile();

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
