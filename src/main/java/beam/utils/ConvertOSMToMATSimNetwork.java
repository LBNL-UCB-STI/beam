package beam.utils;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.core.utils.io.OsmNetworkReader;
import org.matsim.run.NetworkCleaner;

public class ConvertOSMToMATSimNetwork {

    public static void main(String[] args) {
        if (args == null || args.length == 0) {
            System.out.println("Please run by specifying the base directory as argument where osm file(ontario-motorways-trunks.osm) is located.");
            return;
        }
        Scenario sc = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        // creating an empty matsim network
        Network network = sc.getNetwork();
        // The EPSG:3161 is the Lambert projection for Ontario
        CoordinateTransformation ct = TransformationFactory.getCoordinateTransformation(TransformationFactory.WGS84, "EPSG:3161");
        OsmNetworkReader osmReader = new OsmNetworkReader(network, ct);

        osmReader.setKeepPaths(false);
        osmReader.setScaleMaxSpeed(true);

        // this layer covers the whole area, Belgium and bordering areas
        // including OSM secondary roads or greater
//		osmReader.setHierarchyLayer(51.671, 2.177, 49.402, 6.764, 4);

        // converting the merged OSM network into matsim format
        osmReader.parse(args[0] + "/ontario-motorways-trunks.osm");
        new NetworkWriter(network).write(args[0] + "/ontario-motorways-trunks.xml");

        // writing out a cleaned matsim network and loading it
        // into the scenario
        (new NetworkCleaner()).run(args[0] + "/ontario-motorways-trunks.xml", args[0] + "/ontario-motorways-trunks-clean.xml.gz");
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());

        /*
         * TODO Debug the following
         *
         * The following code is out of date from the online tutorial. Needs to be fixed
         */
//		new MatsimNetworkReader(scenario).readFile(OUTFILE.split(".xml")[0] + "_clean.xml.gz");
//		network = (NetworkImpl) scenario.getNetwork();
//
//		// simplifying the cleaned network
//		NetworkSimplifier simplifier = new NetworkSimplifier();
//		Set<Integer> nodeTypess2merge = new HashSet<Integer>();
//		nodeTypess2merge.add(new Integer(4));
//		nodeTypess2merge.add(new Integer(5));
//		simplifier.setNodesToMerge(nodeTypess2merge);
//		simplifier.run(network);
//		new NetworkWriter(network).write(OUTFILE.split(".xml")[0] + "_clean_simple.xml.gz");
    }

}
