package beam.analysis;

import beam.agentsim.events.PathTraversalEvent;
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
import org.matsim.vehicles.VehicleReaderV1;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import java.io.File;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;

/**
 * @author rwaraich
 * <p>
 * Create table for spatial and temporal analysis based on pathTraversalEvents (supports both xml and csv)
 * outputs: linkId, vehicleType, fuelConsumption, fuelType, numberOfVehicles, numberOfPassengers, linkCoordinates, linkLengthInMeters, county
 * for subway, tram, ferry and cable_car the link is reported as a pair of start and end link
 * <p>
 * This class also introduces intermediate links for transit, if stops are far aparat.
 */


public class PathTraversalSpatialTemporalTableGenerator implements BasicEventHandler {

    public static final String TABLE_OUTPUT_FULL_PATH = "c:\\tmp\\csv analysis\\energyConsumption.txt";


    public static final boolean READ_FROM_CSV = false;
    public static final int MAX_TIME_IN_SECONDS = 3600 * 24;
    public static final int BIN_SIZE_IN_SECONDS = 3600 * 1;
    public static final int NUMBER_OF_BINS = MAX_TIME_IN_SECONDS / BIN_SIZE_IN_SECONDS;
    public static final boolean ENABLE_INTERMEDIATE_TRANSIT_LINKS = true;
    public static final double SAMPLE_PERCENTAGE = 1.0;
    public static final int PRINT_TO_CONSOLE_NUMBER_OF_LINES = 10; // for debugging
    public static final int DISTANCE_INTERMEDIATE_NON_ROAD_MODE_LINKS_IN_METERS = 1000;


    // based on weight of average noth american (177.9lb), walking speed of 4km/h -> 259.5 J/m
    // sources:
    // https://en.wikipedia.org/wiki/Human_body_weight
    // https://www.brianmac.co.uk/energyexp.htm
    public static final double WALKING_ENERGY_IN_JOULE_PER_METER = 259.5;


    public static final double CONVERSION_FACTOR_KWH_TO_MJ = 3.6;


    // assuming energy density of Diesel as: 35.8 MJ/L and gasoline as 34.2 MJ/L
    // https://en.wikipedia.org/wiki/Energy_density
    public static final double ENERGY_DENSITY_DIESEL = 35.8;
    public static final double ENERGY_DENSITY_GASOLINE = 34.2;

    // ca. 22 mpg (taken same value as for car in vehicles file)
    public static final double CAR_FUEL_ECONOMY_IN_LITER_PER_METER = 0.0001069;

    public static final double CONST_NUM_ZERO = 0.0;
    public static final double CONST_NUM_ONE = 1.0;
    public static final int CONST_NUM_MILLION = 1000000;
    public static final String VEHICLE_TYPE_FUEL_TYPE_SEPARATOR = "@";
    public static final String TRANSIT_AGENCY_VEHICLE_ID_SEPARATOR = ":";
    public static final String LINKS_SEPARATOR = ",";
    public static final String CAR = "CAR";
    public static final String SUBWAY = "subway";
    public static final String RAIL = "rail";
    public static final String FERRY = "ferry";
    public static final String CABLE_CAR = "cable_car";
    public static final String TRAM = "tram";
    public static final String WALK = "walk";


    public static final String ELECTRICITY = "electricity";
    public static final String DIESEL = "diesel";
    public static final String GASOLINE = "gasoline";
    public static final String NATURAL_GAS = "naturalGas";
    public static final String BIODIESEL = "biodiesel";
    public static final String FOOD = "food";


    public static HashMap<String, R5NetworkLink> r5NetworkLinks;

    private static Vehicles vehicles;

    private Table<String, String, Double>[] linkVehicleTypeTuples = new Table[NUMBER_OF_BINS];

    private Table<String, String, Double>[] energyConsumption = new Table[NUMBER_OF_BINS];

    private Table<String, String, Double>[] numberOfVehicles = new Table[NUMBER_OF_BINS];

    private Table<String, String, Double>[] numberOfPassengers = new Table[NUMBER_OF_BINS];

    private HashMap<String, Tuple<Coord, Coord>> startAndEndCoordNonRoadModes = new HashMap();


