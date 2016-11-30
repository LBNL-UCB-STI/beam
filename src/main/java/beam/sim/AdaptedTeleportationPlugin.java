package beam.sim;

import java.util.ArrayList;
import java.util.Collection;

import org.matsim.core.config.Config;
import org.matsim.core.mobsim.qsim.AbstractQSimPlugin;
import org.matsim.core.mobsim.qsim.TeleportationEngine;
import org.matsim.core.mobsim.qsim.interfaces.MobsimEngine;

import com.google.inject.AbstractModule;
import com.google.inject.Module;

public class AdaptedTeleportationPlugin extends AbstractQSimPlugin {
	
	public AdaptedTeleportationEngine teleportationEngine;	
	
	public AdaptedTeleportationPlugin(Config config) {
		super(config);
	}

	@Override
	public Collection<? extends Module> modules() {
		Collection<Module> result = new ArrayList<>();
		result.add(new AbstractModule() {
			@Override
			protected void configure() {
				bind(AdaptedTeleportationEngine.class).asEagerSingleton();
			}
		});
		return result;
	}

	@Override
	public Collection<Class<? extends MobsimEngine>> engines() {
		Collection<Class<? extends MobsimEngine>> result = new ArrayList<>();
		result.add(AdaptedTeleportationEngine.class);
		return result;
	}
}