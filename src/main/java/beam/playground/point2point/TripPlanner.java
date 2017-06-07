package beam.playground.point2point;

import com.conveyal.r5.api.ProfileResponse;
import com.conveyal.r5.api.util.PointToPointConnection;
import com.conveyal.r5.api.util.Stats;
import com.conveyal.r5.api.util.TransitJourneyID;
import com.conveyal.r5.point_to_point.builder.PointToPointQuery;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.transit.TransportNetwork;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.EnumSet;
import java.util.List;

import static beam.utils.Collections.ifPresentThenForEach;
import static com.conveyal.r5.api.util.LegMode.BICYCLE;
import static com.conveyal.r5.api.util.LegMode.WALK;
import static com.conveyal.r5.api.util.TransitModes.TRANSIT;

/**
 * Created by ahmar.nadeem on 6/6/2017.
 */
public class TripPlanner {

    private static final Logger LOG = LoggerFactory.getLogger(TripPlanner.class);

    public static void main(String[] args) throws Exception {

        //Loading graph
        String dir = "";
        if (args == null || args.length == 0) {
            dir = System.getProperty("user.home") + "/beam/r5/berkeley";
        } else {
            dir = args[0];
        }
        File file = new File(dir, "network.dat");
        TransportNetwork transportNetwork = TransportNetwork.read(file);
        //Optional used to get street names:
        transportNetwork.readOSM(new File(dir, "osm.mapdb"));
        PointToPointQuery pointToPointQuery = new PointToPointQuery(transportNetwork);

        ProfileRequest profileRequest = new ProfileRequest();
        //Set timezone to timezone of transport network
        profileRequest.zoneId = transportNetwork.getTimeZone();
        profileRequest.fromLat = 40.706873;
        profileRequest.fromLon = -73.931345;
        profileRequest.toLat = 40.702278;
        profileRequest.toLon = -73.944209;
        profileRequest.wheelchair = false;
        profileRequest.bikeTrafficStress = 4;
//        profileRequest.setTime("2015-02-05T07:30+05:00", "2015-02-05T10:30+05:00");

        profileRequest.transitModes = EnumSet.of(TRANSIT);
        profileRequest.accessModes = EnumSet.of(WALK);
        profileRequest.egressModes = EnumSet.of(WALK);
        profileRequest.directModes = EnumSet.of(WALK, BICYCLE);
        //Gets a response:
        ProfileResponse profileResponse = pointToPointQuery.getPlan(profileRequest);

        LOG.info("{} OPTIONS returned in the profileResponse", profileResponse.getOptions().size());

        ifPresentThenForEach(profileResponse.getOptions(), option ->  {
            ifPresentThenForEach(option.itinerary, iten -> {
                LOG.info("Total Distance is: {}", iten.distance);
                LOG.info("Total Duration is: {}", convertIntToTimeFormat(iten.duration));
                LOG.info("Start Time is: {}", iten.startTime);
                LOG.info("End Time is: {}", iten.endTime);
                LOG.info("Total Waiting Time is: {}", convertIntToTimeFormat(iten.waitingTime));
                LOG.info("Total Transit Time is: {}", convertIntToTimeFormat(iten.transitTime));
                LOG.info("Total Walk Time is: {}", convertIntToTimeFormat(iten.walkTime));

                PointToPointConnection conn = iten.connection;
                LOG.info("P2P Connection Access: {}", conn.access);
                LOG.info("P2P Connection Egress: {}", conn.egress);

                List<TransitJourneyID> transits = conn.transit;
                ifPresentThenForEach(conn.transit, transit -> {
                    LOG.info("Transit Time: {}", convertIntToTimeFormat(transit.time));
                    LOG.info("Transit Pattern: {}", transit.pattern);
                });
                Stats stats = option.stats;
                LOG.info("Average: {}", stats.avg);
                LOG.info("MIN: {}", stats.min);
                LOG.info("MAX: {}", stats.max);
                LOG.info("NUM: {}", stats.num);

                ifPresentThenForEach(option.access, segment -> {
                    LOG.info("*****SEGMENT DESCRIPTION*****");

                    LOG.info("Access MODE: {}", segment.mode);
                    LOG.info("Access Distance: {}", segment.distance);
                    LOG.info("Access Elevation: {}", segment.elevation);
                    LOG.info("Access Duration: {}", convertIntToTimeFormat(segment.duration));

                    LineString geom = segment.geometry;
                    LOG.info("Segment Area: {}", geom.getArea());
                    LOG.info("Coordinates: {}", geom.getCoordinate());
                    LOG.info("Boundary Dimensions are: {}", geom.getBoundaryDimension());
                    LOG.info("Segment Starting Point: {}", geom.getStartPoint());
                    LOG.info("End Point is: {}", geom.getEndPoint());
                    LOG.info("Geometry Dimensions: {}", geom.getDimension());
                    LOG.info("Geometry Type: {}", geom.getGeometryType());
                    LOG.info("Segment Length: {}", geom.getLength());
                    LOG.info("Segment Num Points: {}", geom.getNumPoints());

                    Coordinate coordinate = geom.getCoordinate();
                    LOG.info("Coordinate-X: {}", coordinate.x);
                    LOG.info("Coordinate-Y: {}", coordinate.y);
                    LOG.info("Coordinate-Z: {}", coordinate.z);

                    LOG.info("*************************");
                });
            });
        });
        LOG.info("{} PATTERNS returned in the profileResponse", profileResponse.getPatterns().size());
    }

    private static String convertIntToTimeFormat(final int timeInSeconds) {

//        long longVal = timeInSeconds.longValue();
        int hours = timeInSeconds / 3600;
        int remainder = timeInSeconds % 3600;
        int mins = remainder / 60;
        remainder = remainder % 60;
        int secs = remainder;

        return String.format("%02d:%02d:%02d", hours, mins, secs);

//        long hours = TimeUnit.SECONDS.toHours(timeInSeconds);
//        long remainMinute = timeInSeconds - TimeUnit.HOURS.toMinutes(hours);
//        long remainSeconds = timeInSeconds - TimeUnit.MINUTES.toSeconds(remainMinute);
//        String result = String.format("%02d", hours) + ":" + String.format("%02d", remainMinute)+ ":" + String.format("%02d", remainSeconds);
//        return result;
    }
}
