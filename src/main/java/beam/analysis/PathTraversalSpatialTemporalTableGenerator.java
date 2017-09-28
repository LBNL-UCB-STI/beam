package beam.analysis;

import beam.sim.config.BeamConfig;
import beam.utils.DebugLib;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.vehicles.*;

import java.io.*;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;

/**
 * @author rwaraich
 * <p>
 * Create table for spatial and temporal analysis based on pathTraversalEvents (xml)
 * outputs: linkId, vehicleType, fuelConsumption, fuelType, numberOfVehicles, numberOfPassengers, linkCoordinates, linkLengthInMeters, county
 * for subway, tram, ferry and cable_car the link is reported as a pair of start and end link
 */


public class PathTraversalSpatialTemporalTableGenerator implements BasicEventHandler {
    int maxTimeInSeconds = 3600 * 24;
    int binSizeInSeconds = 3600 * 1;
    int numberOfBins = maxTimeInSeconds / binSizeInSeconds;
    double samplePct = 1.0;
    int printToConsoleNumberOfLines = 10;

    Table<String, String, Double>[] energyConsumption = new Table[numberOfBins];

    Table<String, String, Double>[] numberOfVehicles = new Table[numberOfBins];

    Table<String, String, Double>[] numberOfPassengers = new Table[numberOfBins];

    HashMap<String, Tuple<Coord, Coord>> startAndEndCoordNonRoadModes = new HashMap();

    public static HashMap<String, R5NetworkLink> r5NetworkLinks;

    private static Vehicles veh;

    public static void main(String[] args) {
        //String pathToEventsFile = "C:\\tmp\\events2.xml.gz";
        //String pathToEventsFile = "C:\\tmp\\base_2017-09-26_18-13-28\\test\\output\\base_2017-09-26_18-13-28\\ITERS\\it.0\\0.events.xml";
        String pathToEventsFile = "C:\\tmp\\base_2017-09-27_05-05-07\\base_2017-09-27_05-05-07~\\base_2017-09-27_05-05-07\\ITERS\\it.0\\0.events.xml";
        //String pathToEventsFile = "C:\\tmp\\base_2017-09-26_18-13-2.tar\\base_2017-09-26_18-13-2\\base_2017-09-26_18-13-28\\ITERS\\it.0\\0.events.xml";
        String r5NetworkPath = "C:\\tmp\\bayAreaR5NetworkLinksWithCounties.csv\\bayAreaR5NetworkLinksWithCounties.csv";

        r5NetworkLinks = R5NetworkReader.readR5Network(r5NetworkPath, true);

        veh = VehicleUtils.createVehiclesContainer();
        new VehicleReaderV1(veh).readFile("C:\\Users\\rwaraich\\IdeaProjects\\application_sfbay_7\\beam\\production\\application-sfbay\\transitVehicles.xml");


        EventsManager events = EventsUtils.createEventsManager();

        PathTraversalSpatialTemporalTableGenerator energyConsumptionPerLinkOverTime = new PathTraversalSpatialTemporalTableGenerator();
        events.addHandler(energyConsumptionPerLinkOverTime);

        MatsimEventsReader reader = new MatsimEventsReader(events);
        reader.readFile(pathToEventsFile);

        energyConsumptionPerLinkOverTime.printDataToFile("c:\\tmp\\energyConsumption.txt");
    }

    public PathTraversalSpatialTemporalTableGenerator() {
        for (int i = 0; i < numberOfBins; i++) {
            energyConsumption[i] = HashBasedTable.create();
            numberOfVehicles[i] = HashBasedTable.create();
            numberOfPassengers[i] = HashBasedTable.create();
        }
    }

    @Override
    public void reset(int iteration) {

    }


    public String convertVehicleType(String vehicleType) {
        HashMap<String, String> oldLabelToNewLabel = new HashMap<>();

        oldLabelToNewLabel.put("bus", "Bus");
        oldLabelToNewLabel.put("subway", "BART");
        oldLabelToNewLabel.put("SUV", "Car");
        oldLabelToNewLabel.put("cable_car", "Cable_Car");
        oldLabelToNewLabel.put("tram", "Muni");
        oldLabelToNewLabel.put("rail", "Rail");
        oldLabelToNewLabel.put("ferry", "Ferry");

        if (oldLabelToNewLabel.containsKey(vehicleType)) {
            return oldLabelToNewLabel.get(vehicleType);
        } else {
            return vehicleType;
        }
    }

