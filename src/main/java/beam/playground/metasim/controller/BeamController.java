package beam.playground.metasim.controller;

import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;

import beam.controller.corelisteners.ControllerCoreListenersModule;
import beam.playground.metasim.agents.BeamAgentPopulation;
import beam.playground.metasim.scheduler.Scheduler;
import beam.playground.metasim.services.Actions;
import beam.playground.metasim.services.config.BeamConfigGroup;
import beam.sim.traveltime.BeamRouter;

import org.apache.log4j.Layout;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.matsim.analysis.CalcLinkStats;
import org.matsim.analysis.IterationStopWatch;
import org.matsim.analysis.ScoreStats;
import org.matsim.analysis.VolumesAnalyzer;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.consistency.ConfigConsistencyCheckerImpl;
import org.matsim.core.config.groups.ControlerConfigGroup;
import org.matsim.core.controler.AbstractController;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.ControlerDefaultsModule;
import org.matsim.core.controler.ControlerI;
import org.matsim.core.controler.ControlerListenerManager;
import org.matsim.core.controler.ControlerListenerManagerImpl;
import org.matsim.core.controler.Injector;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.controler.NewControlerModule;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.PrepareForSim;
import org.matsim.core.controler.TerminationCriterion;
import org.matsim.core.controler.corelisteners.DumpDataAtEnd;
import org.matsim.core.controler.corelisteners.EventsHandling;
import org.matsim.core.controler.corelisteners.PlansDumping;
import org.matsim.core.controler.corelisteners.PlansReplanning;
import org.matsim.core.controler.corelisteners.PlansScoring;
import org.matsim.core.controler.listener.ControlerListener;
import org.matsim.core.events.handler.EventHandler;
import org.matsim.core.mobsim.framework.Mobsim;
import org.matsim.core.mobsim.framework.ObservableMobsim;
import org.matsim.core.mobsim.framework.listeners.MobsimListener;
import org.matsim.core.replanning.ReplanningContext;
import org.matsim.core.replanning.StrategyManager;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.LeastCostPathCalculatorFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioByConfigModule;
import org.matsim.core.scenario.ScenarioByInstanceModule;
import org.matsim.core.scoring.ScoringFunctionFactory;

import java.beans.Statement;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

/**
 */
@Singleton
public final class BeamController extends AbstractController implements ControlerI, MatsimServices {
	public static final String DIRECTORY_ITERS = "ITERS";
	public static final String FILENAME_EVENTS_XML = "events.xml.gz";
	public static final String FILENAME_LINKSTATS = "linkstats.txt.gz";
	public static final String FILENAME_TRAVELDISTANCESTATS = "traveldistancestats";
	public static final String FILENAME_POPULATION = "output_plans.xml.gz";
	public static final String FILENAME_NETWORK = "output_network.xml.gz";
	public static final String FILENAME_HOUSEHOLDS = "output_households.xml.gz";
	public static final String FILENAME_LANES = "output_lanes.xml.gz";
	public static final String FILENAME_CONFIG = "output_config.xml.gz";
	public static final String FILENAME_PERSON_ATTRIBUTES = "output_personAttributes.xml.gz" ; 
	public static final String FILENAME_COUNTS = "output_counts.xml.gz" ;
	public static final Layout DEFAULTLOG4JLAYOUT = new PatternLayout("%d{ISO8601} %5p %C{1}:%L %m%n");

	private static final Logger log = Logger.getLogger(BeamController.class);
	private final Config config;
	private final PrepareForSim prepareForSim;
	private final EventsHandling eventsHandling;
	private final PlansDumping plansDumping;
	private final PlansReplanning plansReplanning;
	private final Provider<Mobsim> mobsimProvider;
	private final PlansScoring plansScoring;
	private final TerminationCriterion terminationCriterion;
	private final DumpDataAtEnd dumpDataAtEnd;
	private final Set<ControlerListener> controlerListenersDeclaredByModules;
	private final Collection<Provider<MobsimListener>> mobsimListeners;
	private final ControlerConfigGroup controlerConfigGroup;
	private final OutputDirectoryHierarchy outputDirectoryHierarchy;
	private Scenario scenario;
	private BeamConfigGroup beamConfig;
	private com.google.inject.Injector injector;

