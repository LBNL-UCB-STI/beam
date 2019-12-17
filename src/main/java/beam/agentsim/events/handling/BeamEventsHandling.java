package beam.agentsim.events.handling;

import beam.sim.BeamServices;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.MatsimServices;
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
    private final EventsManager eventsManager;
    private final MatsimServices matsimServices;
    private BeamEventsLogger eventsLogger;

    @Inject
    BeamEventsHandling(BeamServices beamServices, MatsimServices matsimServices, EventsManager eventsManager) {
        this.beamServices = beamServices;
        this.matsimServices = matsimServices;
        this.eventsManager = eventsManager;
    }

    @Override
    public void notifyBeforeMobsim(BeforeMobsimEvent event) {
        this.eventsLogger = new BeamEventsLogger(beamServices, matsimServices, eventsManager, beamServices.beamConfig().beam().outputs().events().eventsToWrite(), true);
        eventsManager.resetHandlers(event.getIteration());
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
