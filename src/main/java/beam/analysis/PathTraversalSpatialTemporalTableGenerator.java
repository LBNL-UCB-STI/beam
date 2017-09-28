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
import java.util.*;

/**
 * @author rwaraich
 * <p>
 * Create table for spatial and temporal analysis based on pathTraversalEvents (xml)
 * outputs: linkId, vehicleType, fuelConsumption, fuelType, numberOfVehicles, numberOfPassengers, linkCoordinates, linkLengthInMeters, county
 * for subway, tram, ferry and cable_car the link is reported as a pair of start and end link
 */


public class PathTraversalSpatialTemporalTableGenerator implements BasicEventHandler {
    static boolean readFromCSV = true;
    int maxTimeInSeconds = 3600 * 24;
    int binSizeInSeconds = 3600 * 1;
    int numberOfBins = maxTimeInSeconds / binSizeInSeconds;
    double samplePct = 1.0;
    int printToConsoleNumberOfLines = 10;
    int numberOfInterpolationLinksUsedForNonRoadModes = 20;

    public static final String ELECTRICITY = "electricity";
    public static final String DIESEL = "diesel";
    public static final String GASOLINE = "gasoline";
    public static final String NATURAL_GAS = "naturalGas";
    public static final String BIODIESEL = "biodiesel";
    public static final String FOOD = "food";


    Table<String, String, Double>[] energyConsumption = new Table[numberOfBins];

    Table<String, String, Double>[] numberOfVehicles = new Table[numberOfBins];

    Table<String, String, Double>[] numberOfPassengers = new Table[numberOfBins];

    HashMap<String, Tuple<Coord, Coord>> startAndEndCoordNonRoadModes = new HashMap();

    public static HashMap<String, R5NetworkLink> r5NetworkLinks;

    private static Vehicles veh;

