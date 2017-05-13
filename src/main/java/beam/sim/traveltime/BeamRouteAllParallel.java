package beam.sim.traveltime;

import beam.EVGlobalData;
import beam.sim.GlobalActions;
import beam.utils.MathUtil;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.population.routes.LinkNetworkRouteImpl;
import org.matsim.core.router.AStarEuclidean;
import org.matsim.core.router.EmptyStageActivityTypes;
import org.matsim.core.router.StageActivityTypes;
import org.matsim.core.router.util.LeastCostPathCalculator.Path;
import org.matsim.core.router.util.PreProcessEuclidean;
import org.matsim.facilities.Facility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;

public class BeamRouteAllParallel implements Runnable {
    private static final Logger log = Logger.getLogger(BeamRouteAllParallel.class);

    private String fromGroup;
    private LinkedHashMap<String, Link> fromGroups, toGroups;

    public BeamRouteAllParallel(String fromGroup, LinkedHashMap<String,Link> fromGroups, LinkedHashMap<String,Link> toGroups) {
	    this.fromGroup = fromGroup;
	    this.fromGroups = fromGroups;
	    this.toGroups = toGroups;
    }

    @Override
    public void run() {
        log.info("Routing from group "+fromGroup);
        for (Double time = 0.01; time < 24.0; time += 1.0) {
            log.info("Group " + fromGroup + " time " + time);
            for (String toGroup : toGroups.keySet()) {
                TripInformation resultTrip = EVGlobalData.data.router.getTripInformation(time, fromGroups.get(fromGroup), toGroups.get(toGroup));
            }
        }
        log.info("Completed from group "+fromGroup);
    }
}
