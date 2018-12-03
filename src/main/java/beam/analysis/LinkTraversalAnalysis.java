package beam.analysis;

import beam.agentsim.events.PathTraversalEvent;
import beam.sim.BeamServices;
import beam.sim.OutputDataDescription;
import beam.sim.config.BeamConfig;
import beam.utils.OutputDataDescriptor;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LinkTraversalAnalysis implements IterationEndsListener, OutputDataDescriptor {
    private BeamConfig beamConfig;
    private Network network;
    private OutputDirectoryHierarchy outputDirectoryHierarchy;
    private String outputFileName = "linkTraversalAnalysis";

    public LinkTraversalAnalysis(Network network , OutputDirectoryHierarchy outputDirectoryHierarchy , BeamConfig beamConfig) {
        this.network = network;
        this.outputDirectoryHierarchy = outputDirectoryHierarchy;
        this.beamConfig = beamConfig;
    }

    /**
     * Get description of fields written to the output files.
     *
     * @return list of data description objects
     */
    @Override
    public List<OutputDataDescription> getOutputDataDescriptions() {
        return new ArrayList<>();
    }

    /**
     * Notifies all observers of the Controler that a iteration is finished
     *
     * @param event
     */
    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        this.network.getLinks()
                .values()
                .forEach(link -> {
                    double freeFlowSpeed = link.getFreespeed();
                    Id<Link> linkId = link.getId();
                    String linkType = (String) link.getAttributes().getAttribute("type");
                    String averageSpeed = ""; // how to get beamtrip / leg ?
                    String linkEnterTime = (String) link.getAttributes().getAttribute(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME);
                    String vehicleId = (String) link.getAttributes().getAttribute(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID);
                    String vehicleType = (String) link.getAttributes().getAttribute(PathTraversalEvent.ATTRIBUTE_VEHICLE_TYPE);
                    String turnAtLinkEnd = "";
                    int numberOfStops = 0;
                });
    }

}
