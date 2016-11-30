package beam.sim;

import java.util.ArrayList;
import java.util.Collection;

import org.matsim.core.config.Config;
import org.matsim.core.mobsim.qsim.AbstractQSimPlugin;
import org.matsim.core.mobsim.qsim.ActivityEngine;
import org.matsim.core.mobsim.qsim.interfaces.ActivityHandler;
import org.matsim.core.mobsim.qsim.interfaces.MobsimEngine;

import com.google.inject.AbstractModule;
import com.google.inject.Module;

public class AdaptedActivityEnginePlugin extends AbstractQSimPlugin {

	public AdaptedActivityEnginePlugin(Config config) {
		super(config);
	}

	@Override
	public Collection<? extends Module> modules() {
		Collection<Module> result = new ArrayList<>();
		result.add(new AbstractModule() {
			@Override
			public void configure() {
				bind(AdaptedActivityEngine.class).asEagerSingleton();
			}
		});
		return result;
	}

	@Override
	public Collection<Class<? extends ActivityHandler>> activityHandlers() {
		Collection<Class<? extends ActivityHandler>> result = new ArrayList<>();
		result.add(AdaptedActivityEngine.class);
		return result;
	}

	@Override
	public Collection<Class<? extends MobsimEngine>> engines() {
		Collection<Class<? extends MobsimEngine>> result = new ArrayList<>();
		result.add(AdaptedActivityEngine.class);
		return result;
	}
}