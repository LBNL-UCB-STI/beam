package beam.utils.gtfs;

import beam.sim.config.BeamConfig;
import org.matsim.pt.utils.TransitScheduleValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static beam.utils.gtfs.GtfsFunctions.isFilePresent;
import static beam.utils.gtfs.GtfsFunctions.opNameToPath;

//import org.matsim.pt2matsim.config.CreateDefaultConfig;
//import org.matsim.pt2matsim.config.PublicTransitMappingConfigGroup;
//import org.matsim.pt2matsim.gtfs.Gtfs2TransitSchedule;
//import org.matsim.pt2matsim.mapping.RunPublicTransitMapper;
//import org.matsim.pt2matsim.run.CheckMappedSchedulePlausibility;

/**
 * Runs GTFS Schedule Mapping.
 * <p>
 * Currently farms out GTFS data requests to {@link TransitDataDownloader}.
 * <p>
 * TODO: Make a better API for download and storage.
 * <p>
 * Created by sfeygin on 11/8/16.
 */
public class SFBayPT2MATSim {
    private final Logger log = LoggerFactory.getLogger(SFBayPT2MATSim.class);

    public static final BeamConfig BEAM_CONFIG = null; //FIXME
    private String outputDir;
    private String apiKey;

    public SFBayPT2MATSim() {
//        outputDir = BEAM_CONFIG.beam().routing().gtfs().outputDir();
//        apiKey = BEAM_CONFIG.beam().routing().gtfs().apiKey();
    }

    public static void main(String[] args) {
        final SFBayPT2MATSim sfBayPT2MATSim = new SFBayPT2MATSim();
        sfBayPT2MATSim.mapSingleGtfsOperator("Alcatraz Hornblower Ferry", "HF");
//        final OperatorDataUtility operatorDataUtility = new OperatorDataUtility();
//        final Map<String, String> operatorMap = operatorDataUtility.getOperatorMap();
//        operatorDataUtility.saveOperatorMap(String.format("%sGTFSOperators.csv",sfBayPT2MATSim.outputDir),operatorMap);

    }

    public void mapSingleGtfsOperator(String opName, String opKey) {

        final String opPathName = opNameToPath.apply(this.outputDir, opName);

        // Maybe download data and unzip
        if (!isFilePresent.test(opPathName)) {
            try {
                TransitDataDownloader DOWNLOADER = TransitDataDownloader.getInstance(this.apiKey);
                DOWNLOADER.getGTFSZip(opPathName, opKey).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("exception occurred due to ", e);
            } catch(ExecutionException e) {
                log.error("exception occurred due to ", e);
            }
        }

        final String shapeDir = String.format("%s/%s", opPathName, "ScheduleShapes/");
        if (!isFilePresent.test(shapeDir)) {
            new File(shapeDir).mkdir();
        }

        runPT2MATSim(opName, opKey);
    }

    private void runPT2MATSim(String agency, String agencyId) {

        gtfs2Schedule(agency, agencyId);

        mapSchedule2Network(agency, agencyId);

        checkPlausability(agency, agencyId);
        try {
            TransitScheduleValidator.main(new String[]{String.format("%s%s/%s_MappedTransitSchedule.xml", outputDir, agency, agencyId),
                    String.format("%s%s/%s_MultiModalNetwork.xml.gz", outputDir, agency, agencyId)
            });
        } catch (IOException | SAXException | ParserConfigurationException e) {
            log.error("exception occurred due to ", e);
        }
    }

    private void gtfs2Schedule(String agency, String agencyId) {
        String[] gtfsConverterArgs = new String[]{
                // [0] folder where the gtfs files are located
                outputDir + agency + "/" + agencyId + "_gtfs/",
                // [1] which service ids should be used.
                "dayWithMostTrips",
                // [2] the output coordinate system
                BEAM_CONFIG.beam().spatial().localCRS(),
                // [3] output transit schedule file
                String.format("%s%s/%s_UnmappedTransitSchedule.xml.gz", outputDir, agency, agencyId),
                // [4] output default vehicles file (optional)
                String.format("%s%s/Vehicles.xml", outputDir, agency),
                // [5] output converted shape files. Is created based on shapes.txt and shows
                //      all trips contained in the schedule.
                String.format("%s%s/ScheduleShapes/Shapes.shp", outputDir, agency)
        };
//        Gtfs2TransitSchedule.main(gtfsConverterArgs);
    }