    public static void main(String[] args) {

        //String pathToEventsFile = "C:\\tmp\\base_2017-09-26_18-13-28\\test\\output\\base_2017-09-26_18-13-28\\ITERS\\it.0\\0.events_part.xml";
        // String pathToEventsFile = "C:\\tmp\\base_2017-09-26_18-13-28\\test\\output\\base_2017-09-26_18-13-28\\ITERS\\it.0\\0.events.xml";
        String pathToEventsFile = "C:\\tmp\\base_2017-09-27_05-05-07\\base_2017-09-27_05-05-07~\\base_2017-09-27_05-05-07\\ITERS\\it.0\\0.events.xml";
        //String pathToEventsFile = "C:\\tmp\\base_2017-09-26_18-13-2.tar\\base_2017-09-26_18-13-2\\base_2017-09-26_18-13-28\\ITERS\\it.0\\0.events.xml";
        //String pathToEventsFile = "C:\\tmp\\events2.xml.gz";
        String r5NetworkPath = "C:\\tmp\\bayAreaR5NetworkLinksWithCounties.csv\\bayAreaR5NetworkLinksWithCounties.csv";

        r5NetworkLinks = R5NetworkReader.readR5Network(r5NetworkPath, true);

        veh = VehicleUtils.createVehiclesContainer();
        new VehicleReaderV1(veh).readFile("C:\\Users\\rwaraich\\IdeaProjects\\application_sfbay_7\\beam\\production\\application-sfbay\\transitVehicles.xml");


        EventsManager events = EventsUtils.createEventsManager();

        PathTraversalSpatialTemporalTableGenerator energyConsumptionPerLinkOverTime = new PathTraversalSpatialTemporalTableGenerator();

        if (readFromCSV) {
            //String eventCsvPath="C:\\tmp\\base_2017-09-26_18-13-28\\test\\output\\base_2017-09-26_18-13-28\\ITERS\\it.0\\0.events.csv\\0.events.csv";
            //String eventCsvPath = "C:\\tmp\\csv analysis\\sfBay_ridehail_price_high_2017-09-26_12-10-54\\test\\output\\sfBay_ridehail_price_high_2017-09-26_12-10-54\\ITERS\\it.0\\0.events.csv\\0.events.csv";

         //  String eventCsvPath="C:\\tmp\\csv analysis\\sfBay_ridehail_price_high_2017-09-27_05-05-15\\sfBay_ridehail_price_high_2017-09-27_05-05-15~\\sfBay_ridehail_price_high_2017-09-27_05-05-15\\ITERS\\it.0\\0.events.csv\\0.events.csv";
            String eventCsvPath="C:\\tmp\\csv analysis\\sfBay_ridehail_price_low_2017-09-27_08-19-54\\sfBay_ridehail_price_low_2017-09-27_08-19-54~\\sfBay_ridehail_price_low_2017-09-27_08-19-54\\ITERS\\it.0\\0.events.csv\\0.events.csv";
          //  String eventCsvPath="C:\\tmp\\csv analysis\\sfBay_transit_price_high_2017-09-27_05-05-29\\sfBay_transit_price_high_2017-09-27_05-05-29~\\sfBay_transit_price_high_2017-09-27_05-05-29\\ITERS\\it.0\\0.events.csv\\0.events.csv";
          //  String eventCsvPath="C:\\tmp\\csv analysis\\sfBay_transit_price_low_2017-09-27_08-27-38\\sfBay_transit_price_low_2017-09-27_08-27-38~\\sfBay_transit_price_low_2017-09-27_08-27-38\\ITERS\\it.0\\0.events.csv\\0.events.csv";
            PathTraversalEventGenerationFromCsv.generatePathTraversalEventsAndForwardToHandler(eventCsvPath, energyConsumptionPerLinkOverTime);
        } else {
            events.addHandler(energyConsumptionPerLinkOverTime);

            MatsimEventsReader reader = new MatsimEventsReader(events);
            reader.readFile(pathToEventsFile);
        }


        energyConsumptionPerLinkOverTime.printDataToFile("c:\\tmp\\pathTraversalSpatialTemporalAnalysisTable_ridehail_price_low_2017-09-27_08-19-54_hourly.txt");
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


                //Set<String> links = energyConsumption[i].rowKeySet();
                //Set<String> vehicleAndFuelTypes = energyConsumption[i].columnKeySet();

                for (Table.Cell<String, String, Double> cell : energyConsumption[i].cellSet()) {
                    //for (String linkId : links) {
                    //    for (String vehicleAndFuelType : vehicleAndFuelTypes) {

                    String linkId = cell.getRowKey();
                    String vehicleAndFuelType = cell.getColumnKey();
                    LinkedList<R5NetworkLink> r5Links = getR5LinkTakeCareOfTransit(linkId, vehicleAndFuelType, i);

                    for (R5NetworkLink r5Link : r5Links) {
                        String vehicleType = getVehicleType(vehicleAndFuelType);
                        String fuelType = getFuelType(vehicleAndFuelType);

                        sb.append(r5Link.linkId);
                        sb.append("\t");
                        sb.append(i);
                        sb.append("\t");
                        sb.append(convertVehicleType(vehicleType));
                        sb.append("\t");
                        sb.append(energyConsumption[i].get(r5Link.linkId, vehicleAndFuelType));
                        sb.append("\t");
                        sb.append(fuelType);
                        sb.append("\t");
                        sb.append(Math.round(numberOfVehicles[i].get(r5Link.linkId, vehicleAndFuelType)));
                        sb.append("\t");
                        sb.append(Math.round(numberOfPassengers[i].get(r5Link.linkId, vehicleAndFuelType)));
                        sb.append("\t");
                        sb.append(r5Link.coord.getX());
                        sb.append("\t");
                        sb.append(r5Link.coord.getY());
                        sb.append("\t");
                        sb.append(r5Link.lengthInMeters);
                        sb.append("\t");
                        sb.append(r5Link.countyName);
                        sb.append('\n');
                        pw.write(sb.toString());
                        if (j < printToConsoleNumberOfLines) {
                            System.out.print(sb.toString());
                        }
                        sb.setLength(0);
                        j++;
                    }

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

    private LinkedList<R5NetworkLink> getR5LinkTakeCareOfTransit(String linkId, String vehicleAndFuelType, int currentBinIndex) {
        LinkedList<R5NetworkLink> r5Links = new LinkedList<>();
        try {
            if (r5NetworkLinks.containsKey(linkId)) {
                r5Links.add(r5NetworkLinks.get(linkId));
            } else {
                // Tuple<Coord,Coord> startAndEndCoord=startAndEndCoordNonRoadModes.get(linkId); // just using for debugging here
                String[] linkSplit = linkId.split(",");
                R5NetworkLink startLink = r5NetworkLinks.get(linkSplit[0].trim());
                R5NetworkLink endLink = r5NetworkLinks.get(linkSplit[1].trim());
                Coord centerCoord = new Coord((startLink.coord.getX() + endLink.coord.getX()) / 2, (startLink.coord.getY() + endLink.coord.getY()) / 2);
                double lengthInMeters = GeoUtils.distInMeters(startLink.coord.getY(), startLink.coord.getX(), endLink.coord.getY(), endLink.coord.getX());

                r5Links.add(new R5NetworkLink(linkId, centerCoord, lengthInMeters, startLink.countyName));
                // TODO: continue here later
                //r5Links.addAll(createIntermediateTransitLinks(new R5NetworkLink(linkId, centerCoord, lengthInMeters, startLink.countyName), startLink, endLink, vehicleAndFuelType, currentBinIndex));
                // taking county name from start coordinate as data not available for centerCoord to county mapping
            }
        } catch (Exception e) {
            System.out.println("'" + linkId + "' not recognized (skipping)");
            // this was introduced, as for ferry links=",730420" found, which does not allow to process it
        }
        return r5Links;
    }


    private LinkedList<R5NetworkLink> createIntermediateTransitLinks(R5NetworkLink r5TransitLink, R5NetworkLink startLink, R5NetworkLink endLink, String vehicleAndFuelType, int currentBinIndex) {
        LinkedList<R5NetworkLink> r5TransitLinks = new LinkedList<>();

        double deltaX = endLink.coord.getX() - startLink.coord.getX() / (numberOfInterpolationLinksUsedForNonRoadModes - 1);
        double deltaY = endLink.coord.getY() - startLink.coord.getY() / (numberOfInterpolationLinksUsedForNonRoadModes - 1);
        double energyConsumptionOnLink = energyConsumption[currentBinIndex].get(r5TransitLink.linkId, vehicleAndFuelType) / numberOfInterpolationLinksUsedForNonRoadModes;

        for (int i = 0; i < numberOfInterpolationLinksUsedForNonRoadModes; i++) {
            Coord currentLinkCoord = new Coord(startLink.coord.getX() + deltaX * i, startLink.coord.getY() + deltaY * i);

            R5NetworkLink onTransitRouteLink = new R5NetworkLink(r5TransitLink + "_" + i, currentLinkCoord, r5TransitLink.lengthInMeters, r5TransitLink.countyName);
            r5TransitLinks.add(onTransitRouteLink);

            // update tables with new r5 link
            int j = currentBinIndex;
            addValueToTable(energyConsumption[j], onTransitRouteLink.linkId, vehicleAndFuelType, energyConsumptionOnLink);

            addValueToTable(numberOfVehicles[j], onTransitRouteLink.linkId, vehicleAndFuelType, numberOfVehicles[j].get(r5TransitLink.linkId, vehicleAndFuelType));

            addValueToTable(numberOfPassengers[j], onTransitRouteLink.linkId, vehicleAndFuelType, numberOfPassengers[j].get(r5TransitLink.linkId, vehicleAndFuelType));

        }

        // *** not removing the original data from table to avoid problems with iterator within which this code is called
        // remove original transitLink from table
        //int j=currentBinIndex;
        //energyConsumption[j].remove(r5TransitLink.linkId,vehicleAndFuelType);
        //numberOfVehicles[j].remove(r5TransitLink.linkId,vehicleAndFuelType);
        //numberOfPassengers[j].remove(r5TransitLink.linkId,vehicleAndFuelType);

        return r5TransitLinks;
    }


    private int getBinId(double time) {
        return (int) Math.floor(time / (binSizeInSeconds));
    }


    public void handleEvent(double time, Map<String, String> attributes) {
        Random r = new Random();

        if (r.nextDouble() > samplePct) {
            return;
        }

        if (getBinId(time) >= getBinId(maxTimeInSeconds)) {
            return; // not using data after 'maxTimeInSeconds'
        }

        String vehicleType = attributes.get("vehicle_type");
        String mode = attributes.get("mode");
        String vehicleId = attributes.get("vehicle_id");
        String links = attributes.get("links");
        Integer numOfPassengers = Integer.parseInt(attributes.get("num_passengers"));
        double lengthInMeters = Double.parseDouble(attributes.get("length"));

        // initialize Fuel
        String fuelString = attributes.get("fuel");
        Double fuel = 0.0;
        if (fuelString.contains("NA")) {
            if (vehicleId.contains("rideHailing")) {
                double carFuelEconomyInLiterPerMeter = 0.0001069; // ca. 22 mpg (taken same value as for car in vehicles file)
                fuel = carFuelEconomyInLiterPerMeter * lengthInMeters;
                // fix for ride hailing vehicles
            } else if (vehicleType.contains("Human")) {
                if (lengthInMeters > 0) {
                    DebugLib.emptyFunctionForSettingBreakPoint();
                }

                // based on weight of average noth american (177.9lb), walking speed of 4km/h -> 259.5 J/m
                // sources:
                // https://en.wikipedia.org/wiki/Human_body_weight
                // https://www.brianmac.co.uk/energyexp.htm
                double walkingEnergyInJoulePerMeter = 259.5;
                fuel = walkingEnergyInJoulePerMeter * lengthInMeters; // in Joule
            } else {
                DebugLib.stopSystemAndReportInconsistency();
            }
        } else {
            fuel = Double.parseDouble(attributes.get("fuel"));
        }

        if (vehicleId.contains("rideHailing")) {
            vehicleType = "TNC";
        }


        boolean isElectricEnergy = isElectricEnergy(vehicleId, mode);

        fuel = convertFuelToMJ(fuel, mode, isElectricEnergy);

        if (vehicleType.equalsIgnoreCase("subway") || vehicleType.equalsIgnoreCase("rail") || vehicleType.equalsIgnoreCase("ferry") || vehicleType.equalsIgnoreCase("cable_car") || vehicleType.equalsIgnoreCase("tram")) {
            if (PathTraversalLib.hasEmptyStartOrEndCoordinate(attributes)) {
                System.out.println("not processing pathTraversal, as it has empty start or end coordinates: ");
                System.out.println(attributes.toString());
                return; // don't consider traversal events which are missing start or end coordinates
            }

            double fuelEconomy = fuel / lengthInMeters;
            double fuelUsageBasedOnStartEndCoordinates = getFuelUsageBasedOnStartEndCoordinates(fuelEconomy, attributes);

            addValueToTable(energyConsumption[getBinId(time)], links.trim(), getVehicleTypeWithFuelType(vehicleType, vehicleId, mode), fuelUsageBasedOnStartEndCoordinates);
            addValueToTable(numberOfVehicles[getBinId(time)], links.trim(), getVehicleTypeWithFuelType(vehicleType, vehicleId, mode), 1.0);
            addValueToTable(numberOfPassengers[getBinId(time)], links.trim(), getVehicleTypeWithFuelType(vehicleType, vehicleId, mode), numOfPassengers);

            if (!startAndEndCoordNonRoadModes.containsKey(links.trim())) {
                startAndEndCoordNonRoadModes.put(links.trim(), PathTraversalLib.getStartAndEndCoordinates(attributes));
            }

        } else {
            LinkedList<String> linkIds = PathTraversalLib.getLinkIdList(links, readFromCSV ? ";" : ",");

            for (String linkId : linkIds) {
                addValueToTable(energyConsumption[getBinId(time)], linkId, getVehicleTypeWithFuelType(vehicleType, vehicleId, mode), getFuelShareOfLink(linkId, linkIds, fuel));
                addValueToTable(numberOfVehicles[getBinId(time)], linkId, getVehicleTypeWithFuelType(vehicleType, vehicleId, mode), 1.0);
                addValueToTable(numberOfPassengers[getBinId(time)], linkId, getVehicleTypeWithFuelType(vehicleType, vehicleId, mode), numOfPassengers);
            }
        }
    }


    @Override
    public void handleEvent(Event event) {
        if (event.getEventType().equalsIgnoreCase("PathTraversal")) {
            handleEvent(event.getTime(), event.getAttributes());
        }
    }


    private String getFuelType(String vehicleIdString, String mode) {
        String transitAgency = null;

        if (mode.equalsIgnoreCase("CAR")) {
            return GASOLINE;
        }

        if (mode.equalsIgnoreCase("walk")) {
            return FOOD;
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

            if (vehicleFuelType.equalsIgnoreCase(BIODIESEL)) {
                return NATURAL_GAS;
            }

            return vehicleFuelType;
        }

        return null;
    }


    private String getVehicleTypeWithFuelType(String vehicleTypeString, String vehicleIdString, String mode) {
        return vehicleTypeString + "@" + getFuelType(vehicleIdString, mode);
    }

    private boolean isElectricEnergy(String vehicleIdString, String mode) {
        return getFuelType(vehicleIdString, mode).equalsIgnoreCase(ELECTRICITY);
    }

    private Double convertFuelToMJ(Double fuel, String mode, boolean isElectricEnergy) {
        if (mode.contains("walk")) {
            return fuel / 1000000; // converting Joule to MJ
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
