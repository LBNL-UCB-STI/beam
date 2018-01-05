package beam.analysis.via;

import beam.analysis.PathTraversalEventGenerationFromCsv;
import beam.analysis.PathTraversalSpatialTemporalTableGenerator;
import beam.analysis.R5NetworkReader;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;

public class GenerateEnergyInputForVia {

    public static void main(String[] args) {

        String r5NetworkPath = "D:\\data\\trb2018\\bayAreaR5NetworkLinksWithCounties2.csv";

        PathTraversalSpatialTemporalTableGenerator.r5NetworkLinks = R5NetworkReader.readR5Network(r5NetworkPath, true);

        PathTraversalSpatialTemporalTableGenerator.loadVehicles("D:\\data\\trb2018\\transitVehicles.xml");

        EventsManager events = EventsUtils.createEventsManager();

        PathTraversalSpatialTemporalTableGenerator energyConsumptionPerLinkOverTime = new PathTraversalSpatialTemporalTableGenerator();

        String eventCsvPath = "D:\\data\\trb2018\\base_2018-01-04_19-30-34\\ITERS\\it.0\\0.events.csv";

        PathTraversalEventGenerationFromCsv.generatePathTraversalEventsAndForwardToHandler(eventCsvPath, energyConsumptionPerLinkOverTime);

        energyConsumptionPerLinkOverTime.printDataToFile("D:\\data\\trb2018\\energyConsumption_base_2018-01-04_19-30-34_2.txt");
    }

}
