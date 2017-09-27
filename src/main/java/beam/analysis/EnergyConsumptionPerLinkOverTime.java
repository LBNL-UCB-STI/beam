package beam.analysis;

import beam.agentsim.events.PathTraversalEvent;
import beam.utils.IntegerValueHashMap;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.handler.ActivityEndEventHandler;
import org.matsim.api.core.v01.events.handler.ActivityStartEventHandler;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.events.handler.EventHandler;
import org.matsim.vehicles.VehicleReaderV1;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import java.io.*;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Random;

/**
 * @author rwaraich
 */
public class EnergyConsumptionPerLinkOverTime implements BasicEventHandler {
    int maxTimeInSeconds = 3600 * 24;
    int binSizeInSeconds = 3600 * 12;
    int numberOfBins = maxTimeInSeconds / binSizeInSeconds;
    double samplePct = 0.1;
    int printToConsoleNumberOfLines=10;

    Table<String, String, Double>[] energyConsumption = new Table[numberOfBins];

    Table<String, String, Double>[] numberOfVehicles = new Table[numberOfBins];

    Table<String, String, Double>[] numberOfPassengers = new Table[numberOfBins];

    public static HashMap<Integer, R5NetworkLink> r5NetworkLinks;
// TODO: add column fuelType (electric or diesel)
// TODO: load vehicle type
    // test on final, that correct number of hours
    /*
    kwh- >
    diesel-> burning MJ
    */

    // OPEN ISSUES: TRAINSIT LINK-PAIRS NOT WORKING YET PROPERLY, WHY?

    // TODO: write out data faster?

    // TODO: change to hourly
    public static void main(String[] args) {
        //String pathToEventsFile = "C:\\tmp\\events2.xml.gz";
        String pathToEventsFile = "C:\\tmp\\base_2017-09-26_18-13-28\\test\\output\\base_2017-09-26_18-13-28\\ITERS\\it.0\\0.events.xml";
        //String pathToEventsFile = "C:\\tmp\\base_2017-09-26_18-13-2.tar\\base_2017-09-26_18-13-2\\base_2017-09-26_18-13-28\\ITERS\\it.0\\0.events.xml";
        String r5NetworkPath = "C:\\tmp\\bayAreaR5NetworkLinksWithCounties.csv\\bayAreaR5NetworkLinksWithCounties.csv";

        r5NetworkLinks = R5NetworkReader.readR5Network(r5NetworkPath, true);

        //       Vehicles veh = VehicleUtils.createVehiclesContainer();
        //      new VehicleReaderV1(veh).readFile("C:\\Users\\rwaraich\\IdeaProjects\\application_sfbay_7\\beam\\production\\application-sfbay\\transitVehicles.xml");


        EventsManager events = EventsUtils.createEventsManager();

        EnergyConsumptionPerLinkOverTime energyConsumptionPerLinkOverTime = new EnergyConsumptionPerLinkOverTime();
        events.addHandler(energyConsumptionPerLinkOverTime);

        MatsimEventsReader reader = new MatsimEventsReader(events);
        reader.readFile(pathToEventsFile);

        //energyConsumptionPerLinkOverTime.printLinkEnergyVehicleCountAndNumberOfPassengersDataToConsole();
        energyConsumptionPerLinkOverTime.printDataToFile("c:\\tmp\\energyConsumption.txt");
    }

    public EnergyConsumptionPerLinkOverTime() {
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
        oldLabelToNewLabel.put("CAR", "TNC");
        oldLabelToNewLabel.put("subway", "BART");
        oldLabelToNewLabel.put("SUV", "Car");
        oldLabelToNewLabel.put("cable_car", "Cable_Car");
        oldLabelToNewLabel.put("tram", "Muni");
        oldLabelToNewLabel.put("rail", "Caltrain");
        oldLabelToNewLabel.put("ferry", "Ferry");

        if (oldLabelToNewLabel.containsKey(vehicleType)) {
            return oldLabelToNewLabel.get(vehicleType);
        } else {
            return vehicleType;
        }
    }

    private String getVehicleType(String vehicleAndFuelType){
        return vehicleAndFuelType.split("@")[0];
    }

    private String getFuelType(String vehicleAndFuelType){
        return vehicleAndFuelType.split("@")[1];
    }

