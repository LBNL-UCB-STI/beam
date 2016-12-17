package beam.playground.metasim.injection.modules;

import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.controler.corelisteners.DumpDataAtEnd;
import org.matsim.core.controler.corelisteners.EventsHandling;
import org.matsim.core.mobsim.framework.Mobsim;

import beam.playground.metasim.agents.BeamAgentPopulation;
import beam.playground.metasim.agents.actions.Action;
import beam.playground.metasim.agents.actions.ActionFactory;
import beam.playground.metasim.agents.actions.ActionFactoryImpl;
import beam.playground.metasim.agents.behavior.Behavior;
import beam.playground.metasim.agents.behavior.TravelBehaviorProvider;
import beam.playground.metasim.agents.transition.selectors.RandomTransitionSelector;
import beam.playground.metasim.agents.transition.selectors.TransitionSelector;
import beam.playground.metasim.controller.BeamController;
import beam.playground.metasim.controller.corelisteners.BeamEventsHandlingImpl;
import beam.playground.metasim.controller.corelisteners.DumpDataAtEndImpl;
import beam.playground.metasim.metasim.MetaSim;
import beam.playground.metasim.scheduler.ActionCallBackFactory;
import beam.playground.metasim.scheduler.ActionCallbackFactoryImpl;
import beam.playground.metasim.scheduler.Scheduler;
import beam.playground.metasim.services.Actions;
import beam.playground.metasim.services.BeamServices;
import beam.playground.metasim.services.BeamServicesImpl;
import beam.sim.traveltime.BeamRouter;
import beam.sim.traveltime.BeamRouterImpl;

public class BeamModule extends AbstractModule {

	@Override
	public void install() {
		bind(BeamRouter.class).to(BeamRouterImpl.class);
		bind(Behavior.class).toProvider(TravelBehaviorProvider.class);
		bind(Scheduler.class).asEagerSingleton();
		bind(ActionCallBackFactory.class).to(ActionCallbackFactoryImpl.class);
		bind(BeamController.class).asEagerSingleton();
		bind(BeamServices.class).to(BeamServicesImpl.class).asEagerSingleton();
		bind(Actions.class).asEagerSingleton();
		bind(ActionFactory.class).to(ActionFactoryImpl.class);
		bind(TransitionSelector.class).to(RandomTransitionSelector.class);
		bind(Mobsim.class).to(MetaSim.class);
		bind(MatsimServices.class).to(BeamController.class).asEagerSingleton();
		bind(EventsHandling.class).to(BeamEventsHandlingImpl.class);
		addControlerListenerBinding().toInstance(new BeamAgentPopulation());
		bind(DumpDataAtEnd.class).to(DumpDataAtEndImpl.class).asEagerSingleton();
	}

}
