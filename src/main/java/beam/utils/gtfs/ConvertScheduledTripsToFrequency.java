package beam.utils.gtfs;

import beam.router.r5.NetworkCoordinator;
import beam.sim.BeamHelper;
import beam.utils.BeamConfigUtils;
import com.conveyal.r5.analyst.scenario.AddTrips;
import com.conveyal.r5.analyst.scenario.AdjustFrequency;
import com.conveyal.r5.analyst.scenario.Modification;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.conveyal.r5.transit.TransportNetwork;
import com.typesafe.scalalogging.LazyLogging;
import com.typesafe.scalalogging.Logger;
import org.matsim.core.scenario.MutableScenario;
import scala.Tuple3;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Convert a line to frequency.
 */
public class ConvertScheduledTripsToFrequency extends Modification {
    public static final String type = "convert-to-frequency";
    public String getType() {
        return "convert-to-frequency";
    }

    @Override
    public boolean apply(TransportNetwork network) {
        return toR5().apply(network);
    }

    @Override
    public int getSortOrder() {
        return 49;
    }

    public String feed;
    public String[] routes;

    /** Should trips on this route that start outside the days/times specified by frequency entries be retained? */
    public boolean retainTripsOutsideFrequencyEntries = false;

    public List<FrequencyEntry> entries;

    public static class FrequencyEntry extends AbstractTimetable {
        /** start times of this trip (seconds since midnight), when non-null scheduled trips will be created */
        @Deprecated
        public int[] startTimes;

        /** trip from which to copy travel times */
        public String sourceTrip;

        /** trips on the selected patterns which could be used as source trips */
        public String[] patternTrips;

        public AddTrips.PatternTimetable toR5 (String feed) {
            AddTrips.PatternTimetable pt = toBaseR5Timetable();

            pt.sourceTrip = feed + ":" + sourceTrip;

            return pt;
        }
    }

    private String feedScopeId (String feed, String id) {
        return String.format("%s:%s", feed, id);
    }

    public AdjustFrequency toR5 () {
        AdjustFrequency af = new AdjustFrequency();
        af.comment = type;
        af.route = feedScopeId(feed, routes[0]);
        af.retainTripsOutsideFrequencyEntries = retainTripsOutsideFrequencyEntries;
        af.entries = entries.stream().map(e -> e.toR5(feed)).collect(Collectors.toList());

        return af;
    }

}