    private String getVehicleType(String vehicleAndFuelType) {
        return vehicleAndFuelType.split("@")[0];
    }

    private String getFuelType(String vehicleAndFuelType) {
        return vehicleAndFuelType.split("@")[1];
    }

    public void printDataToFile(String path) {
        int j = 0;
        try {
            PrintWriter pw = new PrintWriter(new File(path));
            StringBuilder sb = new StringBuilder();

            sb.append("linkId\ttimeBin\tmode\tfuelConsumption[MJ]\tfuelType\tnumberOfVehicles\tnumberOfPassengers\txCoord\tyCoord\tlengthInMeters\tcounty");
            sb.append('\n');
            for (int i = 0; i < numberOfBins; i++) {

                for (Table.Cell<String, String, Double> cell : energyConsumption[i].cellSet()) {
                    String linkId = cell.getRowKey();
                    String vehicleAndFuelType = cell.getColumnKey();
                    R5NetworkLink r5link = getR5LinkTakeCareOfTransit(linkId);

                    if (r5link == null) {
                        continue;
                    }

                    String vehicleType = getVehicleType(vehicleAndFuelType);
                    String fuelType = getFuelType(vehicleAndFuelType);

                    sb.append(linkId);
                    sb.append("\t");
                    sb.append(i);
                    sb.append("\t");
                    sb.append(convertVehicleType(vehicleType));
                    sb.append("\t");
                    sb.append(energyConsumption[i].get(linkId, vehicleAndFuelType));
                    sb.append("\t");
                    sb.append(fuelType);
                    sb.append("\t");
                    sb.append(Math.round(numberOfVehicles[i].get(linkId, vehicleAndFuelType)));
                    sb.append("\t");
                    sb.append(Math.round(numberOfPassengers[i].get(linkId, vehicleAndFuelType)));
                    sb.append("\t");
                    sb.append(r5link.coord.getX());
                    sb.append("\t");
                    sb.append(r5link.coord.getY());
                    sb.append("\t");
                    sb.append(r5link.lengthInMeters);
                    sb.append("\t");
                    sb.append(r5link.countyName);
                    sb.append('\n');
                    pw.write(sb.toString());
                    if (j < printToConsoleNumberOfLines) {
                        System.out.print(sb.toString());
                    }
                    sb.setLength(0);
                    j++;
                }
            }
            pw.close();
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }


    public double getFuelUsageBasedOnStartEndCoordinates(double fuelEconomy, Map<String, String> pathTraversalEventAttributes) {
        Tuple<Coord, Coord> startAndEndCoordinates = PathTraversalLib.getStartAndEndCoordinates(pathTraversalEventAttributes);
        double lengthInMeters = GeoUtils.distInMeters(startAndEndCoordinates.getFirst().getY(), startAndEndCoordinates.getFirst().getX(), startAndEndCoordinates.getSecond().getY(), startAndEndCoordinates.getSecond().getX());
        return fuelEconomy * lengthInMeters;
    }

    private R5NetworkLink getR5LinkTakeCareOfTransit(String linkId) {
        try {
            if (r5NetworkLinks.containsKey(linkId)) {
                return r5NetworkLinks.get(linkId);
            } else {
                // Tuple<Coord,Coord> startAndEndCoord=startAndEndCoordNonRoadModes.get(linkId); // just using for debugging here
                String[] linkSplit = linkId.split(",");
                R5NetworkLink startLink = r5NetworkLinks.get(linkSplit[0].trim());
                R5NetworkLink endLink = r5NetworkLinks.get(linkSplit[1].trim());
                Coord centerCoord = new Coord((startLink.coord.getX() + endLink.coord.getX()) / 2, (startLink.coord.getY() + endLink.coord.getY()) / 2);
                double lengthInMeters = GeoUtils.distInMeters(startLink.coord.getY(), startLink.coord.getX(), endLink.coord.getY(), endLink.coord.getX());
                return new R5NetworkLink(linkId, centerCoord, lengthInMeters, startLink.countyName);
                // taking county name from start coordinate as data not available for centerCoord to county mapping
            }
        } catch (Exception e) {
            System.out.println("'" + linkId + "' not recognized (skipping)");
            // this was introduced, as for ferry links=",730420" found, which does not allow to process it
        }
        return null;
    }


    private int getBinId(double time) {
        return (int) Math.floor(time / (binSizeInSeconds));
    }

    @Override
    public void handleEvent(Event event) {
        Random r = new Random();

        if (r.nextDouble() > samplePct) {
            return;
        }

        if (getBinId(event.getTime()) >= getBinId(maxTimeInSeconds)) {
            return; // not using data after 'maxTimeInSeconds'
        }

        if (event.getEventType().equalsIgnoreCase("PathTraversal")) {
            String vehicleType = event.getAttributes().get("vehicle_type");
            String mode = event.getAttributes().get("mode");
            String vehicleId = event.getAttributes().get("vehicle_id");
            String links = event.getAttributes().get("links");
            Integer numOfPassengers = Integer.parseInt(event.getAttributes().get("num_passengers"));
            double lengthInMeters = Double.parseDouble(event.getAttributes().get("length"));

            // initialize Fuel
            String fuelString = event.getAttributes().get("fuel");
            Double fuel = 0.0;
            if (fuelString.contains("NA")) {
                if (vehicleId.contains("rideHailing")) {
                    double carFuelEconomyInLiterPerMeter = 0.0001069; // ca. 22 mpg
                    fuel = carFuelEconomyInLiterPerMeter * lengthInMeters;
                    // fix for ride hailing vehicles
                } else if (vehicleType.contains("Human")) {
                    if (lengthInMeters>0){
                        DebugLib.emptyFunctionForSettingBreakPoint();
                    }

                    // based on weight of average noth american (177.9lb), walking speed of 4km/h -> 259.5 J/m
                    // sources:
                    // https://en.wikipedia.org/wiki/Human_body_weight
                    // https://www.brianmac.co.uk/energyexp.htm
                    double walkingEnergyInJoulePerMeter=259.5;
                    fuel = walkingEnergyInJoulePerMeter*lengthInMeters; // in Joule
                } else {
                    DebugLib.stopSystemAndReportInconsistency();
                }
            } else {
                fuel = Double.parseDouble(event.getAttributes().get("fuel"));
            }

            if (vehicleId.contains("rideHailing")) {
                vehicleType = "TNC";
            }




            boolean isElectricEnergy = isElectricEnergy(vehicleType, vehicleId);

            fuel = convertFuelToMJ(fuel,mode, isElectricEnergy);

            if (vehicleType.equalsIgnoreCase("subway") || vehicleType.equalsIgnoreCase("rail") || vehicleType.equalsIgnoreCase("ferry") || vehicleType.equalsIgnoreCase("cable_car") || vehicleType.equalsIgnoreCase("tram")) {
                if (PathTraversalLib.hasEmptyStartOrEndCoordinate(event.getAttributes())) {
                    System.out.println("not processing pathTraversal, as it has empty start or end coordinates: ");
                    System.out.println(event.toString());
                    return; // don't consider traversal events which are missing start or end coordinates
                }

                double fuelEconomy = fuel / lengthInMeters;
                double fuelUsageBasedOnStartEndCoordinates = getFuelUsageBasedOnStartEndCoordinates(fuelEconomy, event.getAttributes());

                addValueToTable(energyConsumption[getBinId(event.getTime())], links.trim(), getVehicleTypeWithFuelType(vehicleType, vehicleId, mode, isElectricEnergy), fuelUsageBasedOnStartEndCoordinates);
                addValueToTable(numberOfVehicles[getBinId(event.getTime())], links.trim(), getVehicleTypeWithFuelType(vehicleType, vehicleId, mode, isElectricEnergy), 1.0);
                addValueToTable(numberOfPassengers[getBinId(event.getTime())], links.trim(), getVehicleTypeWithFuelType(vehicleType, vehicleId, mode, isElectricEnergy), numOfPassengers);

                if (!startAndEndCoordNonRoadModes.containsKey(links.trim())) {
                    startAndEndCoordNonRoadModes.put(links.trim(), PathTraversalLib.getStartAndEndCoordinates(event.getAttributes()));
                }

            } else {
                LinkedList<String> linkIds = PathTraversalLib.getLinkIdList(links);

                for (String linkId : linkIds) {
                    addValueToTable(energyConsumption[getBinId(event.getTime())], linkId, getVehicleTypeWithFuelType(vehicleType, vehicleId, mode, isElectricEnergy), getFuelShareOfLink(linkId, linkIds, fuel));
                    addValueToTable(numberOfVehicles[getBinId(event.getTime())], linkId, getVehicleTypeWithFuelType(vehicleType, vehicleId, mode, isElectricEnergy), 1.0);
                    addValueToTable(numberOfPassengers[getBinId(event.getTime())], linkId, getVehicleTypeWithFuelType(vehicleType, vehicleId, mode, isElectricEnergy), numOfPassengers);
                }
            }
        }
    }


    private String getFuelType(String vehicleIdString, String mode){


        return null;

    }


    private String getVehicleTypeWithFuelType(String vehicleTypeString, String vehicleIdString, String mode, boolean isElectricEnergy) {
        String transitAgency = null;

        if (mode.equalsIgnoreCase("CAR")) {
            return vehicleTypeString + "@gasoline";
        }

        if (mode.equalsIgnoreCase("walk")) {
            return vehicleTypeString + "@food";
        }

        if (vehicleIdString.contains(":")) {
            // is transit agency
            transitAgency = vehicleIdString.split(":")[0].trim();
            Id<VehicleType> vehicleTypeId = Id.create((mode + "-" + transitAgency).toUpperCase(), VehicleType.class);
            if (!veh.getVehicleTypes().containsKey(vehicleTypeId)) {
                vehicleTypeId = Id.create((mode + "-DEFAULT").toUpperCase(), VehicleType.class);
            }

            VehicleType vehicleType = veh.getVehicleTypes().get(vehicleTypeId);
            String vehicleFuelType = vehicleType.getEngineInformation().getFuelType().name();

            if (vehicleFuelType.equalsIgnoreCase("biodiesel")) {
                vehicleFuelType = "naturalGas";
            }

            return vehicleTypeString + "@" + vehicleFuelType;
        }

        return vehicleTypeString;
    }

    private boolean isElectricEnergy(String vehicleType, String vehicleId) {
        return vehicleType.equalsIgnoreCase("subway") || vehicleType.equalsIgnoreCase("cable_car") || vehicleType.equalsIgnoreCase("tram") || (vehicleType.equalsIgnoreCase("bus") && vehicleId.contains("MS"));
    }

    private Double convertFuelToMJ(Double fuel, String mode, boolean isElectricEnergy) {
        if (mode.contains("walk")){
            return fuel/1000000; // converting Joule to MJ
        }


        if (isElectricEnergy) {
            return 3.6 * fuel; // converting kWh to MJ
        } else {
            return 35.8 * fuel; // converting liter diesel to MJ
            // assuming energy density of Diesel as: 35.8 MJ/L
            // https://en.wikipedia.org/wiki/Energy_density
        }
    }


    double getFuelShareOfLink(String linkIdPartOfPath, LinkedList<String> pathLinkIds, double pathFuelConsumption) {
        double pathLength = 0;

        for (String linkId : pathLinkIds) {
            pathLength += r5NetworkLinks.get(linkId.toString()).lengthInMeters;
        }

        return r5NetworkLinks.get(linkIdPartOfPath.toString()).lengthInMeters / pathLength * pathFuelConsumption;
    }


    public static void addValueToTable(Table<String, String, Double> table, String key1, String key2, double value) {
        if (!table.contains(key1, key2)) {
            table.put(key1, key2, 0.0);
        }

        table.put(key1, key2, table.get(key1, key2) + value);
    }


}
