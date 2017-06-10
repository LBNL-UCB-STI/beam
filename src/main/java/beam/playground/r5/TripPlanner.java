package beam.playground.r5;

import com.conveyal.r5.api.ProfileResponse;
import com.conveyal.r5.api.util.*;
import com.conveyal.r5.point_to_point.builder.PointToPointQuery;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.profile.StreetPath;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;

import static beam.utils.Collections.ifPresentThenForEach;

/**
 * Authors ahmar.nadeem
 *         zeeshan.bilal
 * Created on 6/6/2017.
 */
public class TripPlanner {
    private static final Logger LOG = LoggerFactory.getLogger(TripPlanner.class);
    private static final String GRAPH_FILE = "network.dat";
    private static final String OSM_FILE = "osm.mapdb";

    private TransportNetwork transportNetwork = null;

    public static void main(String[] args) throws Exception {

        TripPlanner self = new TripPlanner();
        // Loading graph
        self.init(args);
        //calculate Route
        self.logProfileResponse(self.calcRoute2());
    }

    private void init(String[] parms) throws Exception {
        String networkDir = "";
        if (parms != null && parms.length > 0) {
            // first preference, command line arguments also allow to override configuration for a run.
            networkDir = parms[0];
        } else { //TODO: second preference, configuration file - need to integrate with @BeamConfig after discussion with @Colin
            //last preference, if nither of the above are defined then go with some default burned option
            networkDir = Paths.get(System.getProperty("user.home"),"beam", "output").toString();
        }

        loadGraph(networkDir);
    }

    private boolean loadGraph(String networkDir) throws Exception {
        File networkFile = null;
        File mapdbFile = null;
        if (Files.exists(Paths.get(networkDir))) {
            Path networkPath = Paths.get(networkDir, GRAPH_FILE);
            if (Files.isReadable(networkPath)) {
                networkFile = networkPath.toFile();
            }

            Path osmPath = Paths.get(networkDir, OSM_FILE);
            if (Files.isReadable(osmPath)) {
                mapdbFile = osmPath.toFile();
            }
        }

        // Loading graph
        if (networkFile == null) {
            LOG.error("Fail to build transport network, {} not available.", GRAPH_FILE);
            return false;
        }

        transportNetwork = TransportNetwork.read(networkFile);

        // Optional used to get street names:
        if (mapdbFile == null) {
            LOG.warn("OSM read action ignored, {} not available.", OSM_FILE);
        } else {
            transportNetwork.readOSM(mapdbFile);
        }

        return true;
    }

    public ProfileRequest buildRequest(boolean isTransit) {
        ProfileRequest profileRequest = new ProfileRequest();
        // Set timezone to timezone of transport network
        profileRequest.zoneId = transportNetwork.getTimeZone();
        profileRequest.fromLat = 45.547716775429045;
        profileRequest.fromLon = -122.68020629882812;
        profileRequest.toLat = 45.554628830194815;
        profileRequest.toLon = -122.66613006591795;
        profileRequest.wheelchair = false;
        profileRequest.bikeTrafficStress = 4;
        // TODO: time need to get from request
        profileRequest.setTime("2015-02-05T07:30+05:00", "2015-02-05T10:30+05:00");
        if (isTransit) {
            profileRequest.transitModes = EnumSet.of(TransitModes.TRANSIT, TransitModes.BUS,
                    TransitModes.SUBWAY, TransitModes.RAIL);
        }
        profileRequest.accessModes = EnumSet.of(LegMode.WALK);
        profileRequest.egressModes = EnumSet.of(LegMode.WALK);
        profileRequest.directModes = EnumSet.of(LegMode.WALK, LegMode.BICYCLE);

        return profileRequest;
    }

