package beam.sim.controler.corelisteners;

import beam.agentsim.events.handling.BeamEventsHandling;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.corelisteners.DumpDataAtEnd;
import org.matsim.core.controler.corelisteners.EventsHandling;
import org.matsim.core.controler.corelisteners.PlansDumping;
import org.matsim.core.controler.corelisteners.PlansReplanning;
import org.matsim.core.scoring.PlansScoringModule;

public class BeamControllerCoreListenersModule extends AbstractModule {

	@Override
	public void install() {
		install(new beam.agentsim.events.handling.PlansScoringModule());
		bind( PlansReplanning.class ).to( PlansReplanningImpl.class );
		bind( PlansDumping.class ).to( PlansDumpingImpl.class );
		bind( EventsHandling.class ).to( BeamEventsHandling.class );
		bind( DumpDataAtEnd.class ).to( DumpDataAtEndImpl.class );
	}
}