    private void mapSchedule2Network(String agency, String agencyId) {
        // Create a mapping config
        final String mapperConfigPath = String.format("%s%s/MapperConfig.xml", outputDir, agency);
//        CreateDefaultConfig.main(new String[]{mapperConfigPath});

        // Open the mapping-Config and set the paramters to the required values
        // (usually done manually by opening the config with a simple editor)
//        Config mapperConfig = ConfigUtils.loadConfig(
//                mapperConfigPath,
//                PublicTransitMappingConfigGroup.createDefaultConfig());

//        // Inputs
//        mapperConfig.getModule("PublicTransitMapping").addParam("networkFile", String.format("%sNetworkBase.xml.gz", outputDir));  // Always starting from same network
//        mapperConfig.getModule("PublicTransitMapping").addParam("scheduleFile", String.format("%s%s/%s_UnmappedTransitSchedule.xml.gz", outputDir, agency, agencyId));
//
//        // Outputs
//        mapperConfig.getModule("PublicTransitMapping").addParam("outputNetworkFile", String.format("%s%s/%s_MultiModalNetwork.xml.gz", outputDir, agency, agencyId));
//        mapperConfig.getModule("PublicTransitMapping").addParam("outputScheduleFile", String.format("%s%s/%s_MappedTransitSchedule.xml.gz", outputDir, agency, agencyId));
//        mapperConfig.getModule("PublicTransitMapping").addParam("outputStreetNetworkFile", String.format("%s%s/%s_MultiModalNetwork_StreetOnly.xml.gz", outputDir, agency, agencyId));

        // Link Candidate changes
//
//        final ConfigGroup subwayParamSet = mapperConfig.getModule("PublicTransitMapping").createParameterSet("linkCandidateCreator");
//        subwayParamSet.addParam("scheduleMode", "subway");
//        subwayParamSet.addParam("networkModes", "rail,subway");
//        mapperConfig.getModule("PublicTransitMapping").addParameterSet(subwayParamSet);
//
//        final ConfigGroup cableCarParamSet = mapperConfig.getModule("PublicTransitMapping").createParameterSet("linkCandidateCreator");
//        cableCarParamSet.addParam("scheduleMode", "cable car");
//        cableCarParamSet.addParam("networkModes", "light_rail,rail");
//        mapperConfig.getModule("PublicTransitMapping").addParameterSet(cableCarParamSet);
//
//        // Other changes
//        mapperConfig.getModule("PublicTransitMapping").addParam("scheduleFreespeedModes", "subway");
//        mapperConfig.getModule("PublicTransitMapping").addParam("uTurnCost", "30.0");
//        mapperConfig.getModule("PublicTransitMapping").addParam("prefixArtificial", String.format("%s_", agencyId));
//
////         Save the mapping-Config
////         (usually done manually)
//        final String adjustedMapperConfigPath = String.format("%s%s/MapperConfigAdjusted.xml", outputDir, agency);
//        new ConfigWriter(mapperConfig).write(adjustedMapperConfigPath);
//        // Map the schedule to the network using the config
//        RunPublicTransitMapper.main(new String[]{adjustedMapperConfigPath});
    }

    private void checkPlausability(String agency, String agencyId) {
//        try {
//            CheckMappedSchedulePlausibility.run(
//                    String.format("%s%s/%s_MappedTransitSchedule.xml.gz", outputDir, agency, agencyId),
//                    String.format("%s%s/%s_MultiModalNetwork.xml.gz", outputDir, agency, agencyId),
//                    BEAM_CONFIG.beam().routing().gtfs().crs(),
//                    String.format("%s%s/PlausabilityResults/", outputDir, agency)
//            );
//        } catch (UncheckedIOException e) {
//            e.printStackTrace();
//        }
    }
}
