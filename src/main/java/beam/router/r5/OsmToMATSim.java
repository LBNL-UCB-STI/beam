package beam.router.r5;

import com.conveyal.osmlib.Way;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Created by Andrew A. Campbell on 7/25/17.
 * This class is based off of MATSim's OsmNetworkReader. Particularly, it is used to generate all the link
 * attributes in the MATSim network based on the OSM way's tags the same way OsmNetworkReader does.
 */

public class OsmToMATSim {

    private final static Logger log = LoggerFactory.getLogger(OsmToMATSim.class);

    private final static String TAG_LANES = "lanes";
    private final static String TAG_HIGHWAY = "highway";
    private final static String TAG_MAXSPEED = "maxspeed";
    private final static String TAG_JUNCTION = "junction";
    private final static String TAG_ONEWAY = "oneway";
    private final static String TAG_ACCESS = "access";
    private final static String[] ALL_TAGS = new String[]{TAG_LANES, TAG_HIGHWAY, TAG_MAXSPEED, TAG_JUNCTION, TAG_ONEWAY, TAG_ACCESS};
    public final Map<String, BEAMHighwayDefaults> highwayDefaults = new HashMap<>();
    private final Set<String> unknownHighways = new HashSet<>(); // Used for logging in OsmNetworkReader
    private final Set<String> unknownMaxspeedTags = new HashSet<>();
    private final Set<String> unknownLanesTags = new HashSet<>();
    private final Network mNetwork;
    private long id = 0;


    public OsmToMATSim(final Network mNetwork, boolean useBEAMHighwayDefaults) {
        this.mNetwork = mNetwork;
        if (useBEAMHighwayDefaults) {

            log.info("Falling back to default values.");
            this.setBEAMHighwayDefaults(1, "motorway", 2, 120.0 / 3.6, 1.0, 2000, true);
            this.setBEAMHighwayDefaults(1, "motorway_link", 1, 80.0 / 3.6, 1.0, 1500, true);
            this.setBEAMHighwayDefaults(2, "trunk", 1, 80.0 / 3.6, 1.0, 2000);
            this.setBEAMHighwayDefaults(2, "trunk_link", 1, 50.0 / 3.6, 1.0, 1500);
            this.setBEAMHighwayDefaults(3, "primary", 1, 80.0 / 3.6, 1.0, 1500);
            this.setBEAMHighwayDefaults(3, "primary_link", 1, 60.0 / 3.6, 1.0, 1500);

//			this.setBEAMHighwayDefaults(4, "secondary",     1,  60.0/3.6, 1.0, 1000);
//			this.setBEAMHighwayDefaults(5, "tertiary",      1,  45.0/3.6, 1.0,  600);
//			this.setBEAMHighwayDefaults(6, "minor",         1,  45.0/3.6, 1.0,  600);
//			this.setBEAMHighwayDefaults(6, "unclassified",  1,  45.0/3.6, 1.0,  600);
//			this.setBEAMHighwayDefaults(6, "residential",   1,  30.0/3.6, 1.0,  600);
//			this.setBEAMHighwayDefaults(6, "living_street", 1,  15.0/3.6, 1.0,  300);

            // Setting the following to considerably smaller values, since there are often traffic signals/non-prio intersections.
            // If someone does a systematic study, please report.  kai, jul'16
            this.setBEAMHighwayDefaults(4, "secondary", 1, 30.0 / 3.6, 1.0, 1000);
            this.setBEAMHighwayDefaults(4, "secondary_link", 1, 30.0 / 3.6, 1.0, 1000);
            this.setBEAMHighwayDefaults(5, "tertiary", 1, 25.0 / 3.6, 1.0, 600);
            this.setBEAMHighwayDefaults(5, "tertiary_link", 1, 25.0 / 3.6, 1.0, 600);
            this.setBEAMHighwayDefaults(6, "minor", 1, 20.0 / 3.6, 1.0, 600);
            this.setBEAMHighwayDefaults(6, "residential", 1, 15.0 / 3.6, 1.0, 600);
            this.setBEAMHighwayDefaults(6, "living_street", 1, 10.0 / 3.6, 1.0, 300);
            // changing the speed values failed the evacuation ScenarioGenerator test because of a different network -- DESPITE
            // the fact that all the speed values are reset to some other value there.  No idea what happens there. kai, jul'16

            this.setBEAMHighwayDefaults(6, "unclassified", 1, 45.0 / 3.6, 1.0, 600);
        }
    }