	@SuppressWarnings("deprecation")
	@Inject
	public BeamController(Config config, ControlerListenerManager controlerListenerManager, MatsimServices matsimServices, IterationStopWatch stopWatch, PrepareForSim prepareForSim, EventsHandling eventsHandling, PlansDumping plansDumping, PlansReplanning plansReplanning, Provider<Mobsim> mobsimProvider, PlansScoring plansScoring, TerminationCriterion terminationCriterion, DumpDataAtEnd dumpDataAtEnd, Set<ControlerListener> controlerListenersDeclaredByModules, Collection<Provider<MobsimListener>> mobsimListeners, ControlerConfigGroup controlerConfigGroup, OutputDirectoryHierarchy outputDirectoryHierarchy, com.google.inject.Injector injector) {
		super(controlerListenerManager,stopWatch,matsimServices,outputDirectoryHierarchy);
		this.config = config;
		this.config.addConfigConsistencyChecker(new ConfigConsistencyCheckerImpl());
		this.beamConfig = (BeamConfigGroup) this.config.getModule("beam");
		this.prepareForSim = prepareForSim;
		this.eventsHandling = eventsHandling;
		this.plansDumping = plansDumping;
		this.plansReplanning = plansReplanning;
		this.mobsimProvider = mobsimProvider;
		this.plansScoring = plansScoring;
		this.terminationCriterion = terminationCriterion;
		this.dumpDataAtEnd = dumpDataAtEnd;
		this.controlerListenersDeclaredByModules = controlerListenersDeclaredByModules;
		this.mobsimListeners = mobsimListeners;
		this.controlerConfigGroup = controlerConfigGroup;
		this.outputDirectoryHierarchy = outputDirectoryHierarchy;
		this.injector = injector;
	}

	@Override
	public IterationStopWatch getStopwatch() {
		return injector.getInstance(IterationStopWatch.class);
	}

	// DefaultControlerModule includes submodules. If you want less than what the Controler does
    // by default, you can leave ControlerDefaultsModule out, look at what it does,
    // and only include what you want.
    private List<AbstractModule> modules = new ArrayList<>(Arrays.<AbstractModule>asList(new ControlerDefaultsModule()));

	// The module which is currently defined by the sum of the setXX methods called on this Controler.
    private AbstractModule overrides = AbstractModule.emptyModule();

	/**
	 * Initializes a new instance of Controler with the given arguments.
	 *
	 * @param args
	 *            The arguments to initialize the services with.
	 *            <code>args[0]</code> is expected to contain the path to a
	 *            configuration file, <code>args[1]</code>, if set, is expected
	 *            to contain the path to a local copy of the DTD file used in
	 *            the configuration file.
	 */
//	public BeamController(final String[] args) {
//		this(args.length > 0 ? args[0] : null, null, null);
//	}
//
//	public BeamController(final String configFileName) {
//		this(configFileName, null, null);
//	}
//
//	public BeamController(final Config config) {
//		this(null, config, null);
//	}
//
//	public BeamController(final Scenario scenario) {
//		this(null, null, scenario);
//	}

//	private BeamController(final String configFileName, final Config config, Scenario scenario) {
//		if (scenario != null) {
//			// scenario already loaded (recommended):
//			this.config = scenario.getConfig();
//			this.config.addConfigConsistencyChecker(new ConfigConsistencyCheckerImpl());
//		} else {
//			if (configFileName == null) {
//				// config should already be loaded:
//				if (config == null) {
//					throw new IllegalArgumentException("Either the config or the filename of a configfile must be set to initialize the Controler.");
//				}
//				this.config = config;
//			} else {
//				// else load config:
//				this.config = ConfigUtils.loadConfig(configFileName, new BeamConfigGroup());
//			}
//			this.beamConfig = (BeamConfigGroup) this.config.getModule("beam");
//			this.config.addConfigConsistencyChecker(new ConfigConsistencyCheckerImpl());
//
//			// load scenario:
//			//scenario  = ScenarioUtils.createScenario(this.config);
//			//ScenarioUtils.loadScenario(scenario) ;
//		}
//		this.config.parallelEventHandling().makeLocked(); 
//		this.scenario = scenario;
//		this.overrides = scenario == null ? new ScenarioByConfigModule() : new ScenarioByInstanceModule(this.scenario);
//	}