    public void printDataToFile(String path) {
        int j=0;
        try {
            PrintWriter pw = new PrintWriter(new File(path));
            StringBuilder sb = new StringBuilder();

            sb.append("linkId\ttimeBin\tmode\tfuelConsumption[MJ]\tfuelType\tnumberOfVehicles\tnumberOfPassengers");
            sb.append('\n');
            for (int i = 0; i < numberOfBins; i++) {

                for (Table.Cell<String, String, Double> cell : energyConsumption[i].cellSet()) {
                    String linkId = cell.getRowKey();
                    String vehicleAndFuelType = cell.getColumnKey();
                    /*
                        if (!energyConsumption[i].contains(linkId, vehicleAndFuelType)){
                            continue;
                        } else {
                            // making sure all values are present initialized (otherwise we would get null for return value)
                            addValueToTable(energyConsumption[i], linkId, vehicleAndFuelType, 0);
                            addValueToTable(numberOfVehicles[i], linkId, vehicleAndFuelType, 0);
                            addValueToTable(numberOfPassengers[i], linkId, vehicleAndFuelType, 0);
                        }
*/

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

/*
    public void printLinkEnergyVehicleCountAndNumberOfPassengersDataToConsole() {
        System.out.println("linkId\ttimeBin\tmode\tfuelConsumption[MJ]\tnumberOfVehicles\tnumberOfPassengers");
        for (int i = 0; i < numberOfBins; i++) {
            for (String linkId : energyConsumption[i].rowKeySet()) {
                for (String vehicleType : energyConsumption[i].columnKeySet()) {
                    // making sure all values are present (otherwise we would get null for return value)
                    addValueToTable(energyConsumption[i], linkId, vehicleType, 0);
                    addValueToTable(numberOfVehicles[i], linkId, vehicleType, 0);
                    addValueToTable(numberOfPassengers[i], linkId, vehicleType, 0);

                    System.out.print(linkId);
                    System.out.print("\t");
                    System.out.print(i);
                    System.out.print("\t");
                    System.out.print(convertVehicleType(vehicleType));
                    System.out.print("\t");
                    System.out.print(energyConsumption[i].get(linkId, vehicleType));
                    System.out.print("\t");
                    System.out.print(Math.round(numberOfVehicles[i].get(linkId, vehicleType)));
                    System.out.print("\t");
                    System.out.println(Math.round(numberOfPassengers[i].get(linkId, vehicleType)));
                }
            }
        }
    }
*/

    private int getBinId(double time){
        return (int)Math.round(time / (binSizeInSeconds));
    }

    @Override
    public void handleEvent(Event event) {
        Random r = new Random();

/*
        if (r.nextDouble()>samplePct){
            return;
        }
*/

        if (getBinId(event.getTime()) >= numberOfBins) {
            return;
        }

        if (event.getEventType().equalsIgnoreCase("PathTraversal")) {
            String vehicleType = event.getAttributes().get("vehicle_type");
            String vehicleId = event.getAttributes().get("vehicle_id");
            String links = event.getAttributes().get("links");
            Integer numOfPassengers = Integer.parseInt(event.getAttributes().get("num_passengers"));

            // initialize Fuel
            String fuelString = event.getAttributes().get("fuel");
            Double fuel = 0.0;
            if (!fuelString.contains("NA")) {
                fuel = Double.parseDouble(event.getAttributes().get("fuel"));
            }
            boolean isElectricEnergy=isElectricEnergy(vehicleType,vehicleId);

            fuel = convertFuelToMJ(fuel, isElectricEnergy);

            if (vehicleType.equalsIgnoreCase("subway") || vehicleType.equalsIgnoreCase("rail") || vehicleType.equalsIgnoreCase("ferry") || vehicleType.equalsIgnoreCase("cable_car")) {
                addValueToTable(energyConsumption[getBinId(event.getTime())], links.trim(), getVehicleTypeWithFuelType(vehicleType, isElectricEnergy), fuel);
                addValueToTable(numberOfVehicles[getBinId(event.getTime())], links.trim(), getVehicleTypeWithFuelType(vehicleType, isElectricEnergy), 1.0);
                addValueToTable(numberOfPassengers[getBinId(event.getTime())], links.trim(), getVehicleTypeWithFuelType(vehicleType, isElectricEnergy), numOfPassengers);
            } else {
                LinkedList<Integer> linkIds = getIntegerLinks(links);

                for (Integer linkId : linkIds) {
                    addValueToTable(energyConsumption[getBinId(event.getTime())], linkId.toString(), getVehicleTypeWithFuelType(vehicleType, isElectricEnergy), getFuelShareOfLink(linkId, linkIds, fuel));
                    addValueToTable(numberOfVehicles[getBinId(event.getTime())], linkId.toString(), getVehicleTypeWithFuelType(vehicleType, isElectricEnergy), 1.0);
                    addValueToTable(numberOfPassengers[getBinId(event.getTime())], linkId.toString(), getVehicleTypeWithFuelType(vehicleType, isElectricEnergy), numOfPassengers);
                }
            }
        }
    }

    private String getVehicleTypeWithFuelType(String vehicleType, boolean isElectricEnergy) {
        return vehicleType + (isElectricEnergy?"@e":"@d");
    }

    private boolean isElectricEnergy(String vehicleType, String vehicleId){
        return vehicleType.equalsIgnoreCase("subway") || vehicleType.equalsIgnoreCase("cable_car") || vehicleType.equalsIgnoreCase("tram") || (vehicleType.equalsIgnoreCase("bus") && vehicleId.contains("MS"));
    }

    private Double convertFuelToMJ(Double fuel, boolean isElectricEnergy) {
        if (isElectricEnergy) {
            return 3.6 * fuel; // converting kWh to MJ
        } else {
            return 35.8 * fuel; // converting liter diesel to MJ
            // assuming energy density of Diesel as: 35.8 MJ/L
            // https://en.wikipedia.org/wiki/Energy_density
        }
    }

    LinkedList<Integer> getIntegerLinks(String links) {
        LinkedList<Integer> linkIds = new LinkedList<Integer>();
        if (links.trim().length() != 0) {
            for (String link : links.split(",")) {
                Integer linkId = Integer.parseInt(link.trim());
                linkIds.add(linkId);
            }
        }

        return linkIds;
    }

    double getFuelShareOfLink(Integer linkIdPartOfPath, LinkedList<Integer> pathLinkIds, double pathFuelConsumption) {
        double pathLength = 0;

        for (Integer linkId : pathLinkIds) {
            pathLength += r5NetworkLinks.get(linkId).lengthInMeters;
        }

        return r5NetworkLinks.get(linkIdPartOfPath).lengthInMeters / pathLength * pathFuelConsumption;
    }


    public static void addValueToTable(Table<String, String, Double> table, String key1, String key2, double value) {
        if (!table.contains(key1, key2)) {
            table.put(key1, key2, 0.0);
        }

        table.put(key1, key2, table.get(key1, key2) + value);
    }


}