    /**
     * Replaces OsmNetworkReader.setHighwayDefaults
     * Sets defaults for converting OSM highway paths into MATSim links, assuming it is no oneway road.
     *
     * @param hierarchy               The hierarchy layer the highway appears.
     * @param highwayType             The type of highway these defaults are for.
     * @param lanesPerDirection       number of lanes on that road type <em>in each direction</em>
     * @param freespeed               the free speed vehicles can drive on that road type [meters/second]
     * @param freespeedFactor         the factor the freespeed is scaled
     * @param laneCapacity_vehPerHour the capacity per lane [veh/h]
     * @see <a href="http://wiki.openstreetmap.org/wiki/Map_Features#Highway">http://wiki.openstreetmap.org/wiki/Map_Features#Highway</a>
     */
    public void setBEAMHighwayDefaults(final int hierarchy, final String highwayType, final double lanesPerDirection, final double freespeed, final double freespeedFactor, final double laneCapacity_vehPerHour) {
        setBEAMHighwayDefaults(hierarchy, highwayType, lanesPerDirection, freespeed, freespeedFactor, laneCapacity_vehPerHour, false);
    }

    /**
     * Replaces OsmNetworkReader.setHighwayDefaults
     * Sets defaults for converting OSM highway paths into MATSim links.
     *
     * @param hierarchy               The hierarchy layer the highway appears in.
     * @param highwayType             The type of highway these defaults are for.
     * @param lanesPerDirection       number of lanes on that road type <em>in each direction</em>
     * @param freespeed               the free speed vehicles can drive on that road type [meters/second]
     * @param freespeedFactor         the factor the freespeed is scaled
     * @param laneCapacity_vehPerHour the capacity per lane [veh/h]
     * @param oneway                  <code>true</code> to say that this road is a oneway road
     */
    public void setBEAMHighwayDefaults(final int hierarchy, final String highwayType, final double lanesPerDirection, final double freespeed,
                                       final double freespeedFactor, final double laneCapacity_vehPerHour, final boolean oneway) {
        this.highwayDefaults.put(highwayType, new BEAMHighwayDefaults(hierarchy, lanesPerDirection, freespeed, freespeedFactor, laneCapacity_vehPerHour, oneway));
    }

