package beam.playground.metasim.injection.modules;

import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.controler.corelisteners.DumpDataAtEnd;
import org.matsim.core.controler.corelisteners.EventsHandling;
import org.matsim.core.mobsim.framework.Mobsim;

import com.google.inject.assistedinject.FactoryModuleBuilder;

import beam.playground.metasim.agents.BeamAgentPopulation;
import beam.playground.metasim.agents.FiniteStateMachineGraphFactory;
import beam.playground.metasim.agents.PersonAgentFactory;
import beam.playground.metasim.agents.actions.Action;
import beam.playground.metasim.agents.actions.ActionFactory;
import beam.playground.metasim.agents.behavior.Behavior;
import beam.playground.metasim.agents.behavior.TravelBehaviorProvider;
import beam.playground.metasim.agents.transition.TransitionFactory;
import beam.playground.metasim.agents.transition.selectors.RandomTransitionSelector;
import beam.playground.metasim.agents.transition.selectors.TransitionSelector;
import beam.playground.metasim.controller.BeamController;
import beam.playground.metasim.controller.corelisteners.BeamEventsHandlingImpl;
import beam.playground.metasim.controller.corelisteners.DumpDataAtEndImpl;
import beam.playground.metasim.metasim.MetaSim;
import beam.playground.metasim.scheduler.ActionCallBack;
import beam.playground.metasim.scheduler.ActionCallBackFactory;
import beam.playground.metasim.scheduler.Scheduler;
import beam.playground.metasim.services.DefaultTranitionSelectors;
import beam.playground.metasim.services.BeamRandom;
import beam.playground.metasim.services.BeamServices;
import beam.sim.traveltime.BeamRouter;
import beam.sim.traveltime.BeamRouterImpl;

public class BeamModule extends AbstractModule {

	@Override
	public void install() {
		 
		// CONTROLLER / MOBSIM / SETTINGS
		bind(BeamRouter.class).to(BeamRouterImpl.class);
		bind(BeamController.class).asEagerSingleton();
		bind(BeamServices.class).to(BeamServices.Default.class).asEagerSingleton();
		bind(Mobsim.class).to(MetaSim.class);
		bind(MatsimServices.class).to(BeamController.class).asEagerSingleton();
		bind(EventsHandling.class).to(BeamEventsHandlingImpl.class);
		addControlerListenerBinding().toInstance(new BeamAgentPopulation());
		bind(DumpDataAtEnd.class).to(DumpDataAtEndImpl.class).asEagerSingleton();
		bind(BeamRandom.class).to(BeamRandom.Default.class);
		
		// SCHEDULER
		install(new FactoryModuleBuilder().implement(ActionCallBack.class, ActionCallBack.Default.class).build(ActionCallBackFactory.class));
		bind(Scheduler.class).asEagerSingleton();
		
		// AGENTS
		bind(DefaultTranitionSelectors.class).asEagerSingleton();
		bind(TransitionSelector.class).to(RandomTransitionSelector.class);
		bind(PersonAgentFactory.class).to(PersonAgentFactory.Default.class);
		bind(Behavior.class).toProvider(TravelBehaviorProvider.class);
		install(new FactoryModuleBuilder().implement(Action.class, Action.Default.class).build(ActionFactory.class));
		bind(TransitionFactory.class).to(TransitionFactory.Default.class);
		bind(FiniteStateMachineGraphFactory.class).to(FiniteStateMachineGraphFactory.Default.class);
		
	}

}
