package beam.router.r5;

import beam.sim.common.GeoUtils;
import beam.sim.config.BeamConfig;
import beam.utils.osm.WayFixer$;
import com.conveyal.osmlib.OSM;
import com.conveyal.osmlib.OSMEntity;
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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

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
    private final HighwaySetting highwaySetting;
    private final BeamConfig beamConfig;

    private int matsimNetworkNodeId = 0;

    /**
     * @param r5Net      R5 network.
     * @param beamConfig config to get Path to mapdb file with OSM data and from-to CRS
     */
    public R5MnetBuilder(TransportNetwork r5Net, BeamConfig beamConfig, HighwaySetting highwaySetting) {
        this.r5Network = r5Net;
        this.beamConfig = beamConfig;
        this.highwaySetting = highwaySetting;

        osmFile = beamConfig.beam().routing().r5().osmMapdbFile();
        transform = new GeotoolsTransformation(
                beamConfig.beam().routing().r5().mNetBuilder().fromCRS(),
                beamConfig.beam().routing().r5().mNetBuilder().toCRS()
        );
        mNetwork = NetworkUtils.createNetwork();
    }

    public void buildMNet() throws IOException {
        // Load the OSM file for retrieving the number of lanes, which is not stored in the R5 network
        OSM osm = new OSM(osmFile);
        Map<Long, Way> ways = osm.ways;
        WayFixer$.MODULE$.fix(ways, beamConfig);

        EdgeStore.Edge cursor = r5Network.streetLayer.edgeStore.getCursor();  // Iterator of edges in R5 network
        OsmToMATSim OTM = new OsmToMATSim(mNetwork, true, highwaySetting.speedsMeterPerSecondMap, highwaySetting.capacityMap, highwaySetting.lanesMap, highwaySetting.alphaMap, highwaySetting.betaMap);

        int numberOfFixes = 0;
        HashMap<String, Integer> highwayTypeToCounts = new HashMap<>();

        BufferedWriter bwr = new BufferedWriter(new FileWriter(new File("/home/rutvik/Desktop/hgv/sfbay-link-2.csv")));
//        BufferedWriter bwr1 = new BufferedWriter(new FileWriter(new File("/home/rutvik/Desktop/hgv/i580.csv")));
        StringBuffer s = new StringBuffer();
//        StringBuffer s1 = new StringBuffer();
//        s.append("link_id,highway=primary,highway=trunk,highway=motorway,hgv=designated,hgv=yes\n");
//        s.append("link_id,highway=motorway,hgv=designated,hgv=yes\n");
        s.append("link_id,hgv\n");
//        s1.append("link_id,osm_id,motorway,hgvYes,hgvDesignated,hgvNo,fromX,fromY,toX,toY\n");

        while (cursor.advance()) {
//            log.debug("Edge Index:{}. Cursor {}.", cursor.getEdgeIndex(), cursor);
            // TODO - eventually, we should pass each R5 link to OsmToMATSim and do the two-way handling there.
            // Check if we have already seen this OSM way. Skip if we have.
            Integer edgeIndex = cursor.getEdgeIndex();
            long osmID = cursor.getOSMID();  // id of edge in the OSM db
            Way way = ways.get(osmID);

            Set<Integer> deezNodes = new HashSet<>(2);
            deezNodes.add(cursor.getFromVertex());
            deezNodes.add(cursor.getToVertex());

            final HashSet<String> flagStrings = new HashSet<>();
            for (EdgeStore.EdgeFlag eF : cursor.getFlags()) {
                String flagString = flagToString(eF);
                if (!flagString.isEmpty()) {
                    flagStrings.add(flagToString(eF));
                }
            }

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
            Link link;

//            boolean hgv = false;
//            if (way != null) {
//                String longString = way.tags.toString();
//                boolean hgv = longString.contains("highway=motorway") || longString.contains("hgv=designated") || longString.contains("hgv=yes");
//                boolean i580 = longString.contains("ref=I 580");
//                if (i580) {
//                    int id = link.getId();
//                    int motorway = longString.contains("highway=motorway") ? 1 : 0;
//                    int hgvDesignated = longString.contains("hgv=designated") ? 1 : 0;
//                    int hgvYes = longString.contains("hgv=yes") ? 1 : 0;
//                    int hgvNo = longString.contains("hgv=no") ? 1 : 0;
//
//                }
//                if (hgv && !i580) {
//                    s.append(edgeIndex + ",true\n");
//                }
//            }

            if (way == null) {
                // Made up numbers, this is a PT to road network connector or something
                link = buildLink(edgeIndex, flagStrings, length, fromNode, toNode);
                mNetwork.addLink(link);
                log.debug("Created special link: {}", link);
            } else {
                link = OTM.createLink(way, osmID, edgeIndex, fromNode, toNode, length, flagStrings);
//                link.getAttributes().putAttribute("hgv", hgv);
                String longString = way.tags.toString();
                boolean hgv = /*longString.contains("highway=motorway") || */ longString.contains("hgv=designated") || longString.contains("hgv=yes");
                boolean i580 = longString.contains("ref=I 580");
                if (i580) {
                    int motorway = longString.contains("highway=motorway") ? 1 : 0;
                    int hgvDesignated = longString.contains("hgv=designated") ? 1 : 0;
                    int hgvYes = longString.contains("hgv=yes") ? 1 : 0;
                    int hgvNo = longString.contains("hgv=no") ? 1 : 0;
                    double fromX = GeoUtils.GeoUtilsNad83().utm2Wgs(fromNode.getCoord()).getX();
                    double fromY = GeoUtils.GeoUtilsNad83().utm2Wgs(fromNode.getCoord()).getY();
                    double toX = GeoUtils.GeoUtilsNad83().utm2Wgs(toNode.getCoord()).getX();
                    double toY = GeoUtils.GeoUtilsNad83().utm2Wgs(toNode.getCoord()).getY();
//                    s1.append(link.getId() + "," + osmID + "," + motorway + "," + hgvDesignated + "," + hgvYes + "," + hgvNo + "," + fromX + "," + fromY + "," + toX + "," + toY + "\n");
                }
                if (hgv /* && !i580*/) {
                    s.append(edgeIndex + ",true\n");
                }
                mNetwork.addLink(link);
                log.debug("Created regular link: {}", link);
            }
            if (fromNode.getId() == toNode.getId()) {
                cursor.setLengthMm(1);
                cursor.setSpeed((short) 2905); // 65 miles per hour
                link.setLength(0.001);
                link.setCapacity(10000);
                link.setFreespeed(29.0576);   // 65 miles per hour
                numberOfFixes += 1;
            }
        }

        bwr.write(s.toString());
        bwr.flush();
        bwr.close();
//        bwr1.write(s1.toString());
//        bwr1.flush();
//        bwr1.close();

        if (numberOfFixes > 0) {
            log.warn("Fixed {} links which were having the same `fromNode` and `toNode`", numberOfFixes);
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
        final boolean nodeAlreadyExists = coordinateNodes.containsKey(coord);
        if (nodeAlreadyExists) {
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
            case ALLOWS_WHEELCHAIR:
            case LIMITED_WHEELCHAIR:
            case STAIRS:
            case CROSSING:
            case PLATFORM:
            case SIDEWALK:
            case ELEVATOR:
                out = "walk";
                break;
            case ALLOWS_BIKE:
            case BIKE_LTS_1:
            case BIKE_LTS_2:
            case BIKE_LTS_3:
            case BIKE_LTS_4:
            case BIKE_PATH:
                out = "bike";
                break;
            case ALLOWS_CAR:
            case ROUNDABOUT:
                out = "car";
                break;
            case LINK:
            case LINKABLE:
            case BOGUS_NAME:
            case SLOPE_OVERRIDE:
            case NO_THRU_TRAFFIC:
            case NO_THRU_TRAFFIC_CAR:
            case NO_THRU_TRAFFIC_BIKE:
            case NO_THRU_TRAFFIC_PEDESTRIAN:
                out = "";
                break;
        }
        return out;
    }

}
