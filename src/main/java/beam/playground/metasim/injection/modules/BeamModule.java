package beam.playground.metasim.injection.modules;

import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.ControlerListenerManager;
import org.matsim.core.controler.ControlerListenerManagerImpl;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.controler.corelisteners.DumpDataAtEnd;
import org.matsim.core.controler.corelisteners.EventsHandling;
import org.matsim.core.mobsim.framework.Mobsim;

import com.google.inject.Singleton;

import beam.playground.metasim.agents.BeamAgentPopulation;
import beam.playground.metasim.controller.BeamController;
import beam.playground.metasim.controller.BeamControllerListenerManager;
import beam.playground.metasim.controller.corelisteners.BeamEventsHandlingImpl;
import beam.playground.metasim.controller.corelisteners.DumpDataAtEndImpl;
import beam.playground.metasim.metasim.MetaSim;
import beam.playground.metasim.scheduler.Scheduler;
import beam.playground.metasim.services.BeamServices;
import beam.playground.metasim.services.BeamServicesImpl;
import beam.sim.traveltime.BeamRouter;
import beam.sim.traveltime.BeamRouterImpl;

public class BeamModule extends AbstractModule {

	@Override
	public void install() {
		bind(BeamRouter.class).to(BeamRouterImpl.class);
		bind(Scheduler.class).asEagerSingleton();
		bind(BeamController.class).asEagerSingleton();
		bind(Mobsim.class).to(MetaSim.class);
		bind(BeamServices.class).to(BeamController.class).asEagerSingleton();
		bind(MatsimServices.class).to(BeamController.class).asEagerSingleton();
		bind(EventsHandling.class).to(BeamEventsHandlingImpl.class);
		addControlerListenerBinding().toInstance(new BeamAgentPopulation());
		bind(DumpDataAtEnd.class).to(DumpDataAtEndImpl.class).asEagerSingleton();
	}

}