    public static void main(String[] args) {
        // String pathToEventsFile = "C:\\tmp\\testing events energy\\test\\output\\base_2017-09-26_18-13-28\\ITERS\\it.0\\0.events_part.xml";

        String pathToEventsFile = "C:\\tmp\\csv analysis\\base_2017-09-27_05-05-07\\base_2017-09-27_05-05-07~\\base_2017-09-27_05-05-07\\ITERS\\it.0\\0.events.xml";


        // String pathToEventsFile = "C:\\tmp\\csv analysis\\base_2017-09-27_05-05-07\\base_2017-09-27_05-05-07~\\base_2017-09-27_05-05-07\\ITERS\\it.0\\0.events.xml";
        // String pathToEventsFile = "C:\\tmp\\base_2017-09-26_18-13-28\\test\\output\\base_2017-09-26_18-13-28\\ITERS\\it.0\\0.events.xml";
        //String pathToEventsFile = "C:\\tmp\\base_2017-09-27_05-05-07\\base_2017-09-27_05-05-07~\\base_2017-09-27_05-05-07\\ITERS\\it.0\\0.events.xml";
        //String pathToEventsFile = "C:\\tmp\\base_2017-09-26_18-13-2.tar\\base_2017-09-26_18-13-2\\base_2017-09-26_18-13-28\\ITERS\\it.0\\0.events.xml";
        //String pathToEventsFile = "C:\\tmp\\events2.xml.gz";
        String r5NetworkPath = "C:\\tmp\\bayAreaR5NetworkLinksWithCounties.csv\\bayAreaR5NetworkLinksWithCounties.csv";

        r5NetworkLinks = R5NetworkReader.readR5Network(r5NetworkPath, true);

        vehicles = VehicleUtils.createVehiclesContainer();
        new VehicleReaderV1(vehicles).readFile("C:\\Users\\rwaraich\\IdeaProjects\\application_sfbay_7\\beam\\production\\application-sfbay\\transitVehicles.xml");


        EventsManager events = EventsUtils.createEventsManager();

        PathTraversalSpatialTemporalTableGenerator energyConsumptionPerLinkOverTime = new PathTraversalSpatialTemporalTableGenerator();

        if (READ_FROM_CSV) {
            // String eventCsvPath="C:\\tmp\\testing events energy\\test\\output\\base_2017-09-26_18-13-28\\ITERS\\it.0\\0.events.csv\\0.events_partial.csv";
            String eventCsvPath = "C:\\tmp\\csv analysis\\base_2017-09-27_05-05-07\\base_2017-09-27_05-05-07~\\base_2017-09-27_05-05-07\\ITERS\\it.0\\0.events.csv\\0.events.csv";
            //String eventCsvPath="C:\\tmp\\base_2017-09-26_18-13-28\\test\\output\\base_2017-09-26_18-13-28\\ITERS\\it.0\\0.events.csv\\0.events.csv";
            //String eventCsvPath = "C:\\tmp\\csv analysis\\sfBay_ridehail_price_high_2017-09-26_12-10-54\\test\\output\\sfBay_ridehail_price_high_2017-09-26_12-10-54\\ITERS\\it.0\\0.events.csv\\0.events.csv";

            //  String eventCsvPath="C:\\tmp\\csv analysis\\sfBay_ridehail_price_high_2017-09-27_05-05-15\\sfBay_ridehail_price_high_2017-09-27_05-05-15~\\sfBay_ridehail_price_high_2017-09-27_05-05-15\\ITERS\\it.0\\0.events.csv\\0.events.csv";
            //  String eventCsvPath="C:\\tmp\\csv analysis\\sfBay_ridehail_price_low_2017-09-27_08-19-54\\sfBay_ridehail_price_low_2017-09-27_08-19-54~\\sfBay_ridehail_price_low_2017-09-27_08-19-54\\ITERS\\it.0\\0.events.csv\\0.events.csv";
            //  String eventCsvPath="C:\\tmp\\csv analysis\\sfBay_transit_price_high_2017-09-27_05-05-29\\sfBay_transit_price_high_2017-09-27_05-05-29~\\sfBay_transit_price_high_2017-09-27_05-05-29\\ITERS\\it.0\\0.events.csv\\0.events.csv";
            //  String eventCsvPath="C:\\tmp\\csv analysis\\sfBay_transit_price_low_2017-09-27_08-27-38\\sfBay_transit_price_low_2017-09-27_08-27-38~\\sfBay_transit_price_low_2017-09-27_08-27-38\\ITERS\\it.0\\0.events.csv\\0.events.csv";
            PathTraversalEventGenerationFromCsv.generatePathTraversalEventsAndForwardToHandler(eventCsvPath, energyConsumptionPerLinkOverTime);
        } else {
            events.addHandler(energyConsumptionPerLinkOverTime);

            MatsimEventsReader reader = new MatsimEventsReader(events);
            reader.readFile(pathToEventsFile);
        }


        energyConsumptionPerLinkOverTime.printDataToFile(TABLE_OUTPUT_FULL_PATH);
    }

