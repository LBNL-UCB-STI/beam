package beam.playground.metasim;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.ControlerDefaultsModule;
import org.matsim.core.controler.ControlerI;
import org.matsim.core.controler.Injector;
import org.matsim.core.controler.NewControlerModule;
import org.matsim.core.scenario.ScenarioByConfigModule;

import com.google.inject.assistedinject.FactoryModuleBuilder;

import beam.playground.metasim.agents.BeamAgentPopulation;
import beam.playground.metasim.controller.BeamController;
import beam.playground.metasim.controller.corelisteners.ControllerCoreListenersModule;
import beam.playground.metasim.injection.modules.BeamModule;
import beam.playground.metasim.services.config.BeamConfigGroup;
import beam.playground.metasim.services.config.BeamEventLoggerConfigGroup;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class BeamMainSimulation {

	public static void main(String[] args) {
		String INPUT_DIRECTORY_BASE_PATH = args[0];
		String CONFIG_RELATIVE_PATH = args[1];
		String OUTPUT_DIRECTORY_BASE_PATH = args[2];

	    List<AbstractModule> modules = new ArrayList<>(Arrays.<AbstractModule>asList(new ControlerDefaultsModule()));
	    
	    Config config = ConfigUtils.loadConfig(INPUT_DIRECTORY_BASE_PATH + File.separator + CONFIG_RELATIVE_PATH,new BeamConfigGroup(), new BeamEventLoggerConfigGroup());
	    ((BeamConfigGroup)config.getModules().get("beam")).customizeConfiguration(config,INPUT_DIRECTORY_BASE_PATH,CONFIG_RELATIVE_PATH,OUTPUT_DIRECTORY_BASE_PATH);

	    com.google.inject.Injector injector = Injector.createInjector(config,AbstractModule.override(Collections.singleton(new AbstractModule() {
			@Override
			public void install() {
				install(new NewControlerModule());
				install(new ControllerCoreListenersModule());
				for (AbstractModule module : modules) {
					install(module);
				}
			}
		}), AbstractModule.override(Arrays.asList(new ScenarioByConfigModule()),new BeamModule())));
		BeamController controller = injector.getInstance(BeamController.class);
		controller.getBeamServices().finalizeInitialization();
		controller.run();
		int i = 0;
	}

}
