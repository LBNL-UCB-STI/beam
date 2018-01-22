package beam.analysis.physsim;

import org.matsim.analysis.CalcLinkStats;
import org.matsim.analysis.VolumesAnalyzer;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.groups.ControlerConfigGroup;
import org.matsim.core.config.groups.LinkStatsConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.router.util.TravelTime;

import javax.inject.Inject;
import javax.inject.Provider;
import java.util.Map;

public class LinkStats {

    private final Network network;
    @Inject
    private CalcLinkStats linkStats;
    @Inject
    private VolumesAnalyzer volumes;
    @Inject
    private OutputDirectoryHierarchy controlerIO;
    @Inject
    private Map<String, TravelTime> travelTime222;
    private int iterationsUsed = 0;
    private boolean doReset = false;

    public LinkStats(Network network, OutputDirectoryHierarchy controlerIO) {

        this.network=network;
        linkStats = new CalcLinkStats(network);
        this.controlerIO = controlerIO;
    }


    public void notifyIterationEnds(int iteration, TravelTime travelTime) {
        this.iterationsUsed++;
        linkStats.addData(volumes, travelTime);

        linkStats.writeFile(this.controlerIO.getIterationFilename(iteration, Controler.FILENAME_LINKSTATS));
        this.doReset = true;

    }

    public void notifyIterationStarts(EventsManager eventsManager) {
        volumes = new VolumesAnalyzer(3600, 24 * 3600 - 1, network);
        eventsManager.addHandler(volumes);

        if (this.doReset) {
            // resetting at the beginning of an iteration, to allow others to use the data until the very end of the previous iteration
            this.linkStats.reset();
            this.doReset = false;
        }
    }


}