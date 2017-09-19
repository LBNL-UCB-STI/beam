package beam.router.r5;

import com.conveyal.osmlib.OSM;
import com.conveyal.osmlib.Way;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.transit.TransportNetwork;
import com.vividsolutions.jts.geom.Coordinate;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.NetworkCleaner;
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 *
 *
 * Created by Andrew A. Campbell on 6/7/17.
 */
public class MNetfromR5 {
	private static final Logger log = Logger.getLogger(MNetfromR5.class);

	private TransportNetwork r5Network = null;  // R5 mNetowrk
	private Network mNetowrk = null;  // MATSim mNetowrk
	//TODO - the CRS should be settable not hard coded
	private String fromCRS = "EPSG:4326";  // WGS84
	private String toCRS = "EPSG:26910";  // UTM10N
	GeotoolsTransformation tranform = new GeotoolsTransformation(this.fromCRS, this.toCRS);
	private String osmFile;

	private HashMap<Coord, Id<Node>> nodeMap = new HashMap<>();  // Maps x,y Coord to node ID
	private int nodeId = 0;  // Current new MATSim network Node ids
	private EnumSet<EdgeStore.EdgeFlag> modeFlags = EnumSet.noneOf(EdgeStore.EdgeFlag.class); // modes to keep

	private Long lastProcessedOSMId;
	private Set<Integer> lastProcessedNodes = new HashSet<>(2);

	private HashMap<Integer, Long> r5ToMNetMap = new HashMap<>();

	/**
	 *
	 * @param r5NetPath Path to R5 network.dat file.
	 * @param modeFlags EnumSet defining the modes to be included in the network. See
	 *                     com.conveyal.r5.streets.EdgeStore.EdgeFlag for EdgeFlag definitions.
	 */
	public MNetfromR5(String r5NetPath, String osmPath, EnumSet<EdgeStore.EdgeFlag> modeFlags){
		this.osmFile = osmPath;
		File netFile = new File(r5NetPath);
		log.info("Found R5 Transport Network file, loading....");
		try {
			this.r5Network = TransportNetwork.read(netFile);
		} catch (Exception e) {
			e.printStackTrace();
		}
		this.modeFlags = modeFlags;
		this.mNetowrk = NetworkUtils.createNetwork();
	}

	/**
	 * Defaults to only using automobiles for the network.
	 * @param r5NetPath
	 */
	public MNetfromR5(String r5NetPath, String osmPath) {
		this(r5NetPath, osmPath, EnumSet.of(EdgeStore.EdgeFlag.ALLOWS_CAR));
	}