    public PathTraversalSpatialTemporalTableGenerator() {
        for (int i = 0; i < NUMBER_OF_BINS; i++) {
            linkVehicleTypeTuples[i] = HashBasedTable.create();
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
        return vehicleAndFuelType.split(VEHICLE_TYPE_FUEL_TYPE_SEPARATOR)[0];
    }

    private String getFuelType(String vehicleAndFuelType) {
        return vehicleAndFuelType.split(VEHICLE_TYPE_FUEL_TYPE_SEPARATOR)[1];
    }

    public void printDataToFile(String path) {
        int j = 0;
        try {
            PrintWriter pw = new PrintWriter(new File(path));
            StringBuilder sb = new StringBuilder();

            sb.append("linkId\ttimeBin\tmode\tfuelConsumption[MJ]\tfuelType\tnumberOfVehicles\tnumberOfPassengers\txCoord\tyCoord\tlengthInMeters\tcounty");
            sb.append('\n');
            for (int i = 0; i < NUMBER_OF_BINS; i++) {

                for (Table.Cell<String, String, Double> cell : linkVehicleTypeTuples[i].cellSet()) {
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
                        if (j < PRINT_TO_CONSOLE_NUMBER_OF_LINES) {
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
                Tuple<Coord, Coord> startAndEndCoord = startAndEndCoordNonRoadModes.get(linkId); // just using for debugging here
                String[] linkSplit = linkId.split(LINKS_SEPARATOR);
                R5NetworkLink startLink = r5NetworkLinks.get(linkSplit[0].trim());
                R5NetworkLink endLink = r5NetworkLinks.get(linkSplit[1].trim());
                Coord centerCoord = new Coord((startLink.coord.getX() + endLink.coord.getX()) / 2, (startLink.coord.getY() + endLink.coord.getY()) / 2);
                double lengthInMeters = GeoUtils.distInMeters(startLink.coord.getY(), startLink.coord.getX(), endLink.coord.getY(), endLink.coord.getX());
// TODO: do county distribution again

                if (linkId.equalsIgnoreCase("849856,1375838")) {
                    DebugLib.emptyFunctionForSettingBreakPoint();
                }


                if (ENABLE_INTERMEDIATE_TRANSIT_LINKS && lengthInMeters > DISTANCE_INTERMEDIATE_NON_ROAD_MODE_LINKS_IN_METERS) {
                    r5Links.addAll(createIntermediateTransitLinks(new R5NetworkLink(linkId, centerCoord, lengthInMeters, startLink.countyName), startAndEndCoord, vehicleAndFuelType, currentBinIndex, lengthInMeters));
                } else {
                    r5Links.add(new R5NetworkLink(linkId, centerCoord, lengthInMeters, startLink.countyName));
                    // taking county name from start coordinate as data not available for centerCoord to county mapping
                }

            }
        } catch (Exception e) {
            System.out.println("'" + linkId + "' not recognized (skipping)");
            // this was introduced, as for ferry links=",730420" found, which does not allow to process it
        }
        return r5Links;
    }


    private LinkedList<R5NetworkLink> createIntermediateTransitLinks(R5NetworkLink r5TransitLink, Tuple<Coord, Coord> startAndEndCoord, String vehicleAndFuelType, int currentBinIndex, double lengthInMeters) {
        LinkedList<R5NetworkLink> r5TransitLinks = new LinkedList<>();

        Coord startCoord = startAndEndCoord.getFirst();
        Coord endCoord = startAndEndCoord.getSecond();

        int numberOfInterpolationLinksUsedForNonRoadModes = (int) Math.round(lengthInMeters / DISTANCE_INTERMEDIATE_NON_ROAD_MODE_LINKS_IN_METERS);
        numberOfInterpolationLinksUsedForNonRoadModes = Math.max(numberOfInterpolationLinksUsedForNonRoadModes, 2); // ensure that at least two elements are present

        double deltaX = (endCoord.getX() - startCoord.getX()) / (numberOfInterpolationLinksUsedForNonRoadModes - 1);
        double deltaY = (endCoord.getY() - startCoord.getY()) / (numberOfInterpolationLinksUsedForNonRoadModes - 1);
        double energyConsumptionPerLink = energyConsumption[currentBinIndex].get(r5TransitLink.linkId, vehicleAndFuelType) / numberOfInterpolationLinksUsedForNonRoadModes;

        for (int i = 0; i < numberOfInterpolationLinksUsedForNonRoadModes; i++) {
            Coord currentLinkCoord = new Coord(startCoord.getX() + deltaX * i, startCoord.getY() + deltaY * i);
            R5NetworkLink onTransitRouteLink = new R5NetworkLink(r5TransitLink.linkId + "_" + i, currentLinkCoord, r5TransitLink.lengthInMeters / numberOfInterpolationLinksUsedForNonRoadModes, r5TransitLink.countyName);
            r5TransitLinks.add(onTransitRouteLink);

            // update tables with new r5 link
            int j = currentBinIndex;
            addValueToTable(energyConsumption[j], onTransitRouteLink.linkId, vehicleAndFuelType, energyConsumptionPerLink);
            addValueToTable(numberOfVehicles[j], onTransitRouteLink.linkId, vehicleAndFuelType, numberOfVehicles[j].get(r5TransitLink.linkId, vehicleAndFuelType));
            addValueToTable(numberOfPassengers[j], onTransitRouteLink.linkId, vehicleAndFuelType, numberOfPassengers[j].get(r5TransitLink.linkId, vehicleAndFuelType));

            if (r5TransitLink.linkId.equalsIgnoreCase("849856,1375838")) {
                DebugLib.emptyFunctionForSettingBreakPoint();
            }


        }

        return r5TransitLinks;
    }


    private int getBinId(double time) {
        return (int) Math.floor(time / (BIN_SIZE_IN_SECONDS));
    }


    public void handleEvent(double time, Map<String, String> attributes) {
        Random r = new Random();

        if (r.nextDouble() > SAMPLE_PERCENTAGE) {
            return;
        }

        if (getBinId(time) >= getBinId(MAX_TIME_IN_SECONDS)) {
            return; // not using data after 'MAX_TIME_IN_SECONDS'
        }

        String vehicleType = attributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_TYPE);
        String mode = attributes.get(PathTraversalEvent.ATTRIBUTE_MODE);
        String vehicleId = attributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID);
        String links = attributes.get(PathTraversalEvent.ATTRIBUTE_LINK_IDS);
        Integer numOfPassengers = Integer.parseInt(attributes.get(PathTraversalEvent.ATTRIBUTE_NUM_PASS));
        double lengthInMeters = Double.parseDouble(attributes.get(PathTraversalEvent.ATTRIBUTE_LENGTH));

        // initialize Fuel
        String fuelString = attributes.get("fuel");
        Double fuel = CONST_NUM_ZERO;
        if (fuelString.contains("NA")) {
            if (vehicleId.contains("rideHailing")) {
                fuel = CAR_FUEL_ECONOMY_IN_LITER_PER_METER * lengthInMeters;
                // fix for ride hailing vehicles
            } else if (vehicleType.contains("Human")) {
                if (lengthInMeters > 0) {
                    DebugLib.emptyFunctionForSettingBreakPoint();
                }


                fuel = WALKING_ENERGY_IN_JOULE_PER_METER * lengthInMeters; // in Joule
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

        String vehicleTypeWithFuelType = getVehicleTypeWithFuelType(vehicleType, vehicleId, mode);
        if (isNonRoadMode(vehicleType)) {
            if (PathTraversalLib.hasEmptyStartOrEndCoordinate(attributes)) {
                System.out.println("not processing pathTraversal, as it has empty start or end coordinates: ");
                System.out.println(attributes.toString());
                return; // don't consider traversal events which are missing start or end coordinates
            }

            double fuelEconomy = fuel / lengthInMeters;
            double fuelUsageBasedOnStartEndCoordinates = getFuelUsageBasedOnStartEndCoordinates(fuelEconomy, attributes);

            updateOutputTables(time, numOfPassengers, vehicleTypeWithFuelType, links.trim(), fuelUsageBasedOnStartEndCoordinates);


            if (!startAndEndCoordNonRoadModes.containsKey(links.trim())) {
                startAndEndCoordNonRoadModes.put(links.trim(), PathTraversalLib.getStartAndEndCoordinates(attributes));
            }


            if (links.trim().equalsIgnoreCase("849856,1375838")) {
                DebugLib.emptyFunctionForSettingBreakPoint();
            }

        } else {
            LinkedList<String> linkIds = PathTraversalLib.getLinkIdList(links, LINKS_SEPARATOR);

            for (String linkId : linkIds) {
                double fuelEnergyConsumption = getFuelShareOfLink(linkId, linkIds, fuel);
                updateOutputTables(time, numOfPassengers, vehicleTypeWithFuelType, linkId, fuelEnergyConsumption);
            }
        }
    }

    private void updateOutputTables(double time, Integer numOfPassengers, String vehicleTypeWithFuelType, String linkId, double fuelEnergyConsumption) {
        addValueToTable(linkVehicleTypeTuples[getBinId(time)], linkId, vehicleTypeWithFuelType, 0);
        addValueToTable(energyConsumption[getBinId(time)], linkId, vehicleTypeWithFuelType, fuelEnergyConsumption);
        addValueToTable(numberOfVehicles[getBinId(time)], linkId, vehicleTypeWithFuelType, CONST_NUM_ONE);
        addValueToTable(numberOfPassengers[getBinId(time)], linkId, vehicleTypeWithFuelType, numOfPassengers);
    }

    private boolean isNonRoadMode(String vehicleType) {
        return vehicleType.equalsIgnoreCase(SUBWAY) || vehicleType.equalsIgnoreCase(RAIL) || vehicleType.equalsIgnoreCase(FERRY) || vehicleType.equalsIgnoreCase(CABLE_CAR) || vehicleType.equalsIgnoreCase(TRAM);
    }


    @Override
    public void handleEvent(Event event) {
        if (event.getEventType().equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE)) {
            handleEvent(event.getTime(), event.getAttributes());
        }
    }


    private String getFuelType(String vehicleIdString, String mode) {
        String transitAgency = null;

        if (mode.equalsIgnoreCase(CAR)) {
            return GASOLINE;
        }

        if (mode.equalsIgnoreCase(WALK)) {
            return FOOD;
        }

        if (vehicleIdString.contains(TRANSIT_AGENCY_VEHICLE_ID_SEPARATOR)) {
            // is transit agency
            transitAgency = vehicleIdString.split(TRANSIT_AGENCY_VEHICLE_ID_SEPARATOR)[0].trim();
            Id<VehicleType> vehicleTypeId = Id.create((mode + "-" + transitAgency).toUpperCase(), VehicleType.class);
            if (!vehicles.getVehicleTypes().containsKey(vehicleTypeId)) {
                vehicleTypeId = Id.create((mode + "-DEFAULT").toUpperCase(), VehicleType.class);
            }

            VehicleType vehicleType = vehicles.getVehicleTypes().get(vehicleTypeId);
            String vehicleFuelType = vehicleType.getEngineInformation().getFuelType().name();

            if (vehicleFuelType.equalsIgnoreCase(BIODIESEL)) {
                return NATURAL_GAS;
            }

            return vehicleFuelType;
        }

        return null;
    }


    private String getVehicleTypeWithFuelType(String vehicleTypeString, String vehicleIdString, String mode) {
        return vehicleTypeString + VEHICLE_TYPE_FUEL_TYPE_SEPARATOR + getFuelType(vehicleIdString, mode);
    }

    private boolean isElectricEnergy(String vehicleIdString, String mode) {
        return getFuelType(vehicleIdString, mode).equalsIgnoreCase(ELECTRICITY);
    }

    private Double convertFuelToMJ(Double fuel, String mode, boolean isElectricEnergy) {
        if (mode.contains(WALK)) {
            return fuel / CONST_NUM_MILLION; // converting Joule to MJ
        }

        if (mode.contains(CAR)) {
            return ENERGY_DENSITY_GASOLINE * fuel; // converting liter gasoline to MJ
        }

        if (isElectricEnergy) {
            return CONVERSION_FACTOR_KWH_TO_MJ * fuel; // converting kWh to MJ
        } else {
            return ENERGY_DENSITY_DIESEL * fuel; // converting liter diesel to MJ
        }
    }


    private double getFuelShareOfLink(String linkIdPartOfPath, LinkedList<String> pathLinkIds, double pathFuelConsumption) {
        double pathLength = CONST_NUM_ZERO;

        for (String linkId : pathLinkIds) {
            pathLength += r5NetworkLinks.get(linkId).lengthInMeters;
        }

        return r5NetworkLinks.get(linkIdPartOfPath).lengthInMeters / pathLength * pathFuelConsumption;
    }


    public static void addValueToTable(Table<String, String, Double> table, String key1, String key2, double value) {
        if (!table.contains(key1, key2)) {
            table.put(key1, key2, CONST_NUM_ZERO);
        }

        table.put(key1, key2, table.get(key1, key2) + value);
    }


}
