package beam.playground.metasim.agents.actions;

import org.matsim.core.controler.MatsimServices;

import com.google.inject.Inject;
import com.google.inject.Provider;

import beam.playground.metasim.services.BeamServices;

public interface ActionFactory {
	Action create(String name);
	
	public class Default implements ActionFactory {
		private final Provider<BeamServices> beamServicesProvider;
		private final Provider<MatsimServices> matsimServiceProvider;

		@Inject
		public Default(Provider<BeamServices> beamServicesProvider, Provider<MatsimServices> matsimServiceProvider){
			this.beamServicesProvider = beamServicesProvider;
			this.matsimServiceProvider = matsimServiceProvider;
		}

		@Override
		public Action create(String name) {
			return new BaseAction(name,beamServicesProvider.get(), matsimServiceProvider.get());
		}

	}

}