	private void buildMNet() {
		// Load the OSM file for retrieving the number of lanes, which is not stored in the R5 network
		OSM osm = new OSM(this.osmFile);
		Map<Long, Way> ways = osm.ways;
		EdgeStore.Edge cursor = r5Network.streetLayer.edgeStore.getCursor();  // Iterator of edges in R5 network
		OsmToMATSim OTM = new OsmToMATSim(this.mNetowrk, this.tranform, true);
		ArrayList<Long> newMLinkIDs = new ArrayList<>();
		while (cursor.advance()) {
			// TODO - eventually, we should pass each R5 link to OsmToMATSim and do the two-way handling there.
			// Check if we have already seen this OSM way. Skip if we have.
			Integer edgeIndex = cursor.getEdgeIndex();
			if (newMLinkIDs.size() == 2){
				if (((edgeIndex-1)%2) == 1){  // Previous was an odd edge. Hopefully this never happens.
					System.out.println("WHAT NOW?");
					break;
				}
//				if (! Long.valueOf(edgeIndex-1).equals(newMLinkIDs.get(0))){
//					int i = 1;
//				}
				this.r5ToMNetMap.put(edgeIndex-1, newMLinkIDs.get(0));
//				if (! Long.valueOf(edgeIndex).equals(newMLinkIDs.get(1))){
//					int i = 1;
//				}
				this.r5ToMNetMap.put(edgeIndex, newMLinkIDs.get(1));
				newMLinkIDs = new ArrayList<>();
				continue;
			} else if (newMLinkIDs.size() == 1){
//				if (! Long.valueOf(edgeIndex-1).equals(newMLinkIDs.get(0))){
//					int i = 1;
//				}
				this.r5ToMNetMap.put(edgeIndex-1, newMLinkIDs.get(0));
			} else if (newMLinkIDs.size() > 2){
				System.out.print("WHAT NOW AGAIN?");
				break;
			}
			Long osmID = cursor.getOSMID();  // id of edge in the OSM db
			Way way = ways.get(osmID);
			Set<Integer> deezNodes = new HashSet<>(2);
			deezNodes.add(cursor.getFromVertex());
			deezNodes.add(cursor.getToVertex());
			if (osmID.equals(this.lastProcessedOSMId) && deezNodes.equals(this.lastProcessedNodes)) {
				log.info("EDGE SKIPPED - already processed edge." + "From: " +
						String.valueOf(cursor.getFromVertex()) + " To: " +
						String.valueOf(cursor.getToVertex()) +  " OSM ID: " + String.valueOf(osmID));
				newMLinkIDs = new ArrayList<>();
				continue;
			}
			// Check if this edge permits any of the desired modes.
			EnumSet<EdgeStore.EdgeFlag> flags = cursor.getFlags();
			flags.retainAll(this.modeFlags);
			if (flags.isEmpty()) {
				log.info("EDGE SKIPPED - no allowable modes: " + "cursor: " + String.valueOf(cursor.getEdgeIndex()) +
				 "OSM ID: " + String.valueOf(osmID));
				newMLinkIDs = new ArrayList<>();
				continue;
			}
			// Convert flags to strings that the MATSim network will recognize.
			HashSet<String> flagStrings = new HashSet<>();
			for (EdgeStore.EdgeFlag eF : flags) {
				flagStrings.add(flagToString(eF));
			}
			////
			//Add the edge and its nodes
			////
//			int lanes = Integer.valueOf(way.getTag("lanes"));
//			Integer idx = cursor.getEdgeIndex();
			//TODO - length needs to be transformed to output CRS. It is important that we calculate the length here
			//TODO - because R5 often breaks single OSM links into pieces.
			double length = cursor.getLengthM();  // R5 link length in meters, as calculated by R5
//			double speed = cursor.getSpeedMs();
			// Get start and end coordinates for the edge
			Coordinate tempFromCoord = cursor.getGeometry().getCoordinate();
			Coord fromCoord = transformCRS(new Coord(tempFromCoord.x, tempFromCoord.y));  // MATSim coord
			Coordinate[] tempCoords = cursor.getGeometry().getCoordinates();
			Coordinate tempToCoord = tempCoords[tempCoords.length - 1];
			Coord toCoord = transformCRS(new Coord(tempToCoord.x, tempToCoord.y));
			// Add R5 start and end nodes to the MATSim network
			// Grab existing nodes from mNetwork if they already exist, else make new ones and add to mNetwork
			Node fromNode = this.getOrMakeNode(fromCoord);
			Node toNode = this.getOrMakeNode(toCoord);
			// Make and add the link (only if way exists)

			if (way != null) {
//				System.out.println(way.getTag("highway"));
				newMLinkIDs = OTM.createLink(way, osmID, edgeIndex, fromNode, toNode, length, flagStrings);
				this.lastProcessedOSMId = osmID;
				this.lastProcessedNodes = deezNodes;
			} else {
				newMLinkIDs = new ArrayList<>(); // reset the IDs
			}
		}
	}

	public void setTransformCRS(String to, String from){
		this.toCRS = to;
		this.fromCRS = from;
	}

	public void writeMNet(String mNetPath){
		NetworkWriter nw = new NetworkWriter(this.mNetowrk);
		nw.write(mNetPath);
	}


	/**
	 * Checks whether we already have a MATSim Node at the Coord. If so, returns that Node. If not, makes and adds
	 * a new Node to the network.
	 * @param coord
	 * @return
	 */
	private Node getOrMakeNode(Coord coord){
		Node dummyNode;
		Id<Node> id;
		if (this.nodeMap.containsKey(coord)){  // node already exists.
//			log.info("NODE ALREADY EXISTS");
			id = this.nodeMap.get(coord);
			dummyNode = this.mNetowrk.getNodes().get(id);
		} else { // need to make new fromID and node and increment the nodeId
			id = Id.createNodeId(this.nodeId);
			this.nodeId++;
			dummyNode = NetworkUtils.createAndAddNode(mNetowrk, id, coord);
			this.nodeMap.put(coord, id);
		}
		return dummyNode;
	}

	/*
	Tranforms from WGS84 to UTM 26910
	/TODO - this helper is not needed now that we have this.transform. But setTransform() needs to be updated
	 */
	private Coord transformCRS(Coord coord){
		GeotoolsTransformation tranform = new GeotoolsTransformation(this.fromCRS, this.toCRS);
		return tranform.transform(coord);
	}

	/**
	 * Returns the corresponding mode string for the EdgeFlag. See com.conveyal.r5.streets.EdgeStore.EdgeFlag and
	 * org.matsim.api.core.v01.TransportMode for definitions.
	 * @param flag EdgeFlag describing link travel modes.
	 * @return
	 */
	//TODO - we should probably make the cases settable for the case that we want to use custom modes in the MATSim net
	private String flagToString(EdgeStore.EdgeFlag flag){
		String out = null;
		switch (flag){
			case ALLOWS_PEDESTRIAN:
				out = "walk";
				break;
			case ALLOWS_BIKE:
				out = "bike";
				break;
			case ALLOWS_CAR:
				out = "car";
				break;
		}
		try {
			return out;
		}
		catch (NullPointerException exec) {
			log.error("No matching flag");
			exec.printStackTrace();
		}
		return out;
	}


