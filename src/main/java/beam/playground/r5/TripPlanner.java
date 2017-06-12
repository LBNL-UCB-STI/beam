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
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;

import static beam.utils.Collections.ifPresentThenForEach;

/**
 * Authors ahmar.nadeem
 * zeeshan.bilal
 * Created on 6/6/2017.
 */
public class TripPlanner {
    private static final Logger LOG = LoggerFactory.getLogger(TripPlanner.class);
    private static final String GRAPH_FILE = "network.dat";
    private static final String OSM_FILE = "osm.mapdb";

//    private TransportNetwork transportNetwork = null;

    public static void main(String[] args) throws Exception {

        /*TripPlanner self = new TripPlanner();
        // Loading graph
        TransportNetwork transportNetwork = self.init(args);

        ProfileRequest request = self.buildRequest(false, 45.547716775429045,
                -122.68020629882812, 45.554628830194815, -122.66613006591795,
                false, 4, LocalDateTime.now(), LocalDateTime.now(), transportNetwork.getTimeZone());
        //calculate Route
        self.logProfileResponse(self.calcRoute2(transportNetwork, request));*/
    }

    /**
     * Initialize the transport network.
     *
     * @param parms
     * @return
     * @throws Exception
     */
    protected TransportNetwork init(String[] parms) throws Exception {
        String networkDir = "";
        if (parms != null && parms.length > 0) {
            // first preference, command line arguments also allow to override configuration for a run.
            networkDir = parms[0];
        } else { //TODO: second preference, configuration file - need to integrate with @BeamConfig after discussion with @Colin
            //last preference, if nither of the above are defined then go with some default burned option
            networkDir = Paths.get(System.getProperty("user.home"), "beam", "output").toString();
        }

        return loadGraph(networkDir);
    }

    /**
     * Read the network.dat and osm file (if any) and return the TransportNetwork object for further usage of available data.
     *
     * @param networkDir
     * @return
     * @throws Exception
     */
    private TransportNetwork loadGraph(String networkDir) throws Exception {
        TransportNetwork transportNetwork = null;
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
            return null;
        }

        transportNetwork = TransportNetwork.read(networkFile);

        // Optional used to get street names:
        if (mapdbFile == null) {
            LOG.warn("OSM read action ignored, {} not available.", OSM_FILE);
        } else {
            transportNetwork.readOSM(mapdbFile);
        }

