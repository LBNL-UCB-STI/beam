package beam.agentsim.events.handling;


import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.corelisteners.PlansScoring;

public class PlansScoringModule extends AbstractModule {
	@Override
	public void install() {
		bind(beam.agentsim.events.handling.ScoringFunctionsForPopulation.class).asEagerSingleton();
		bind(PlansScoring.class).to(beam.agentsim.events.handling.PlansScoringImpl.class);
	}
}
