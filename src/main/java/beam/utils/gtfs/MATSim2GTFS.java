package beam.utils.gtfs;


import com.beust.jcommander.internal.Maps;
import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.*;
import org.mapdb.Fun;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt.transitSchedule.api.*;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

/**
 * Convert a MatSim XML transit network to GTFS.
 * There are a few assumptions here that fit only with the
 * Sioux Falls 2014 PT network.
 */

public class MATSim2GTFS {

    private static final Map<String, String> BUS_LINE_NAMES = Maps.newHashMap();

    static {
        // Bus lines
        BUS_LINE_NAMES.put("Line1", "Line 1");
        BUS_LINE_NAMES.put("Line1_r", "Line 1r");
        BUS_LINE_NAMES.put("Line2", "Line 2");
        BUS_LINE_NAMES.put("Line2_r", "Line 2r");
        BUS_LINE_NAMES.put("Line3", "Line 3");
        BUS_LINE_NAMES.put("Line3_r", "Line 3r");
        BUS_LINE_NAMES.put("Line4", "Line 4");
        BUS_LINE_NAMES.put("Line4_r", "Line 4r");
        BUS_LINE_NAMES.put("Line5", "Line 5");
        BUS_LINE_NAMES.put("Line5_r", "Line 5r");

    }

    public static void main(String[] args) {

        // Transformation to convert Singapore UTM coordinates to WGS84
        CoordinateTransformation cT = TransformationFactory.getCoordinateTransformation("epsg:26914", TransformationFactory.WGS84);

        final String FEED_ID = args[0];

        // A dummy scenario into which we will load the XML transit data
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new TransitScheduleReader(scenario).readFile(args[1]);

        // A GTFS feed into which we will copy the Matsim transit data
        GTFSFeed gtfsFeed = new GTFSFeed();

        // Add a feed_info to declare the feed ID.
        FeedInfo gtfsFeedInfo = new FeedInfo();
        gtfsFeedInfo.feed_id = FEED_ID;
        gtfsFeedInfo.feed_lang = "en_us";
        gtfsFeedInfo.feed_publisher_name = "Sioux Faux Flyer";
        gtfsFeedInfo.feed_publisher_url = createUrl("http://siouxfauxflyer.com");
        gtfsFeed.feedInfo.put("info", gtfsFeedInfo);

        // Create a single agency in this new GTFS feed.
        Agency gtfsAgency = new Agency();
        gtfsAgency.agency_id = FEED_ID;
        gtfsAgency.agency_name = FEED_ID;
        gtfsAgency.agency_timezone = "America/New_York";
        gtfsAgency.agency_url = createUrl("http://www.siouxfauxflyer.com");
        gtfsFeed.agency.put(gtfsAgency.agency_id, gtfsAgency);

        // A Matsim network describes only a single day of operation.
        // Create a single GTFS service called S which is exactly the same every day, running from year 2000 to 2100.
        Calendar calendar = new Calendar();
        calendar.monday = calendar.tuesday = calendar.wednesday = calendar.thursday = calendar.friday =
                calendar.saturday = calendar.sunday = 1;
        calendar.service_id = "S";
        calendar.start_date = 20000101;
        calendar.end_date = 21000101;

        // Create a gtfs-lib Service object wrapping the Calendar.
        Service gtfsService = new Service(calendar.service_id);
        gtfsService.calendar = calendar;
        gtfsFeed.services.put(gtfsService.service_id, gtfsService);

        // Copy all stops from Matsim scenario to GTFS
        for (TransitStopFacility matsimStop : scenario.getTransitSchedule().getFacilities().values()) {
            Stop gtfsStop = new Stop();
            gtfsStop.stop_id = matsimStop.getId().toString();
            gtfsStop.stop_name = matsimStop.getName();
            Coord wgsCoord = cT.transform(matsimStop.getCoord());
            gtfsStop.stop_lat = wgsCoord.getY();
            gtfsStop.stop_lon = wgsCoord.getX();
            gtfsFeed.stops.put(gtfsStop.stop_id, gtfsStop);
        }

        // Next convert all Matsim TransitLines to GTFS Routes
        for (TransitLine matsimLine : scenario.getTransitSchedule().getTransitLines().values()) {

            Route gtfsRoute = new Route();
            gtfsRoute.feed_id = FEED_ID;
            gtfsRoute.agency_id = FEED_ID;
            gtfsRoute.route_id = matsimLine.getId().toString();
            gtfsRoute.route_short_name = matsimLine.getId().toString();

            if (BUS_LINE_NAMES.containsKey(gtfsRoute.route_id)) {
                gtfsRoute.route_type = 3; // Subway
                gtfsRoute.route_long_name = BUS_LINE_NAMES.get(gtfsRoute.route_id);
            } else {
                gtfsRoute.route_type = 3; // Bus
            }
            gtfsFeed.routes.put(gtfsRoute.route_id, gtfsRoute);

            // Within a Matsim TransitLine, convert each Matsim TransitRoute to what Conveyal calls a "trip pattern"
            // (a bunch of GTFS trips with the same stop sequence).
            // Note that this explodes MatSim's representation (which in GTFS would use frequencies.txt with
            // exactTimes = true) into a bunch of separate trips all with the same inter-stop times.
            for (TransitRoute matsimRoute : matsimLine.getRoutes().values()) {
                // A Matsim Route is like what Conveyal calls a "trip pattern".
                String matsimRouteId = matsimRoute.getId().toString();
                for (Departure matsimDeparture : matsimRoute.getDepartures().values()) {
                    Trip gtfsTrip = new Trip();
                    gtfsTrip.route_id = gtfsRoute.route_id;
                    gtfsTrip.service_id = "S"; // All trips are on the same service day.
                    // Matsim Route ID concatenated with departure ID should make a unique trip ID.
                    gtfsTrip.trip_id = matsimRouteId + "_" + matsimDeparture.getId().toString();
                    gtfsTrip.trip_headsign = matsimRoute.getStops().get(matsimRoute.getStops().size() - 1).getStopFacility().getName();
                    gtfsFeed.trips.put(gtfsTrip.trip_id, gtfsTrip);

                    // Disabled making stop times because the Matsim transit schedule contains only a single set of
                    // uncongested travel times per day. We want to use the actual arrival and departure times after
                    // many iterations of Matsim, which are processed manually.

                    // The first departure time on a Matsim trip is in seconds after midnight.
                    // The Matsim arrival and departure offsets are also in seconds.
                    double firstDepartureTime = matsimDeparture.getDepartureTime();
                    int stopWithinTrip = 0;
                    for (TransitRouteStop trs : matsimRoute.getStops()) {
                        StopTime gtfsStopTime = new StopTime();
                        gtfsStopTime.trip_id = gtfsTrip.trip_id;
                        gtfsStopTime.stop_sequence = stopWithinTrip;
                        gtfsStopTime.stop_id = trs.getStopFacility().getId().toString();
                        gtfsStopTime.arrival_time = (int) (firstDepartureTime + trs.getArrivalOffset());
                        gtfsStopTime.departure_time = (int) (firstDepartureTime + trs.getDepartureOffset());
                        gtfsFeed.stop_times.put(
                                new Fun.Tuple2(gtfsStopTime.trip_id, gtfsStopTime.stop_sequence),
                                gtfsStopTime);
                        stopWithinTrip += 1;
                    }
                }
            }
        }


//            // Now create stop_times.txt separately, from the Singapore Matsim model events output.
//            // NB: This file must have at least the departureId field quoted, because those IDs contain commas.
//            eventsManager.addHandler((VehicleDepartsAtFacilityEventHandler) event -> {
//                String stopId = event.getFacilityId().toString();
//                Double dbltime = Double.parseDouble(String.valueOf(event.getTime()));
//                Integer time = dbltime.intValue();
//                String vehicleId = event.getVehicleId().toString();
//                String[] components = vehicleId.split("_");
//                String routeId;
//                String departureId;
//                if(components.length==4){
//                    routeId = components[1]+"r";
//                    departureId = components[3];
//                }else{
//                    routeId = components[1];
//                    departureId = components[2];
//                }
//                String tripId = routeId + '#' + departureId;
//                StopTime gtfsStopTime = new StopTime();
//                gtfsStopTime.trip_id = tripId;
//                gtfsStopTime.stop_sequence = time;
//                gtfsStopTime.stop_id = stopId;
//                gtfsStopTime.arrival_time = time;
//                gtfsStopTime.departure_time = time;
//                gtfsFeed.stop_times.put(
//                        new Fun.Tuple2(gtfsStopTime.trip_id, Integer.valueOf(gtfsStopTime.stop_sequence)),
//                        gtfsStopTime);
//
//            });


        // Write out to GTFS
        gtfsFeed.toFile(args[2]);
    }

    /**
     * Circumvent Java "safety".
     */
    private static URL createUrl(String url) {
        try {
            return new URL(url);
        } catch (MalformedURLException error) {
            throw new IllegalArgumentException(error.getMessage(), error);
        }
    }
}