	// ******** --------- *******
	// The following is the internal interface of the Controler, which
	// is meant to be called while the Controler is running (not before)..
	// ******** --------- *******

	@Override
	public final TravelTime getLinkTravelTimes() {
		return this.injector.getInstance(com.google.inject.Injector.class).getInstance(Key.get(new TypeLiteral<Map<String, TravelTime>>() {}))
				.get(TransportMode.car);
	}

    /**
     * Gives access to a {@link org.matsim.core.router.TripRouter} instance.
     * This is a routing service which you can use
     * to calculate routes, e.g. from your own replanning code or your own within-day replanning
     * agent code.
     * You get a Provider (and not an instance directly) because your code may want to later
     * create more than one instance. A TripRouter is not guaranteed to be thread-safe, so
     * you must get() an instance for each thread if you plan to write multi-threaded code.
     *
     * See {@link org.matsim.core.router.TripRouter} for more information and pointers to examples.
     */
    @Override
	public final Provider<TripRouter> getTripRouterProvider() {
		return this.injector.getProvider(TripRouter.class);
	}
	
	@Override
	public final TravelDisutility createTravelDisutilityCalculator() {
        return getTravelDisutilityFactory().createTravelDisutility(this.injector.getInstance(TravelTime.class));
	}

	@Override
	public final LeastCostPathCalculatorFactory getLeastCostPathCalculatorFactory() {
		return this.injector.getInstance(LeastCostPathCalculatorFactory.class);
	}

	@Override
	public final ScoringFunctionFactory getScoringFunctionFactory() {
		return this.injector.getInstance(ScoringFunctionFactory.class);
	}

    @Override
	public final Config getConfig() {
		return config;
	}

    @Override
	public final Scenario getScenario() {
		if (this.injector != null) {
			return this.injector.getInstance(Scenario.class);
		} else {
			if ( scenario == null ) {
				log.error( "Trying to get Scenario before it was instanciated.");
				log.error( "When passing a config file or a config file path to the Controler constructor," );
				log.error( "Scenario will be loaded first when the run() method is invoked." );
				throw new IllegalStateException( "Trying to get Scenario before is was instanciated." );
			}
			return this.scenario;
		}
    }

	
	@Override
	public final EventsManager getEvents() {
		if (this.injector != null) {
			return this.injector.getInstance(EventsManager.class);
		} else {
			return new EventsManager() {
				@Override
				public void processEvent(Event event) {
					BeamController.this.injector.getInstance(EventsManager.class).processEvent(event);
				}

				@Override
				public void addHandler(final EventHandler handler) {
					addOverridingModule(new AbstractModule() {
						@Override
						public void install() {
							addEventHandlerBinding().toInstance(handler);
						}
					});
				}

				@Override
				public void removeHandler(EventHandler handler) {
					throw new UnsupportedOperationException();
				}

				@Override
				public void resetHandlers(int iteration) {
					throw new UnsupportedOperationException();
				}

				@Override
				public void initProcessing() {
					throw new UnsupportedOperationException();
				}

				@Override
				public void afterSimStep(double time) {
					throw new UnsupportedOperationException();
				}

				@Override
				public void finishProcessing() {
					throw new UnsupportedOperationException();
				}
			};
		}
	}

	@Override
	public final com.google.inject.Injector getInjector() {
		return this.injector;
	}

	/**
	 * @deprecated Do not use this, as it may not contain values in every
	 *             iteration
	 */
	@Override
	@Deprecated
	public final CalcLinkStats getLinkStats() {
		return this.injector.getInstance(CalcLinkStats.class);
	}

