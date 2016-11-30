package beam.scoring;

import java.util.Collection;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.controler.listener.AfterMobsimListener;

import beam.EVGlobalData;
import beam.controller.EVController;
import beam.events.scoring.ChangePlugOverheadScoreEvent;
import beam.events.scoring.ChangePlugOverheadScoreEventHandler;
import beam.events.scoring.ChargingCostScoreEvent;
import beam.events.scoring.ChargingCostScoreEventHandler;
import beam.events.scoring.EVScoringEvent;
import beam.events.scoring.LegTravelTimeScoreEvent;
import beam.events.scoring.LegTravelTimeScoreEventHandler;
import beam.events.scoring.ParkingScoreEvent;
import beam.events.scoring.ParkingScoreEventHandler;
import beam.events.scoring.RangeAnxietyScoreEvent;
import beam.events.scoring.RangeAnxietyScoreEventHandler;
import beam.parking.lib.obj.DoubleValueHashMap;
import beam.replanning.ChargingStrategyManager;
import beam.replanning.StrategySequence;

// TODO: perform test that these scores are added up in the end 
public class EVScoreAccumulator implements ChargingCostScoreEventHandler, AfterMobsimListener, ParkingScoreEventHandler, LegTravelTimeScoreEventHandler, RangeAnxietyScoreEventHandler, ChangePlugOverheadScoreEventHandler {

	private static final Logger log = Logger.getLogger(EVScoreAccumulator.class);
	private static final boolean loggingOn=false;
	
	DoubleValueHashMap<Id<Person>> evScores;

	public EVScoreAccumulator(EVController controler) {
		controler.getEvents().addHandler(this);
		controler.addControlerListener(this);
	}

	@Override
	public void reset(int iteration) {
		evScores = new DoubleValueHashMap<>();
	}

	@Override
	public void handleEvent(ChargingCostScoreEvent event) {
		evScores.incrementBy(event.getPersonId(), event.getScore());
		logEventScore(event);
	}

	private void logEventScore(EVScoringEvent event) {
		if (loggingOn){
			log.info(event.getScore());
		}
	}
	
	@Override
	public void handleEvent(ParkingScoreEvent event) {
		evScores.incrementBy(event.getPersonId(), event.getScore());
		logEventScore(event);
	}

	@Override
	public void notifyAfterMobsim(AfterMobsimEvent event) {
		Collection<? extends Person> persons = event.getServices().getScenario().getPopulation().getPersons().values();
		for (Person person : persons) {
			Id<Person> personId = person.getId();
			ChargingStrategyManager.data.getReplanable(personId).getSelectedEvDailyPlan().getChargingStrategiesForTheDay()
					.addScore(evScores.get(personId));
		}
	}

	@Override
	public void handleEvent(RangeAnxietyScoreEvent event) {
		evScores.incrementBy(event.getPersonId(), event.getScore());
		logEventScore(event);
	}

	@Override
	public void handleEvent(LegTravelTimeScoreEvent event) {
		evScores.incrementBy(event.getPersonId(), event.getScore());
		logEventScore(event);
	}

	@Override
	public void handleEvent(ChangePlugOverheadScoreEvent event) {
		evScores.incrementBy(event.getPersonId(), event.getScore());
		logEventScore(event);
	}
	
}
