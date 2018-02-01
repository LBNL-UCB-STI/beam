package beam.analysis.physsim;

import beam.utils.BeamCalcLinkStats;
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
    private BeamCalcLinkStats linkStats;
    private VolumesAnalyzer volumes;
    private OutputDirectoryHierarchy controlerIO;

    public LinkStats(Network network, OutputDirectoryHierarchy controlerIO) {
        this.network = network;
        linkStats = new BeamCalcLinkStats(network);
        this.controlerIO = controlerIO;
    }

    public void notifyIterationEnds(int iteration, TravelTime travelTime) {
        linkStats.addData(volumes, travelTime);
        linkStats.writeFile(this.controlerIO.getIterationFilename(iteration, Controler.FILENAME_LINKSTATS));
    }

    public void notifyIterationStarts(EventsManager eventsManager) {
        this.linkStats.reset();
        volumes = new VolumesAnalyzer(3600, 24 * 3600 - 1, network);
        eventsManager.addHandler(volumes);
    }


}