    public long calcRoute() {
        StreetRouter streetRouter = new StreetRouter(transportNetwork.streetLayer);
        ProfileRequest profileRequest = buildRequest(false);
        streetRouter.profileRequest = profileRequest;
        streetRouter.streetMode = StreetMode.WALK;

        // TODO use target pruning instead of a distance limit
        streetRouter.distanceLimitMeters = 100_000;

        streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon);
        streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon);

        streetRouter.route();

        // Gets lowest weight state for end coordinate split
        StreetRouter.State lastState = streetRouter.getState(streetRouter.getDestinationSplit());

        StreetPath streetPath = new StreetPath(lastState, transportNetwork, false);

        long totalDistance = 0;
        int stateIdx = 0;

        // TODO: this can be improved since end and start vertices are the same
        // in all the edges.
        for (StreetRouter.State state : streetPath.getStates()) {
            Integer edgeIdx = state.backEdge;
            if (!(edgeIdx == -1 || edgeIdx == null)) {
                EdgeStore.Edge edge = transportNetwork.streetLayer.edgeStore.getCursor(edgeIdx);
                LOG.info("{} - Lat/Long for edgeIndex [{}] are [{}]", stateIdx++, edgeIdx, edge.getGeometry());
                LOG.info("\tmode [{}]", state.streetMode);
                LOG.info("\tweight [{}]", state.weight);
                LOG.info("\tduration sec [{}:{}]", state.getDurationSeconds()/60, state.getDurationSeconds()%60);
                LOG.info("\tdistance [{}]", state.distance / 1000);
            }
        }
        return totalDistance;
    }

    public ProfileResponse calcRoute2() {
        PointToPointQuery pointToPointQuery = new PointToPointQuery(transportNetwork);
        // Gets a response:
        ProfileResponse profileResponse = pointToPointQuery.getPlan(buildRequest(false));
        return profileResponse;
    }

    public void logProfileResponse(ProfileResponse profileResponse) {
        LOG.info("{} OPTIONS returned in the profileResponse", profileResponse.getOptions().size());

        ifPresentThenForEach(profileResponse.getOptions(), option -> {
            LOG.info("*****OPTION START*****");
            LOG.info("Option start with summary: {}", option.summary);
            Stats stats = option.stats;
            LOG.info("Average: {}", stats.avg);
            LOG.info("MIN: {}", stats.min);
            LOG.info("MAX: {}", stats.max);
            LOG.info("NUM: {}", stats.num);

            ifPresentThenForEach(option.itinerary, iten -> {
                LOG.info("\t*****ITINERARY START*****");
                LOG.info("\tTotal Distance is: {}", iten.distance);
                LOG.info("\tTotal Duration is: {}", convertIntToTimeFormat(iten.duration));
                LOG.info("\tStart Time is: {}", iten.startTime);
                LOG.info("\tEnd Time is: {}", iten.endTime);
                LOG.info("\tTotal Waiting Time is: {}", convertIntToTimeFormat(iten.waitingTime));
                LOG.info("\tTotal Transit Time is: {}", convertIntToTimeFormat(iten.transitTime));
                LOG.info("\tTotal Walk Time is: {}", convertIntToTimeFormat(iten.walkTime));

                PointToPointConnection conn = iten.connection;

                ifPresentThenForEach(conn.transit, transit -> {
                    LOG.info("\t\t*****TRANSIT START*****");
                    LOG.info("\t\tTransit Time: {}", convertIntToTimeFormat(transit.time));
                    LOG.info("\t\tTransit Pattern: {}", transit.pattern);
                    LOG.info("\t\t*****TRANSIT END*****");
                });

                LOG.info("P2P Connection Access: {}", conn.access);
                LOG.info("P2P Connection Egress: {}", conn.egress);

                LOG.info("\t*****ITINERARY END*****");
            });

            ifPresentThenForEach(option.access, segment -> {
                LOG.info("\t*****SEGMENT START*****");

                LOG.info("\tAccess MODE: {}", segment.mode);
                LOG.info("\tAccess Distance: {}", segment.distance);
                LOG.info("\tTotal Edge Distance: {}", segment.streetEdges.parallelStream().mapToInt(edge -> edge.distance).sum());
                LOG.info("\tAccess Elevation: {}", segment.elevation);
                LOG.info("\tAccess Duration: {}", convertIntToTimeFormat(segment.duration));

                LineString geom = segment.geometry;
                LOG.info("\tSegment Area: {}", geom.getArea());
                LOG.info("\tCoordinates: {}", geom.getCoordinate());
                LOG.info("\tBoundary Dimensions are: {}", geom.getBoundaryDimension());
                LOG.info("\tSegment Starting Point: {}", geom.getStartPoint());
                LOG.info("\tEnd Point is: {}", geom.getEndPoint());
                LOG.info("\tGeometry Dimensions: {}", geom.getDimension());
                LOG.info("\tGeometry Type: {}", geom.getGeometryType());
                LOG.info("\tSegment Length: {}", geom.getLength());
                LOG.info("\tSegment Num Points: {}", geom.getNumPoints());

                Coordinate coordinate = geom.getCoordinate();
                LOG.info("\tCoordinate-X: {}", coordinate.x);
                LOG.info("\tCoordinate-Y: {}", coordinate.y);
                LOG.info("\tCoordinate-Z: {}", coordinate.z);

                LOG.info("\tTotal Edges are: {}", segment.streetEdges.size());
                ifPresentThenForEach(segment.streetEdges, edge -> {
                    LOG.info("\t\t*****EDGE START*****");
                    LOG.info("\t\tStreet Name: {}", edge.streetName);
                    LOG.info("\t\tMode: {}", edge.mode);
                    LOG.info("\t\tDistance: {}", edge.distance);
                    LOG.info("\t\tEdge Id: {}", edge.edgeId);

                    LOG.info("\t\t*****Edge END*****");
                });

                LOG.info("\t*****SEGMENT END*****");
            });
            LOG.info("*****OPTION END*****");
        });
        LOG.info("{} PATTERNS returned in the profileResponse", profileResponse.getPatterns().size());
    }

    private static String convertIntToTimeFormat(final int timeInSeconds) {

        int hours = timeInSeconds / 3600;
        int remainder = timeInSeconds % 3600;
        int mins = remainder / 60;
        remainder = remainder % 60;
        int secs = remainder;

        return String.format("%02d:%02d:%02d", hours, mins, secs);
    }
}
