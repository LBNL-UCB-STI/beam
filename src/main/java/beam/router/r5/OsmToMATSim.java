package beam.router.r5;

import com.conveyal.osmlib.Way;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;

import java.util.*;

/**
 * Created by Andrew A. Campbell on 7/25/17.
 * This class is based off of MATSim's OsmNetworkReader. Particularly, it is used to generate all the link
 * attributes in the MATSim network based on the OSM way's tags the same way OsmNetworkReader does.
 */

public class OsmToMATSim {
	private final static Logger log = Logger.getLogger(OsmToMATSim.class);

	private final static String TAG_LANES = "lanes";
	private final static String TAG_HIGHWAY = "highway";
	private final static String TAG_MAXSPEED = "maxspeed";
	private final static String TAG_JUNCTION = "junction";
	private final static String TAG_ONEWAY = "oneway";
	private final static String TAG_ACCESS = "access";
	private final static String[] ALL_TAGS = new String[] {TAG_LANES, TAG_HIGHWAY, TAG_MAXSPEED, TAG_JUNCTION, TAG_ONEWAY, TAG_ACCESS};

	private final Set<String> unknownHighways = new HashSet<String>(); // Used for logging in OsmNetworkReader
	private final Set<String> unknownMaxspeedTags = new HashSet<String>();
	private final Set<String> unknownLanesTags = new HashSet<String>();
	private long id = 0;

	private boolean scaleMaxSpeed = false;

	public final Map<String, BEAMHighwayDefaults> highwayDefaults = new HashMap<>();
	private final Network mNetwork;
	private final CoordinateTransformation transform;


//	public OsmToMATSim(final Network mNetwork, final CoordinateTransformation transformation,
//					   true){
//
//	}


	public OsmToMATSim(final Network mNetwork, final CoordinateTransformation transformation,
					   boolean useBEAMHighwayDefaults){
		this.mNetwork = mNetwork;
		this.transform = transformation;
		if (useBEAMHighwayDefaults){

			log.info("Falling back to default values.");
			this.setBEAMHighwayDefaults(1, "motorway",      2, 120.0/3.6, 1.0, 2000, true);
			this.setBEAMHighwayDefaults(1, "motorway_link", 1,  80.0/3.6, 1.0, 1500, true);
			this.setBEAMHighwayDefaults(2, "trunk",         1,  80.0/3.6, 1.0, 2000);
			this.setBEAMHighwayDefaults(2, "trunk_link",    1,  50.0/3.6, 1.0, 1500);
			this.setBEAMHighwayDefaults(3, "primary",       1,  80.0/3.6, 1.0, 1500);
			this.setBEAMHighwayDefaults(3, "primary_link",  1,  60.0/3.6, 1.0, 1500);

//			this.setBEAMHighwayDefaults(4, "secondary",     1,  60.0/3.6, 1.0, 1000);
//			this.setBEAMHighwayDefaults(5, "tertiary",      1,  45.0/3.6, 1.0,  600);
//			this.setBEAMHighwayDefaults(6, "minor",         1,  45.0/3.6, 1.0,  600);
//			this.setBEAMHighwayDefaults(6, "unclassified",  1,  45.0/3.6, 1.0,  600);
//			this.setBEAMHighwayDefaults(6, "residential",   1,  30.0/3.6, 1.0,  600);
//			this.setBEAMHighwayDefaults(6, "living_street", 1,  15.0/3.6, 1.0,  300);

			// Setting the following to considerably smaller values, since there are often traffic signals/non-prio intersections.
			// If someone does a systematic study, please report.  kai, jul'16
			this.setBEAMHighwayDefaults(4, "secondary",     1,  30.0/3.6, 1.0, 1000);
			this.setBEAMHighwayDefaults(4, "secondary_link",     1,  30.0/3.6, 1.0, 1000);
			this.setBEAMHighwayDefaults(5, "tertiary",      1,  25.0/3.6, 1.0,  600);
			this.setBEAMHighwayDefaults(5, "tertiary_link",      1,  25.0/3.6, 1.0,  600);
			this.setBEAMHighwayDefaults(6, "minor",         1,  20.0/3.6, 1.0,  600);
			this.setBEAMHighwayDefaults(6, "residential",   1,  15.0/3.6, 1.0,  600);
			this.setBEAMHighwayDefaults(6, "living_street", 1,  10.0/3.6, 1.0,  300);
			// changing the speed values failed the evacuation ScenarioGenerator test because of a different network -- DESPITE
			// the fact that all the speed values are reset to some other value there.  No idea what happens there. kai, jul'16

			this.setBEAMHighwayDefaults(6, "unclassified",  1,  45.0/3.6, 1.0,  600);
		}
	}

	/**
	 * Replaces OsmNetworkReader.setHighwayDefaults
	 * Sets defaults for converting OSM highway paths into MATSim links, assuming it is no oneway road.
	 *
	 * @param hierarchy The hierarchy layer the highway appears.
	 * @param highwayType The type of highway these defaults are for.
	 * @param lanesPerDirection number of lanes on that road type <em>in each direction</em>
	 * @param freespeed the free speed vehicles can drive on that road type [meters/second]
	 * @param freespeedFactor the factor the freespeed is scaled
	 * @param laneCapacity_vehPerHour the capacity per lane [veh/h]
	 *
	 * @see <a href="http://wiki.openstreetmap.org/wiki/Map_Features#Highway">http://wiki.openstreetmap.org/wiki/Map_Features#Highway</a>
	 */
	public void setBEAMHighwayDefaults(final int hierarchy , final String highwayType, final double lanesPerDirection, final double freespeed, final double freespeedFactor, final double laneCapacity_vehPerHour) {
		setBEAMHighwayDefaults(hierarchy, highwayType, lanesPerDirection, freespeed, freespeedFactor, laneCapacity_vehPerHour, false);
	}

