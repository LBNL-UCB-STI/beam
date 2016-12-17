package beam.playground.metasim.agents.actions;

import com.google.inject.Inject;
import com.google.inject.Provider;

import beam.playground.metasim.services.BeamServices;

public class ActionFactoryImpl implements ActionFactory {
	private final Provider<BeamServices> beamServicesProvider;

	@Inject
	public ActionFactoryImpl(Provider<BeamServices> beamServicesProvider){
		this.beamServicesProvider = beamServicesProvider;
	}

	@Override
	public Action create(String name) {
		return new BaseAction(name,beamServicesProvider.get());
	}

}
