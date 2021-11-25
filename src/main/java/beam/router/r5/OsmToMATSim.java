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
    private final static String TAG_CAPACITY = "capacity";

    private final static double MOTORWAY_LINK_RATIO = 80.0/120;
    private final static double PRIMARY_LINK_RATIO = 60.0/80;
    private final static double TRUNK_LINK_RATIO = 50.0/80;
    private final static double SECONDARY_LINK_RATIO = 0.66;
    private final static double TERTIARY_LINK_RATIO = 0.66;

    public final Map<String, BEAMHighwayDefaults> highwayDefaults = new HashMap<>();
    private final Set<String> unknownMaxspeedTags = new HashSet<>();
    private final Set<String> unknownLanesTags = new HashSet<>();
    private final Set<String> unknownBetasTags = new HashSet<>();
    private final Set<String> unknownAlphasTags = new HashSet<>();
    private final Network mNetwork;

    /**
     * @param mNetwork MATSim network
     * @param useBEAMHighwayDefaults whether to use those defaults or not
     * @param speedsMeterPerSecondMap Map highway type to the speeds, in meteres per seconds
     * @param capacityMap Map highway type to the capacities, in vehicles per hour
     * @param lanesMap Map highway type to the number of lanes in each direction
     *
     * In case if you don't want to provide any of this map, just give an empty map
     */
    public OsmToMATSim(final Network mNetwork, boolean useBEAMHighwayDefaults,
                       Map<HighwayType, Double> speedsMeterPerSecondMap,
                       Map<HighwayType, Integer> capacityMap,
                       Map<HighwayType, Integer> lanesMap,
                       Map<HighwayType, Double> alphaMap,
                       Map<HighwayType, Double> betaMap) {
        this.mNetwork = mNetwork;
        if (useBEAMHighwayDefaults) {
            log.info("Falling back to default values.");
            this.setBEAMHighwayDefaults(1, "motorway", lanesMap.getOrDefault(HighwayType.Motorway, 2),
                    speedsMeterPerSecondMap.getOrDefault(HighwayType.Motorway, toMetersPerSecond(75)), 1.0,
                    capacityMap.getOrDefault(HighwayType.Motorway,2500), alphaMap.getOrDefault(HighwayType.Motorway, 1.0), betaMap.getOrDefault(HighwayType.Motorway, 2.0), true);
            this.setBEAMHighwayDefaults(1, "motorway_link", lanesMap.getOrDefault(HighwayType.MotorwayLink, 1),
                    speedsMeterPerSecondMap.getOrDefault(HighwayType.MotorwayLink, MOTORWAY_LINK_RATIO * toMetersPerSecond(75)), 1.0,
                    capacityMap.getOrDefault(HighwayType.MotorwayLink, 2000), alphaMap.getOrDefault(HighwayType.MotorwayLink, 1.0), betaMap.getOrDefault(HighwayType.MotorwayLink, 2.0), true);
            this.setBEAMHighwayDefaults(3, "primary", lanesMap.getOrDefault(HighwayType.Primary, 1),
                    speedsMeterPerSecondMap.getOrDefault(HighwayType.Primary, toMetersPerSecond(65)), 1.0,
                    capacityMap.getOrDefault(HighwayType.Primary, 2300), alphaMap.getOrDefault(HighwayType.Primary, 1.0), betaMap.getOrDefault(HighwayType.Primary, 2.0));
            this.setBEAMHighwayDefaults(3, "primary_link", lanesMap.getOrDefault(HighwayType.PrimaryLink, 1),
                    speedsMeterPerSecondMap.getOrDefault(HighwayType.PrimaryLink, PRIMARY_LINK_RATIO * toMetersPerSecond(65)), 1.0,
                    capacityMap.getOrDefault(HighwayType.PrimaryLink, 1800), alphaMap.getOrDefault(HighwayType.PrimaryLink, 1.0), betaMap.getOrDefault(HighwayType.PrimaryLink, 2.0));
            this.setBEAMHighwayDefaults(2, "trunk", lanesMap.getOrDefault(HighwayType.Trunk, 1),
                    speedsMeterPerSecondMap.getOrDefault(HighwayType.Trunk, toMetersPerSecond(60)), 1.0,
                    capacityMap.getOrDefault(HighwayType.Trunk, 2200), alphaMap.getOrDefault(HighwayType.Trunk, 1.0), betaMap.getOrDefault(HighwayType.Trunk, 2.0));
            this.setBEAMHighwayDefaults(2, "trunk_link", lanesMap.getOrDefault(HighwayType.TrunkLink, 1),
                    speedsMeterPerSecondMap.getOrDefault(HighwayType.TrunkLink, TRUNK_LINK_RATIO * toMetersPerSecond(60)), 1.0,
                    capacityMap.getOrDefault(HighwayType.TrunkLink, 1500), alphaMap.getOrDefault(HighwayType.TrunkLink, 1.0), betaMap.getOrDefault(HighwayType.TrunkLink, 2.0));

            this.setBEAMHighwayDefaults(4, "secondary", lanesMap.getOrDefault(HighwayType.Secondary, 1),
                    speedsMeterPerSecondMap.getOrDefault(HighwayType.Secondary, toMetersPerSecond(60)), 1.0,
                    capacityMap.getOrDefault(HighwayType.Secondary, 2200), alphaMap.getOrDefault(HighwayType.Secondary, 1.0), betaMap.getOrDefault(HighwayType.Secondary, 2.0));
            this.setBEAMHighwayDefaults(4, "secondary_link", lanesMap.getOrDefault(HighwayType.SecondaryLink, 1),
                    speedsMeterPerSecondMap.getOrDefault(HighwayType.SecondaryLink, SECONDARY_LINK_RATIO * toMetersPerSecond(60)), 1.0,
                    capacityMap.getOrDefault(HighwayType.SecondaryLink, 1500), alphaMap.getOrDefault(HighwayType.SecondaryLink, 1.0), betaMap.getOrDefault(HighwayType.SecondaryLink, 2.0));
            this.setBEAMHighwayDefaults(5, "tertiary", lanesMap.getOrDefault(HighwayType.Tertiary, 1),
                    speedsMeterPerSecondMap.getOrDefault(HighwayType.Tertiary, toMetersPerSecond(55)), 1.0,
                    capacityMap.getOrDefault(HighwayType.Tertiary, 2100), alphaMap.getOrDefault(HighwayType.Tertiary, 1.0), betaMap.getOrDefault(HighwayType.Tertiary, 2.0));
            this.setBEAMHighwayDefaults(5, "tertiary_link", lanesMap.getOrDefault(HighwayType.TertiaryLink, 1),
                    speedsMeterPerSecondMap.getOrDefault(HighwayType.TertiaryLink, TERTIARY_LINK_RATIO * toMetersPerSecond(55)), 1.0,
                    capacityMap.getOrDefault(HighwayType.TertiaryLink, 1500), alphaMap.getOrDefault(HighwayType.TertiaryLink, 1.0), betaMap.getOrDefault(HighwayType.TertiaryLink, 2.0));

            this.setBEAMHighwayDefaults(6, "minor", lanesMap.getOrDefault(HighwayType.Minor, 1),
                    speedsMeterPerSecondMap.getOrDefault(HighwayType.Minor, toMetersPerSecond(25)), 1.0,
                    capacityMap.getOrDefault(HighwayType.Minor, 1000), alphaMap.getOrDefault(HighwayType.Minor, 1.0), betaMap.getOrDefault(HighwayType.Minor, 2.0));
            this.setBEAMHighwayDefaults(6, "residential", lanesMap.getOrDefault(HighwayType.Residential, 1),
                    speedsMeterPerSecondMap.getOrDefault(HighwayType.Residential, toMetersPerSecond(25)), 1.0,
                    capacityMap.getOrDefault(HighwayType.Residential, 1000), alphaMap.getOrDefault(HighwayType.Residential, 1.0), betaMap.getOrDefault(HighwayType.Residential, 2.0));
            this.setBEAMHighwayDefaults(6, "living_street", lanesMap.getOrDefault(HighwayType.LivingStreet, 1),
                    speedsMeterPerSecondMap.getOrDefault(HighwayType.LivingStreet, toMetersPerSecond(25)), 1.0,
                    capacityMap.getOrDefault(HighwayType.LivingStreet, 1000), alphaMap.getOrDefault(HighwayType.LivingStreet, 1.0), betaMap.getOrDefault(HighwayType.LivingStreet, 2.0));

            this.setBEAMHighwayDefaults(6, "unclassified", lanesMap.getOrDefault(HighwayType.Unclassified, 1),
                    speedsMeterPerSecondMap.getOrDefault(HighwayType.Unclassified, toMetersPerSecond(28)), 1.0,
                    capacityMap.getOrDefault(HighwayType.Unclassified, 800), alphaMap.getOrDefault(HighwayType.Unclassified, 1.0), betaMap.getOrDefault(HighwayType.Unclassified, 2.0));
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
    public void setBEAMHighwayDefaults(final int hierarchy, final String highwayType, final double lanesPerDirection, final double freespeed, final double freespeedFactor, final double laneCapacity_vehPerHour, final double alpha, final double beta) {
        setBEAMHighwayDefaults(hierarchy, highwayType, lanesPerDirection, freespeed, freespeedFactor, laneCapacity_vehPerHour,alpha, beta, false);
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
                                       final double freespeedFactor, final double laneCapacity_vehPerHour, final double alpha, final double beta, final boolean oneway) {
        this.highwayDefaults.put(highwayType, new BEAMHighwayDefaults(hierarchy, lanesPerDirection, freespeed, freespeedFactor, laneCapacity_vehPerHour, alpha, beta, oneway));
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
        double capacity;
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
                if(maxspeedTag.endsWith("mph")) {
                    freespeed = toMetersPerSecond(Double.parseDouble(maxspeedTag.replace("mph", "").trim())); // convert mph to m/s
                } else {
                    freespeed = Double.parseDouble(maxspeedTag) / 3.6; // convert km/h to m/s
                }
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
        String capacityTag = way.getTag(TAG_CAPACITY);
        if (capacityTag != null) {
            try {
                capacity = Double.parseDouble(capacityTag);
            } catch (Exception e) {
                capacity = nofLanes * laneCapacity;
            }
        } else {
            capacity = nofLanes * laneCapacity;
        }


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

    public static double toMetersPerSecond(double milesPerHour) {
        return milesPerHour * 1.60934 * 1000 / 3600;
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
        public final double alpha;
        public final double beta;

        public BEAMHighwayDefaults(final int hierarchy, final double lanesPerDirection, final double freespeed,
                                   final double freespeedFactor, final double laneCapacity, final double alpha, final double beta, final boolean oneway) {
            this.hierarchy = hierarchy;
            this.lanesPerDirection = lanesPerDirection;
            this.freespeed = freespeed;
            this.freespeedFactor = freespeedFactor;
            this.laneCapacity = laneCapacity;
            this.oneway = oneway;
            this.alpha = alpha;
            this.beta = beta;
        }
    }
}
