package beam.playground.injection.modules;

import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.ControlerListenerManager;
import org.matsim.core.controler.ControlerListenerManagerImpl;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.controler.corelisteners.EventsHandling;
import org.matsim.core.mobsim.framework.Mobsim;

import com.google.inject.Singleton;

import beam.playground.metasim.MetaSim;
import beam.playground.scheduler.Scheduler;
import beam.playground.services.BeamServices;
import beam.playground.services.BeamServicesImpl;
import beam.playground.agents.BeamAgentPopulation;
import beam.playground.controller.BeamController;
import beam.playground.controller.BeamControllerListenerManager;
import beam.playground.controller.corelisteners.EventsHandlingImpl;
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
		bind(EventsHandling.class).to(EventsHandlingImpl.class);
		addControlerListenerBinding().toInstance(new BeamAgentPopulation());
		bind(ControlerListenerManager.class).to(BeamControllerListenerManager.class);
	}

}