	/**
	 * Replaces OsmNetworkReader.setHighwayDefaults
	 * Sets defaults for converting OSM highway paths into MATSim links.
	 *
	 * @param hierarchy The hierarchy layer the highway appears in.
	 * @param highwayType The type of highway these defaults are for.
	 * @param lanesPerDirection number of lanes on that road type <em>in each direction</em>
	 * @param freespeed the free speed vehicles can drive on that road type [meters/second]
	 * @param freespeedFactor the factor the freespeed is scaled
	 * @param laneCapacity_vehPerHour the capacity per lane [veh/h]
	 * @param oneway <code>true</code> to say that this road is a oneway road
	 */
	public void setBEAMHighwayDefaults(final int hierarchy, final String highwayType, final double lanesPerDirection, final double freespeed,
									   final double freespeedFactor, final double laneCapacity_vehPerHour, final boolean oneway) {
		this.highwayDefaults.put(highwayType, new BEAMHighwayDefaults(hierarchy, lanesPerDirection, freespeed, freespeedFactor, laneCapacity_vehPerHour, oneway));
	}

	/**
	 * Replaces OsmNetworkReader.createLink()
	 * Creates the MATSim link and adds it to the MATSim network
	 * @param way
	 * @param osmID
	 * @param r5ID
	 * @param fromMNode From node MATSim network
	 * @param toMNode To node in MATSim network
	 * @param length
	 * @param flagStrings: allowable modes for the link
	 */
	public ArrayList<Long> createLink(final Way way, long osmID, Integer r5ID, final Node fromMNode, final Node toMNode,
									  final double length, HashSet<String> flagStrings) {
		String highway = way.getTag(TAG_HIGHWAY);
		ArrayList<Long> newLinkIds = new ArrayList<>();


		if ("no".equals(way.getTag(TAG_ACCESS))) {
			return newLinkIds;
		}

		// load defaults
		BEAMHighwayDefaults defaults = this.highwayDefaults.get(highway);
		if (defaults == null) {
			this.unknownHighways.add(highway);
			return newLinkIds;
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
			}
			else {
				log.warn("Could not interpret oneway tag:" + onewayTag + ". Ignoring it.");
			}
		}

		// In case trunks, primary and secondary roads are marked as oneway,
		// the default number of lanes should be two instead of one.
		if(highway.equalsIgnoreCase("trunk") || highway.equalsIgnoreCase("primary") || highway.equalsIgnoreCase("secondary")){
			if((oneway || onewayReverse) && nofLanes == 1.0){
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

		if (this.scaleMaxSpeed) {
			freespeed = freespeed * freespeedFactor;
		}

		// only create link, if both nodes were found, node could be null, since nodes outside a layer were dropped
		Id<Node> fromId = fromMNode.getId();
		Id<Node> toId = toMNode.getId();
		if(this.mNetwork.getNodes().get(fromId) != null && this.mNetwork.getNodes().get(toId) != null){
			String origId = Long.toString(osmID);
			// Not a reverse link, so use from and to-nodes in order
			if (!onewayReverse) {
				Link l = this.mNetwork.getFactory().createLink(Id.create(r5ID, Link.class), this.mNetwork.getNodes().get(fromId), this.mNetwork.getNodes().get(toId));
				l.setLength(length);
				l.setFreespeed(freespeed);
				l.setCapacity(capacity);
				l.setNumberOfLanes(nofLanes);
				l.setAllowedModes(flagStrings);
				if (l instanceof Link) {
					final String id1 = origId;
					NetworkUtils.setOrigId(l, id1 ) ;
					final String type = highway;
					NetworkUtils.setType(l, type);
				}
				this.mNetwork.addLink(l);
				newLinkIds.add(Long.valueOf(r5ID));
				r5ID++;
			}
			// CASE_1: Not onewayReverse AND not oneway. Then we have a 2-way and need to create 2 new links. The
			//			first was created in the previous if clause. The second is created in the following if clause
			// CASE_2: Is oneWay, then link created in previous if-clause
			// CASE_3: Is onewayReverse. Link is created in following if-clause.
			if (!oneway) {
				Link l = this.mNetwork.getFactory().createLink(Id.create(r5ID, Link.class), this.mNetwork.getNodes().get(toId), this.mNetwork.getNodes().get(fromId));
				l.setLength(length);
				l.setFreespeed(freespeed);
				l.setCapacity(capacity);
				l.setNumberOfLanes(nofLanes);
				l.setAllowedModes(flagStrings);
				if (l instanceof Link) {
					final String id1 = origId;
					NetworkUtils.setOrigId(l, id1 ) ;
					final String type = highway;
					NetworkUtils.setType(l, type);
				}
				this.mNetwork.addLink(l);
				newLinkIds.add(Long.valueOf(r5ID));
				r5ID++;
			}

		}
		return newLinkIds;
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
