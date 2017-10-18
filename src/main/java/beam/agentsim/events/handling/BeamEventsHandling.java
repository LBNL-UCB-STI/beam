package beam.agentsim.events.handling;

import beam.sim.BeamServices;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.matsim.core.controler.corelisteners.EventsHandling;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.AfterMobsimListener;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.ShutdownListener;

/**
 * BEAM
 */
@Singleton
public final class BeamEventsHandling implements EventsHandling, BeforeMobsimListener, AfterMobsimListener, IterationEndsListener, ShutdownListener {
    private final BeamServices beamServices;
    private BeamEventsLogger eventsLogger;

    @Inject
    BeamEventsHandling(BeamServices beamServices) {
        this.beamServices = beamServices;
    }

    @Override
    public void notifyBeforeMobsim(BeforeMobsimEvent event) {
        this.eventsLogger = new BeamEventsLogger(beamServices, beamServices.matsimServices().getEvents());
        beamServices.matsimServices().getEvents().resetHandlers(event.getIteration());
        // init for event processing of new iteration
        beamServices.matsimServices().getEvents().initProcessing();
    }


    @Override
    public void notifyAfterMobsim(AfterMobsimEvent event) {
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        eventsLogger.iterationEnds();
    }

    @Override
    public void notifyShutdown(ShutdownEvent event) {
    }

}