    public Link createLink(final Way way, long osmID, Integer r5ID, final Node fromMNode, final Node toMNode,
                           final double length, HashSet<String> flagStrings) {
        String highway = way.getTag(TAG_HIGHWAY);
        if (highway == null) {
            highway = "unclassified";
        }
        BEAMHighwayDefaults defaults = this.highwayDefaults.get(highway);

        if (defaults == null) {
            defaults = this.highwayDefaults.get("unclassified");
        }

        double nofLanes = defaults.lanesPerDirection;
        double laneCapacity = defaults.laneCapacity;
        double freespeed = defaults.freespeed;
        double freespeedFactor = defaults.freespeedFactor;
        boolean oneway = defaults.oneway;
        boolean onewayReverse = false;

        // check if there are tags that overwrite defaults
        // - check tag "junction"
        if ("roundabout".equals(way.getTag(TAG_JUNCTION))) {
            // if "junction" is not set in tags, get() returns null and equals() evaluates to false
            oneway = true;
        }

        // check tag "oneway"
        String onewayTag = way.getTag(TAG_ONEWAY);
        if (onewayTag != null) {
            if ("yes".equals(onewayTag)) {
                oneway = true;
            } else if ("true".equals(onewayTag)) {
                oneway = true;
            } else if ("1".equals(onewayTag)) {
                oneway = true;
            } else if ("-1".equals(onewayTag)) {
                onewayReverse = true;
                oneway = false;
            } else if ("no".equals(onewayTag)) {
                oneway = false; // may be used to overwrite defaults
            } else {
                log.warn("Could not interpret oneway tag:" + onewayTag + ". Ignoring it.");
            }
        }

        // In case trunks, primary and secondary roads are marked as oneway,
        // the default number of lanes should be two instead of one.
        if (highway.equalsIgnoreCase("trunk") || highway.equalsIgnoreCase("primary") || highway.equalsIgnoreCase("secondary")) {
            if ((oneway || onewayReverse) && nofLanes == 1.0) {
                nofLanes = 2.0;
            }
        }

        String maxspeedTag = way.getTag(TAG_MAXSPEED);
        if (maxspeedTag != null) {
            try {
                freespeed = Double.parseDouble(maxspeedTag) / 3.6; // convert km/h to m/s
            } catch (NumberFormatException e) {
                if (!this.unknownMaxspeedTags.contains(maxspeedTag)) {
                    this.unknownMaxspeedTags.add(maxspeedTag);
                    log.warn("Could not parse maxspeed tag:" + e.getMessage() + ". Ignoring it.");
                }
            }
        }

        // check tag "lanes"
        String lanesTag = way.getTag(TAG_LANES);
        if (lanesTag != null) {
            try {
                double totalNofLanes = Double.parseDouble(lanesTag);
                if (totalNofLanes > 0) {
                    nofLanes = totalNofLanes;

                    //By default, the OSM lanes tag specifies the total number of lanes in both directions.
                    //So if the road is not oneway (onewayReverse), let's distribute them between both directions
                    //michalm, jan'16
                    if (!oneway && !onewayReverse) {
                        nofLanes /= 2.;
                    }
                }
            } catch (Exception e) {
                if (!this.unknownLanesTags.contains(lanesTag)) {
                    this.unknownLanesTags.add(lanesTag);
                    log.warn("Could not parse lanes tag:" + e.getMessage() + ". Ignoring it.");
                }
            }
        }

        // create the link(s)
        double capacity = nofLanes * laneCapacity;

        boolean scaleMaxSpeed = false;
        if (scaleMaxSpeed) {
            freespeed = freespeed * freespeedFactor;
        }

        // only create link, if both nodes were found, node could be null, since nodes outside a layer were dropped
        Id<Node> fromId = fromMNode.getId();
        Id<Node> toId = toMNode.getId();
        if (this.mNetwork.getNodes().get(fromId) != null && this.mNetwork.getNodes().get(toId) != null) {
            Link l = this.mNetwork.getFactory().createLink(Id.create(r5ID, Link.class), this.mNetwork.getNodes().get(fromId), this.mNetwork.getNodes().get(toId));
            l.setLength(length);
            l.setFreespeed(freespeed);
            l.setCapacity(capacity);
            l.setNumberOfLanes(nofLanes);
            l.setAllowedModes(flagStrings);
            NetworkUtils.setOrigId(l, Long.toString(osmID));
            NetworkUtils.setType(l, highway);
            return l;
        } else {
            throw new RuntimeException();
        }
    }

    /**
     * Takes the place of the private class OsmNetworkReader.OsmHighwayDefaults
     */
    public static class BEAMHighwayDefaults {
        public final int hierarchy;
        public final double lanesPerDirection;
        public final double freespeed;
        public final double freespeedFactor;
        public final double laneCapacity;
        public final boolean oneway;

        public BEAMHighwayDefaults(final int hierarchy, final double lanesPerDirection, final double freespeed,
                                   final double freespeedFactor, final double laneCapacity, final boolean oneway) {
            this.hierarchy = hierarchy;
            this.lanesPerDirection = lanesPerDirection;
            this.freespeed = freespeed;
            this.freespeedFactor = freespeedFactor;
            this.laneCapacity = laneCapacity;
            this.oneway = oneway;
        }
    }
}