	// TODO - delete this local class if we don't need to use link to/from nodes as keys in a Map
	/**
	 * Class for using tuples of MATSim Coords as keys for a Map
	 */
	public class CoordKey {

		private final Coord fromCoord;
		private final Coord toCoord;

		public CoordKey(Coord fromCoord, Coord toCoord) {
			this.fromCoord = fromCoord;
			this.toCoord = toCoord;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof CoordKey)) return false;
			CoordKey key = (CoordKey) o;
			return fromCoord == key.fromCoord && toCoord == key.toCoord;
		}

		@Override
		public int hashCode() {
			int result = Integer.valueOf(fromCoord.toString());
			result = 31 * result + Integer.valueOf(toCoord.toString());
			return result;
		}
	}

	/**
	 * This class is based off of MATSim's OsmNetworkReader. Particularly, it is used to generate all the link
	 * attributes in the MATSim network based on the OSM way's tags the same way OsmNetworkReader does.
	 */
	public class OsmWayToMATSim {

	}

	/**
	 * Input args:
	 * 0 - path to network.dat file
	 * 1 - path to OSM mapdb file
	 * 2 - output path for MATSim network
	 * 3 - path to new r5 network.dat file
	 * 4 - [OPTIONAL] comma-separated list of EdgeFlag enum names. See com.conveyal.r5.streets.EdgeStore.EdgeFlag for
	 * EdgeFlag definitions.
	 * @param args
	 */
	public static void main(String[] args) throws IOException {
		String inFolder = args[0];
		String osmPath = args[1];
		String mNetPath = args[2];
		// If mode flags passed, use the constructor with the modeFlags parameter
		MNetfromR5 mn = null;
		if (args.length > 3){
			String[] flagStrings = args[4].trim().split(",");
			EnumSet<EdgeStore.EdgeFlag> modeFlags = EnumSet.noneOf(EdgeStore.EdgeFlag.class);
			for (String f: flagStrings){
				modeFlags.add(EdgeStore.EdgeFlag.valueOf(f));
			}
			System.out.println("USING MODE FLAGS HURRAY!!!!!!!!!");
			mn = new MNetfromR5(inFolder, osmPath, modeFlags);
		}
		// otherwise use the default constructor
		else {
			mn = new MNetfromR5(inFolder, osmPath);
		}

		mn.buildMNet();
		log.info("Finished building network.");
		NetworkCleaner nC = new NetworkCleaner();
		log.info("Running NetowrkCleaner");
		nC.run(mn.mNetowrk);
		log.info("Number of links:" + mn.mNetowrk.getLinks().size());
		log.info("Number of nodes: " + mn.mNetowrk.getNodes().size());
		mn.writeMNet(mNetPath);

		// Prune R5 links removed by NetworkCleaner
		EdgeStore.Edge cursor = mn.r5Network.streetLayer.edgeStore.getCursor();
		Integer removed = 0;
		Integer kept = 0;
		while (cursor.advance()){
			Integer edgeIndex = cursor.getEdgeIndex();
			if (mn.r5ToMNetMap.containsKey(edgeIndex)) { // This R5 link is in the pre-cleaning MATSim network.
				Long mNetIDLong = mn.r5ToMNetMap.get(edgeIndex);
				Id<Link> mNetID = Id.create(mNetIDLong, Link.class);
				if (!mn.mNetowrk.getLinks().containsKey(mNetID)) {
					removed++;
					log.info("R5 link not in post-cleaning MATSim network: " + String.valueOf(edgeIndex));
					// This R5 link has been removed. Need to set permissions to "remove" from network
					cursor.clearFlag(EdgeStore.EdgeFlag.ALLOWS_CAR);
					cursor.clearFlag(EdgeStore.EdgeFlag.ALLOWS_BIKE);
					cursor.clearFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
				} else { // R5 link in the pre and post-cleaning MATSim network
					kept++;
				}
			} else { // not in the pre-cleaning MATSim network
				log.info("R5 Link not in the pre-cleaning MATSim network: "  +String.valueOf(edgeIndex));
			}
		}
		System.out.println("No. R5 Links Remvoed: " + String.valueOf(removed));
		System.out.println("No. R5 Links kept: " + String.valueOf(kept));
		System.out.println("No. of post-cleaning MATSim links: " + String.valueOf(mn.mNetowrk.getLinks().size()));
		mn.r5Network.write(new File(args[3]));
		Integer n = 0;
		for (Integer k: mn.r5ToMNetMap.keySet()){
			Long lK = new Long(k);
			Long v = mn.r5ToMNetMap.get(k);
			if (! lK.equals(v)){
				n++;
			}
		}
		System.out.println("No. non-matching link IDs: " + String.valueOf(n));
	}

}

