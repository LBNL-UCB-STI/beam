package beam.utils.gtfs.merging;

import beam.utils.gtfs.OperatorDataUtility;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.vehicles.VehicleReaderV1;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.VehicleWriterV1;
import org.matsim.vehicles.Vehicles;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static beam.utils.gtfs.SFBayPT2MATSim.BEAM_CONFIG;


/**
 * Utility to merge networks mapped from multiple GTFS providers into one master network.
 * <p>
 * Created by sfeygin on 11/12/16.
 */
public class MultiGTFSMerger {

    private final Map<String, String> agency2AgencyId;

    private final Map<String, Network> agencyId2Network;
    private final Map<String, TransitSchedule> agencyId2TransitSchedule;
    private final Map<String, Vehicles> agencyId2Vehicles;
    private final String netOutName;
    private final String scheduleOutName;
    private final String vehiclesOutName;

    private Network mergedNetwork;
    private TransitSchedule mergedSchedule;
    private Vehicles mergedVehicles;

    private String outputDir = BEAM_CONFIG.beam().outputs().baseOutputDirectory();
    private String initNetId;

    public MultiGTFSMerger(String netOutName, String scheduleOutName, String vehiclesOutName) {

        agencyId2Network = new HashMap<>();
        agencyId2TransitSchedule = new HashMap<>();
        agencyId2Vehicles = new HashMap<>();

        OperatorDataUtility operatorDataUtility = new OperatorDataUtility(BEAM_CONFIG);
        agency2AgencyId = filterValidAgencies(operatorDataUtility.getOperatorMap());

        final String fileType = ".xml.gz";
        this.netOutName = String.format("%s/%s%s", outputDir, netOutName, fileType);
        this.scheduleOutName = String.format("%s/%s%s", outputDir, scheduleOutName, fileType);
        this.vehiclesOutName = String.format("%s/%s%s", outputDir, vehiclesOutName, fileType);

    }

    public static void main(String[] args) {
        final MultiGTFSMerger merger = new MultiGTFSMerger("sf_bay_network_all_pt", "sf_bay_schedule_all", "sf_bay_vehicles_all");
        merger.run();
//        merger.readCountsFromEvents();
//        try {
//            TransitScheduleValidator.main(new String[]{"/Users/sfeygin/current_code/java/research/ucb_smartcities_all/input/sf_bay/schedule/sf_bay_schedule_all.xml",
//                    "/Users/sfeygin/current_code/java/research/ucb_smartcities_all/input/sf_bay/network/combi/sf_bay_network_all_pt.xml"});
//
//        } catch (IOException | SAXException | ParserConfigurationException e) {
//            e.printStackTrace();
//        }
//        System.out.println("Done!");
    }

    public void run() {
        agency2AgencyId.forEach(this::loadSingleAgencyData);
        agency2AgencyId.values().stream().filter(s -> !s.equals(initNetId)).forEach(this::integrateAgencyData);
        writeMergedDataToFile();
    }

    private void loadSingleAgencyData(String agency, String agencyId) {
        final String netPath = String.format("%s%s/%s_MultiModalNetwork.xml.gz", outputDir, agency, agencyId);
        final String tsPath = String.format("%s%s/%s_MappedTransitSchedule.xml", outputDir, agency, agencyId);
        final String vehPath = String.format("%s%s/Vehicles.xml", outputDir, agency);

        MutableScenario s = (MutableScenario) ScenarioUtils.createScenario(ConfigUtils.createConfig());
        s.getConfig().transit().setUseTransit(true);

        TransitSchedule ts = s.getTransitSchedule();
        Network net = s.getNetwork();
        final Vehicles veh = VehicleUtils.createVehiclesContainer();

        new MatsimNetworkReader(s.getNetwork()).readFile(netPath);
        new TransitScheduleReader(s).readFile(tsPath);
        new VehicleReaderV1(veh).readFile(vehPath);

        if (mergedNetwork == null) {
            mergedNetwork = net;
            initNetId = agencyId;
        } else {
            agencyId2Network.put(agencyId, net);
        }

        if (mergedSchedule == null) {
            mergedSchedule = ts;
        } else {
            agencyId2TransitSchedule.put(agencyId, ts);
        }

        if (mergedVehicles == null) {
            mergedVehicles = veh;
        } else {
            agencyId2Vehicles.put(agencyId, veh);
        }

        System.out.println(String.format("Loaded %s data.", agency));

    }

    private void integrateAgencyData(String agencyId) {
        System.out.println(String.format("Merging data for agency with id %s", agencyId));
        mergedNetwork = NetworkMerger.mergeNetworks(mergedNetwork, agencyId2Network.get(agencyId));
        mergedSchedule = ScheduleMerger.mergeSchedules(mergedSchedule, agencyId2TransitSchedule.get(agencyId), agencyId);
        mergedVehicles = VehicleMerger.mergeVehicles(mergedVehicles, agencyId2Vehicles.get(agencyId));
    }

    private Map<String, String> filterValidAgencies(Map<String, String> agency2AgencyId) {
        return agency2AgencyId.entrySet().stream().filter(e ->
                new File(String.format("%s%s/PlausabilityResults", outputDir, e.getKey())).exists()
        ).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private void writeMergedDataToFile() {
        NetworkWriter netWriter = new NetworkWriter(mergedNetwork);
        System.out.println("Writing merged network...");
        netWriter.write(netOutName);
        System.out.println("Done!");

        TransitScheduleWriter tsWriter = new TransitScheduleWriter(mergedSchedule);
        System.out.println("Writing merged schedule...");
        tsWriter.writeFile(scheduleOutName);
        System.out.println("Done!");

        VehicleWriterV1 vehWriter = new VehicleWriterV1(mergedVehicles);
        System.out.println("Writing merged vehicles...");
        vehWriter.writeFile(vehiclesOutName);
        System.out.println("Done!");
    }
}