        return transportNetwork;
    }

    /**
     * Build profile request based on the parameters provided.
     *
     * @param isTransit
     * @return
     */
    public ProfileRequest buildRequest(final boolean isTransit, final double fromLatitude, final double fromLongitude, final double toLatitude, final double toLongitude
            , final boolean isWheelChair, final int bikeTrafficStress, final DateTime fromTime, final DateTime toTime, final ZoneId zoneId) {
        ProfileRequest profileRequest = new ProfileRequest();
        profileRequest.zoneId = zoneId;
        profileRequest.fromLat = fromLatitude;
        profileRequest.fromLon = fromLongitude;
        profileRequest.toLat = toLatitude;
        profileRequest.toLon = toLongitude;
        profileRequest.wheelchair = isWheelChair;
        profileRequest.bikeTrafficStress = bikeTrafficStress;
        profileRequest.setTime(fromTime.toString(), toTime.toString());

        if (isTransit) {
            profileRequest.transitModes = EnumSet.of(TransitModes.TRANSIT, TransitModes.BUS,
                    TransitModes.SUBWAY, TransitModes.RAIL);
        }
        profileRequest.accessModes = EnumSet.of(LegMode.WALK);
        profileRequest.egressModes = EnumSet.of(LegMode.WALK);
        profileRequest.directModes = EnumSet.of(LegMode.WALK, LegMode.BICYCLE);

        return profileRequest;
    }

    /**
     * Calculate route based on the requested plan
     *
     * @param profileRequest
     * @return
     */
    public long calcRoute(TransportNetwork transportNetwork, ProfileRequest profileRequest) {
        StreetRouter streetRouter = new StreetRouter(transportNetwork.streetLayer);
//        ProfileRequest profileRequest = buildRequest(false);
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
                LOG.info("\tduration sec [{}:{}]", state.getDurationSeconds() / 60, state.getDurationSeconds() % 60);
                LOG.info("\tdistance [{}]", state.distance / 1000);
            }
        }
        return totalDistance;
    }

    /**
     * Calculate route on the basis of profile request.
     *
     * @param profileRequest
     * @return
     */
    public ProfileResponse calcRoute2(TransportNetwork transportNetwork, ProfileRequest profileRequest) {
        PointToPointQuery pointToPointQuery = new PointToPointQuery(transportNetwork);
        // Gets a response:
        ProfileResponse profileResponse = pointToPointQuery.getPlan(profileRequest);
        return profileResponse;
    }

    /**
     * To log the response against a plan request
     *
     * @param profileResponse
     */
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
                LOG.info("\tTransfers are: {}", iten.transfers);

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

                ifPresentThenForEach(segment.alerts, alert -> {
                    LOG.info("\t\tAlert Header Text: {}", alert.alertHeaderText);
                    LOG.info("\t\tAlert Description: {}", alert.alertDescriptionText);
                    LOG.info("\t\tAlert Start Date: {}", alert.effectiveStartDate);
                    LOG.info("\t\tAlert End Date: {}", alert.effectiveEndDate);
                    LOG.info("\t\tAlert URL: {}", alert.alertUrl);
                });

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
                LOG.info("\tIs Segment Closed: {}", geom.isClosed());
                LOG.info("\tIs Segment a RING: {}", geom.isRing());
                LOG.info("\tIs Segment a RECTANGLE: {}", geom.isRectangle());
                LOG.info("\tIs Segment SIMPLE: {}", geom.isSimple());
                LOG.info("\tIs A valid Segment: {}", geom.isValid());
                LOG.info("\tIs Segment Empty: {}", geom.isEmpty());

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
                    AbsoluteDirection dir = edge.absoluteDirection;
                    LOG.info("\t\tEdge Direction: {}", dir.name());
                    LOG.info("\t\tEdge Direction Ordinal: {}", dir.ordinal());

                    LOG.info("\t\t*****Edge END*****");
                });
                LOG.info("\t*****TRANSIT SEGMENTS START*****");
                logTransitSegment(option.transit);
                LOG.info("\t*****TRANSIT SEGMENTS END*****");
                LOG.info("\t*****ACCESS START*****");
                logStreetSegment(option.access);
                LOG.info("\t*****ACCESS END*****");
                LOG.info("\t*****EGRESS START*****");
                logStreetSegment(option.egress);
                LOG.info("\t*****EGRESS END*****");

                LOG.info("*****OPTION END*****");
            });
            LOG.info("{} PATTERNS returned in the profileResponse", profileResponse.getPatterns().size());
        });
    }

    /**
     * A helper function to convert integer to the man readable time notation.
     *
     * @param sSegments
     * @return
     */
    public void logStreetSegment(List<StreetSegment> sSegments) {
        ifPresentThenForEach(sSegments, segment -> {
            LOG.info("\t*****SEGMENT START*****");

            LOG.info("\tAccess MODE: {}", segment.mode);
            LOG.info("\tAccess Distance: {}", segment.distance);
            LOG.info("\tTotal Edge Distance: {}",
                    segment.streetEdges.parallelStream().mapToInt(edge -> edge.distance).sum());
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
    }

    /**
     * @param tSegments
     */
    public void logTransitSegment(List<TransitSegment> tSegments) {
        ifPresentThenForEach(tSegments, segment -> {
            LOG.info("\t*****SEGMENT START*****");

            LOG.info("\tTransit MODE: {}", segment.mode);
            LOG.info("\tFrom Name: {}", segment.fromName);
            LOG.info("\tTo Name: {}", segment.toName);
            LOG.info("\t*****From Stop*****");
            logStop(segment.from);
            LOG.info("\t*****To Stop*****");
            logStop(segment.to);

            LOG.info("\tTotal Routes are: {}", segment.getRoutes().size());
            ifPresentThenForEach(segment.getRoutes(), route -> {
                LOG.info("\t\t*****ROUTE START*****");
                LOG.info("\t\tRoute Long Name: {}", route.longName);
                LOG.info("\t\tRoute Short Name: {}", route.shortName);
                LOG.info("\t\tMode: {}", route.mode);
                LOG.info("\t\tAgency Name: {}", route.agencyName);
                LOG.info("\t\tRoute Id: {}", route.id);
                LOG.info("\t\tRoute Idx: {}", route.routeIdx);
                LOG.info("\t\tDesc: {}", route.description);
                LOG.info("\t\t*****Edge END*****");
            });

            LOG.info("\t*****SEGMENT END*****");
        });
    }

    /**
     * @param stop
     */
    public void logStop(Stop stop) {
        LOG.info("\tStop ZoneId: {}", stop.zoneId);
        LOG.info("\tStop Id : {}", stop.stopId);
        LOG.info("\tSStop Code: {}", stop.code);
        LOG.info("\tStop Mode: {}", stop.mode);
        LOG.info("\tStop Name: {}", stop.name);
        LOG.info("\tLat: {}", stop.lat);
        LOG.info("\tLon: {}", stop.lon);
        LOG.info("\tWheelchair Boarding: {}", stop.wheelchairBoarding);
    }

    /**
     * A helper function for formatting the time in seconds to human readable time format
     *
     * @param timeInSeconds
     * @return
     */
    private static String convertIntToTimeFormat(final int timeInSeconds) {

        int hours = timeInSeconds / 3600;
        int remainder = timeInSeconds % 3600;
        int mins = remainder / 60;
        remainder = remainder % 60;
        int secs = remainder;

        return String.format("%02d:%02d:%02d", hours, mins, secs);
    }
}
