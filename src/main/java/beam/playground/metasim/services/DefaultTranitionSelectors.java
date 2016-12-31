package beam.playground.metasim.services;

import java.util.LinkedHashMap;
import java.util.Random;

import org.matsim.analysis.CalcLinkStats;
import org.matsim.analysis.IterationStopWatch;
import org.matsim.analysis.ScoreStats;
import org.matsim.analysis.VolumesAnalyzer;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.listener.ControlerListener;
import org.matsim.core.replanning.StrategyManager;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.LeastCostPathCalculatorFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scoring.ScoringFunctionFactory;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;

import beam.playground.metasim.agents.BeamAgentPopulation;
import beam.playground.metasim.agents.actions.Action;
import beam.playground.metasim.agents.transition.selectors.TransitionSelector;
import beam.playground.metasim.scheduler.Scheduler;
import beam.playground.metasim.services.config.BeamConfigGroup;
import beam.sim.traveltime.BeamRouter;

public class DefaultTranitionSelectors {
	private LinkedHashMap<Action,TransitionSelector> transitionSelectors = new LinkedHashMap<>();

	@Inject
	public DefaultTranitionSelectors() {
		super();
	}

	public void putDefaultTranitionSelectorForAction(Action action, TransitionSelector transitionSelector) {
		transitionSelectors.put(action,transitionSelector);
	}

	public TransitionSelector getDefaultTranitionSelectorForAction(Action action) {
		return transitionSelectors.get(action);
	}


}
