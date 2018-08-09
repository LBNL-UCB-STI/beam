package beam.utils.scripts;

import beam.agentsim.events.ReserveRideHailEvent;
import beam.agentsim.infrastructure.TAZTreeMap;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

import beam.analysis.plots.GraphsStatsAgentSimEventsListener;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation;

public class CreateRideHailDemandTAZSpatialData implements BasicEventHandler {

    private  final int BIN_SIZE = 1800;
    private  Map<Integer, Map<Coord, Integer>> timeBinsCoord = new HashMap();
    private  TAZTreeMap tree;
    GeotoolsTransformation utm2wgs = new GeotoolsTransformation("EPSG:4326", "EPSG:26910");


    public static void main(String[] args) {
        CreateRideHailDemandTAZSpatialData createRideHailDemandTAZSpatialData = new CreateRideHailDemandTAZSpatialData();

        createRideHailDemandTAZSpatialData.tree = TAZTreeMap.fromCsv("/home/rajnikant/IdeaProjects/beam/test/input/sf-light/taz-centers.csv.gz");
        EventsManager events = EventsUtils.createEventsManager();
        events.addHandler(createRideHailDemandTAZSpatialData);

        MatsimEventsReader reader = new MatsimEventsReader(events);
        reader.readFile("/home/rajnikant/IdeaProjects/beam/output/sf-light/sf-light-1k__2018-08-07_00-50-11/ITERS/it.0/0.events.xml");

        createRideHailDemandTAZSpatialData.printMinDistance("/home/rajnikant/IdeaProjects/beam/test/input/minDistance.csv");

    }


    //  generating csv which included timeBin, taz coords, and rideHailDemand for min distance
    public void printMinDistance(String csvFileName) {

        try
                (BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {
            StringBuilder heading = new StringBuilder("timeBin,taz-x-coord,taz-y-coord,ridehaildemand");

            out.write(heading.toString());
            out.newLine();

            timeBinsCoord.keySet().stream().forEach(timeBin -> {
                Map<Coord, Integer> coordCount = timeBinsCoord.get(timeBin);
                coordCount.keySet().stream().forEach(coord -> {
                    Integer count = coordCount.get(coord);
                    try {
                        out.write(timeBin + "," + coord.getX() + "," + coord.getY() + "," + count);
                        out.newLine();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                });
            });

            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    //  calculating rideHailDemand
    private void updateCoordMap(int timeBin, Coord coord) {
        Map<Coord, Integer> coordCount = timeBinsCoord.get(timeBin);
        if (coordCount == null) {
            coordCount = new HashMap<>();
            coordCount.put(coord, 1);
        } else {
            Integer count = coordCount.get(coord);
            if (count == null) {
                coordCount.put(coord, 1);
            } else {
                coordCount.put(coord, ++count);
            }
        }
        timeBinsCoord.put(timeBin, coordCount);

    }

    //    handling reservedRideHail events and transforming events coords in to taz coords.
    @Override
    public void handleEvent(Event event) {
        if (event.getEventType().equals("ReserveRideHail")) {
            double time = Double.parseDouble(event.getAttributes().get(ReserveRideHailEvent.ATTRIBUTE_TIME));
            double pickLocationXCoord = Double.parseDouble(event.getAttributes().get(ReserveRideHailEvent.ATTRIBUTE_PICKUP_LOCATION_X));
            double pickLocationYCoord = Double.parseDouble(event.getAttributes().get(ReserveRideHailEvent.ATTRIBUTE_PICKUP_LOCATION_Y));

            Coord transformed = utm2wgs.transform(new Coord(pickLocationXCoord, pickLocationYCoord));

            TAZTreeMap.TAZ taz = tree.getTAZ(transformed.getX(), transformed.getY());
            int timeBin = (int) (time / BIN_SIZE);
            updateCoordMap(timeBin, taz.coord());
        }
    }
}
