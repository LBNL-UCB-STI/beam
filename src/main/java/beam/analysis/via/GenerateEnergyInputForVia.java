package beam.analysis.via;

import beam.analysis.PathTraversalEventGenerationFromCsv;
import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
import beam.analysis.R5NetworkReader;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;

public class GenerateEnergyInputForVia {

    public static void main(String[] args) {

        String r5NetworkPath = "Y:\\trb2018\\sfLightR5NetworkLinksWithCounties2.csv";

        PathTraversalSpatialTemporalTableGenerator.r5NetworkLinks = R5NetworkReader.readR5Network(r5NetworkPath, true);

        PathTraversalSpatialTemporalTableGenerator.loadVehicles("C:\\Users\\NRO_M4700_SSD_02\\IdeaProjects\\beam10\\beam\\test\\input\\sf-light\\transitVehicles.xml");

        EventsManager events = EventsUtils.createEventsManager();

        PathTraversalSpatialTemporalTableGenerator energyConsumptionPerLinkOverTime = new PathTraversalSpatialTemporalTableGenerator();

        String eventCsvPath = "Y:\\trb2018\\sf-light-1k_2018-01-05_11-28-45\\ITERS\\it.0\\0.events.csv";

        PathTraversalEventGenerationFromCsv.generatePathTraversalEventsAndForwardToHandler(eventCsvPath, energyConsumptionPerLinkOverTime);

        energyConsumptionPerLinkOverTime.printDataToFile("Y:\\trb2018\\energyConsumption_sf-light-1k_2018-01-05_11-28-45.csv");
    }

}
