package beam.sim;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.mobsim.framework.Mobsim;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.QSimModule;
import org.matsim.core.scenario.ScenarioByInstanceModule;

import com.google.inject.Injector;

public class AdaptedQSimUtils {

	public static QSim createDefaultQSim(final Scenario scenario, final EventsManager eventsManager) {
		Injector injector = org.matsim.core.controler.Injector.createInjector(scenario.getConfig(),
				new StandaloneQSimModule(scenario, eventsManager));
		return (QSim) injector.getInstance(Mobsim.class);
	}

	private static class StandaloneQSimModule extends org.matsim.core.controler.AbstractModule {
		private final Scenario scenario;
		private final EventsManager eventsManager;

		public StandaloneQSimModule(Scenario scenario, EventsManager eventsManager) {
			this.scenario = scenario;
			this.eventsManager = eventsManager;
		}

		@Override
		public void install() {
			install(new ScenarioByInstanceModule(scenario));
			bind(EventsManager.class).toInstance(eventsManager);
			install(new AdaptedQSimModule());
		}
	}

}
