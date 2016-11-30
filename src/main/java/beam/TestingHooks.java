package beam;

import static org.junit.Assert.assertFalse;

import java.util.HashMap;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import beam.replanning.ChargingStrategyManager;
import beam.replanning.StrategySequence;

public class TestingHooks {

	public HashMap<Id<Person>, StrategySequence> testCase_chargingStrategySequence;
	public boolean unexpectedShutDown=false;
	public boolean errorDuringExecution=false;

	public void addTestingHooksBeforeStartOfSimulation() {
		if (EVGlobalData.data.IS_TEST_CASE) {

			if (testCase_chargingStrategySequence != null) {
				for (Id<Person> personId : testCase_chargingStrategySequence.keySet()) {
					Person person = EVGlobalData.data.controler.getScenario().getPopulation().getPersons().get(personId);
					ChargingStrategyManager.data.createReplanableWithStrategySequence(person, testCase_chargingStrategySequence.get(personId));
				}
			}

		}

	}
	
	public void assertNoRunTimeError(){
		assertFalse(EVGlobalData.data.testingHooks.unexpectedShutDown);
		assertFalse(EVGlobalData.data.testingHooks.errorDuringExecution);
	}

}
