package beam.playground.metasim.controller.corelisteners;

import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.corelisteners.DumpDataAtEnd;
import org.matsim.core.controler.corelisteners.EventsHandling;
import org.matsim.core.controler.corelisteners.PlansDumping;
import org.matsim.core.controler.corelisteners.PlansReplanning;
import org.matsim.core.scoring.PlansScoringModule;

public class ControllerCoreListenersModule extends AbstractModule {

	@Override
	public void install() {
		install(new PlansScoringModule());
		bind( PlansReplanning.class ).to( PlansReplanningImpl.class );
		bind( PlansDumping.class ).to( PlansDumpingImpl.class );
		bind( EventsHandling.class ).to( EventsHandlingImpl.class );
		bind( DumpDataAtEnd.class ).to( DumpDataAtEndImpl.class );
	}
}

