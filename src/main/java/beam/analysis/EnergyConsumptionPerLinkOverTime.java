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

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;

// TODO: readin transit vehicles?


// TODO: add file for bart, etc. -> start and end coord + all other infos, distance, energy, flow.
//  ->

public class EnergyConsumptionPerLinkOverTime implements BasicEventHandler {
    int maxTimeInSeconds = 3600 * 24;
    int binSizeInSeconds=60*15;
    int numberOfBins=maxTimeInSeconds/binSizeInSeconds;

    Table<Integer, String, Double>[] energyConsumption = new Table[96];

    Table<Integer, String, Double>[] numberOfVehicles = new Table[96];

    Table<Integer, String, Double>[] numberOfPassengers = new Table[96];

    public static void addValueToTable(Table<Integer, String, Double> table, Integer key1, String key2, double value) {
        if (!table.contains(key1, key2)) {
            table.put(key1, key2, 0.0);
        }

        table.put(key1, key2, table.get(key1, key2) + value);
    }

    public EnergyConsumptionPerLinkOverTime() {
        for (int i = 0; i < numberOfBins; i++) {
            energyConsumption[i] = HashBasedTable.create();
            numberOfVehicles[i] = HashBasedTable.create();
            numberOfPassengers[i] = HashBasedTable.create();
        }
    }

    public static void main(String[] args) {
        String pathToEventsFile = "C:\\tmp\\base_2017-09-26_18-13-28\\test\\output\\base_2017-09-26_18-13-28\\ITERS\\it.0\\0.events.xml";
        String r5NetworkPath = "C:\\tmp\\bayAreaR5NetworkLinks.txt";

        HashMap<Integer, R5NetworkLink> r5NetworkLinks=R5NetworkReader.readR5Network(r5NetworkPath);

        EventsManager events = EventsUtils.createEventsManager();

        EnergyConsumptionPerLinkOverTime energyConsumptionPerLinkOverTime = new EnergyConsumptionPerLinkOverTime();
        events.addHandler(energyConsumptionPerLinkOverTime);

        MatsimEventsReader reader = new MatsimEventsReader(events);
        reader.readFile(pathToEventsFile);

        energyConsumptionPerLinkOverTime.printLinkEnergyVehicleCountAndNumberOfPassengersDataToConsole();
    }

    @Override
    public void reset(int iteration) {

    }

    public void printLinkEnergyVehicleCountAndNumberOfPassengersDataToConsole() {
        System.out.println("linkId\ttimeBin\tmode\tfuel\tumberOfVehicles\tnumberOfPassengers");
        for (int i = 0; i < numberOfBins; i++) {
            for (Integer linkId : energyConsumption[i].rowKeySet()) {
                for (String mode : energyConsumption[i].columnKeySet()) {
                    // making sure all values are present (otherwise we would get null for return value)
                    addValueToTable(energyConsumption[i], linkId, mode, 0);
                    addValueToTable(numberOfVehicles[i], linkId, mode, 0);
                    addValueToTable(numberOfPassengers[i], linkId, mode, 0);

                    System.out.print(linkId);
                    System.out.print("\t");
                    System.out.print(i);
                    System.out.print("\t");
                    System.out.print(mode);
                    System.out.print("\t");
                    System.out.print(energyConsumption[i].get(linkId, mode));
                    System.out.print("\t");
                    System.out.print(Math.round(numberOfVehicles[i].get(linkId, mode)));
                    System.out.print("\t");
                    System.out.println(Math.round(numberOfPassengers[i].get(linkId, mode)));
                }
            }
        }
    }

    @Override
    public void handleEvent(Event event) {
        if (event.getEventType().equalsIgnoreCase("PathTraversal")) {
            String mode = event.getAttributes().get("mode");
            if (mode.equalsIgnoreCase("subway") || mode.equalsIgnoreCase("rail") || mode.equalsIgnoreCase("ferry") || mode.equalsIgnoreCase("cable_car")) {
                //TODO: process when network available...
            } else {
                if (event.getTime() < maxTimeInSeconds) {
                    String links = event.getAttributes().get("links");
                    String fuelString = event.getAttributes().get("fuel");
                    Double fuel = 0.0;
                    if (!fuelString.contains("NA")) {
                        fuel = Double.parseDouble(event.getAttributes().get("fuel"));
                    }

                    Integer numOfPassengers = Integer.parseInt(event.getAttributes().get("num_passengers"));

                    for (String link : links.split(",")) {
                        addValueToTable(energyConsumption[(int) Math.round(event.getTime() / (binSizeInSeconds))], Integer.parseInt(link.trim()), mode, fuel);
                        addValueToTable(numberOfVehicles[(int) Math.round(event.getTime() / (binSizeInSeconds))], Integer.parseInt(link.trim()), mode, 1.0);
                        addValueToTable(numberOfPassengers[(int) Math.round(event.getTime() / (binSizeInSeconds))], Integer.parseInt(link.trim()), mode, numOfPassengers);
                    }
                }
            }
        }
    }








}
