package beam.router.r5;

import beam.sim.config.BeamConfig;
import com.conveyal.osmlib.OSM;
import com.conveyal.osmlib.Way;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.transit.TransportNetwork;
import com.vividsolutions.jts.geom.Coordinate;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Build the pruned R5 network and MATSim network. These two networks have 1-1 link parity.
 * The R5 edgeIndex values match the MATSim link IDs w/ 1-1 parity.
 */
public class R5MnetBuilder {

    private static final Logger log = LoggerFactory.getLogger(R5MnetBuilder.class);

    private final TransportNetwork r5Network;
    private final Network mNetwork;
    private final GeotoolsTransformation transform;
    private final String osmFile;
    private final Map<Coord, Id<Node>> coordinateNodes = new HashMap<>();

    private int matsimNetworkNodeId = 0;

    /**
     * @param r5Net     R5 network.
     * @param beamConfig config to get Path to mapdb file with OSM data and from-to CRS
     */
    public R5MnetBuilder(TransportNetwork r5Net, BeamConfig beamConfig) {
        BeamConfig.Beam beam = beamConfig.beam();

        osmFile = beam.routing().r5().osmMapdbFile();

        transform = new GeotoolsTransformation(beam.routing().r5().mNetBuilder().fromCRS(),
                beam.routing().r5().mNetBuilder().toCRS());

        r5Network = r5Net;

        mNetwork = NetworkUtils.createNetwork();
    }

    public void buildMNet() {
        // Load the OSM file for retrieving the number of lanes, which is not stored in the R5 network
//        OSM osm = new OSM(osmFile);
        Map<Long, Way> ways = new OSM(osmFile).ways;
        EdgeStore.Edge cursor = r5Network.streetLayer.edgeStore.getCursor();  // Iterator of edges in R5 network
        OsmToMATSim OTM = new OsmToMATSim(mNetwork, true);
        while (cursor.advance()) {
            log.debug("Edge Index:{}. Cursor {}.", cursor.getEdgeIndex(), cursor);
            // TODO - eventually, we should pass each R5 link to OsmToMATSim and do the two-way handling there.
            // Check if we have already seen this OSM way. Skip if we have.
            Integer edgeIndex = cursor.getEdgeIndex();

            Long osmID = cursor.getOSMID();  // id of edge in the OSM db

            Way way = ways.get(osmID);

            Set<Integer> deezNodes = new HashSet<>(2);
            deezNodes.add(cursor.getFromVertex());
            deezNodes.add(cursor.getToVertex());

            Set<String> flagStrings = cursor.getFlags()
                    .stream()
                    .map(ef -> flagToString(ef))
                    .collect(Collectors.toSet());

            //TODO - length needs to be transformed to output CRS. It is important that we calculate the length here
            //TODO - because R5 often breaks single OSM links into pieces.
            double length = cursor.getLengthM();  // R5 link length in meters, as calculated by R5
            // Get start and end coordinates for the edge
            Coordinate tempFromCoord = cursor.getGeometry().getCoordinate();
            Coord fromCoord = transform.transform(new Coord(tempFromCoord.x, tempFromCoord.y));  // MATSim coord
            Coordinate[] tempCoords = cursor.getGeometry().getCoordinates();
            Coordinate tempToCoord = tempCoords[tempCoords.length - 1];
            Coord toCoord = transform.transform(new Coord(tempToCoord.x, tempToCoord.y));

            // Add R5 start and end nodes to the MATSim network
            // Grab existing nodes from mNetwork if they already exist, else make new ones and add to mNetwork
            Node fromNode = getOrMakeNode(fromCoord);
            Node toNode = getOrMakeNode(toCoord);
            if (way == null) {
                // Made up numbers, this is a PT to road network connector or something
                Link link = buildLink(edgeIndex, flagStrings, length, fromNode, toNode);
                mNetwork.addLink(link);
                log.debug("Created special link: {}", link);
            } else {
                Link link = OTM.createLink(way, osmID, edgeIndex, fromNode, toNode, length, (HashSet<String>)flagStrings);
                mNetwork.addLink(link);
                log.debug("Created regular link: {}", link);
            }
        }
    }

    private Link buildLink(Integer edgeIndex, Set<String> flagStrings, double length, Node fromNode, Node toNode) {
        Link link = mNetwork.getFactory().createLink(Id.create(edgeIndex, Link.class), fromNode, toNode);
        link.setLength(length);
        link.setFreespeed(10.0 / 3.6);
        link.setCapacity(300);
        link.setNumberOfLanes(1);
        link.setAllowedModes(flagStrings);
        return link;
    }

    public Network getNetwork() {
        return mNetwork;
    }

    /**
     * Checks whether we already have a MATSim Node at the Coord. If so, returns that Node. If not, makes and adds
     * a new Node to the network.
     *
     * @param coord
     * @return
     */
    private Node getOrMakeNode(Coord coord) {
        Node dummyNode;
        Id<Node> id;
        if (coordinateNodes.containsKey(coord)) {
            id = this.coordinateNodes.get(coord);
            dummyNode = this.mNetwork.getNodes().get(id);
        } else { // need to make new fromID and node and increment the matsimNetworkNodeId
            id = Id.createNodeId(this.matsimNetworkNodeId);
            this.matsimNetworkNodeId++;
            dummyNode = NetworkUtils.createAndAddNode(mNetwork, id, coord);
            this.coordinateNodes.put(coord, id);
        }
        return dummyNode;
    }

    /**
     * Returns the corresponding mode string for the EdgeFlag. See com.conveyal.r5.streets.EdgeStore.EdgeFlag and
     * org.matsim.api.core.v01.TransportMode for definitions.
     *
     * @param flag EdgeFlag describing link travel modes.
     * @return
     */
    //TODO - we should probably make the cases settable for the case that we want to use custom modes in the MATSim net
    private static String flagToString(EdgeStore.EdgeFlag flag) {
        String out = null;
        switch (flag) {
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
        return out;
    }

}