	@Override
	public final VolumesAnalyzer getVolumes() {
		return this.injector.getInstance(VolumesAnalyzer.class);
	}

	@Override
	public final ScoreStats getScoreStats() {
		return this.injector.getInstance(ScoreStats.class);
	}

    @Override
	public final TravelDisutilityFactory getTravelDisutilityFactory() {
		return this.injector.getInstance(com.google.inject.Injector.class).getInstance(Key.get(new TypeLiteral<Map<String, TravelDisutilityFactory>>(){}))
				.get(TransportMode.car);
	}

	/**
	 * @return Returns the {@link org.matsim.core.replanning.StrategyManager}
	 *         used for the replanning of plans.
	 * @deprecated -- try to use services.addPlanStrategyFactory or services.addPlanSelectoryFactory.
	 * There are cases when this does not work, which is in particular necessary if you need to re-configure the StrategyManager
	 * during the iterations, <i>and</i> you cannot do this before the iterations start.  In such cases, using this
	 * method may be ok. kai/mzilske, aug'14
	 */
	@Override
	@Deprecated // see javadoc above
	public final StrategyManager getStrategyManager() {
		return this.injector.getInstance(StrategyManager.class);
	}

	// ******** --------- *******
	// The following methods are the outer interface of the Controler. They are used
	// to set up infrastructure from the outside, before calling run().
	// ******** --------- *******


	public final void setScoringFunctionFactory(
			final ScoringFunctionFactory scoringFunctionFactory) {
        this.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
				bind(ScoringFunctionFactory.class).toInstance(scoringFunctionFactory);
			}
        });
	}

	public final void setTerminationCriterion(final TerminationCriterion terminationCriterion) {
		this.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				bind(TerminationCriterion.class).toInstance(terminationCriterion);
			}
		});
	}

	/**
     * Allows you to set a factory for {@link org.matsim.core.router.TripRouter} instances.
     * Do this if your use-case requires custom routing logic, for instance if you
     * implement your own complex travel mode.
     * See {@link org.matsim.core.router.TripRouter} for more information and pointers to examples.
     */
	public final void setTripRouterFactory(final javax.inject.Provider<TripRouter> factory) {
        this.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
				bind(TripRouter.class).toProvider(factory);
			}
        });
	}

    public final void addOverridingModule(AbstractModule abstractModule) {
        if (this.injector != null) {
            throw new RuntimeException("Too late for configuring the Controler. This can only be done before calling run.");
        }
        this.overrides = AbstractModule.override(Arrays.asList(this.overrides), abstractModule);
    }

    public final void setModules(AbstractModule... modules) {
        if (this.injector != null) {
            throw new RuntimeException("Too late for configuring the Controler. This can only be done before calling run.");
        }
        this.modules = Arrays.asList(modules);
    }

	@Override
	public void run() {
		super.run(this.config);
	}

	@Override
	protected void loadCoreListeners() {
		if (controlerConfigGroup.getDumpDataAtEnd()) {
			this.addCoreControlerListener(this.dumpDataAtEnd);
		}

		this.addCoreControlerListener(this.plansScoring);
		this.addCoreControlerListener(this.plansReplanning);
		this.addCoreControlerListener(this.plansDumping);
		this.addCoreControlerListener(this.eventsHandling);
		// must be last being added (=first being executed)

		for (ControlerListener controlerListener : this.controlerListenersDeclaredByModules) {
			this.addControlerListener(controlerListener);
		}
	}

	@Override
	protected void runMobSim() {
		Mobsim simulation = this.mobsimProvider.get();
		if (simulation instanceof ObservableMobsim) {
			for (Provider<MobsimListener> l : this.mobsimListeners) {
				((ObservableMobsim) simulation).addQueueSimulationListeners(l.get());
			}
		}
		simulation.run();
	}

	@Override
	protected void prepareForSim() {
		this.prepareForSim.run();
	}

	@Override
	protected boolean continueIterations(int iteration) {
		return terminationCriterion.continueIterations(iteration);
	}

}
