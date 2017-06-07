package beam.playground.r5;

import beam.utils.DefaultPathUtil;
import com.conveyal.r5.transit.TransportNetwork;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;

/**
 * Created by ahmar.nadeem on 5/28/2017.
 * <p>
 * This class is the entry point for creating network.dat
 * It expects an argument that tells the directory path where the osm files are present.
 * The system picks that osm file from that path and creates the network.dat file there.
 * </p>
 * <p>
 * In case the argument is not provided, the system will search for the osm file in USER_HOME/beam/network folder.
 * </p>
 */
public class NetworkBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(NetworkBuilder.class);

    public static void buildNetwork(String inputDirectory, String outputDirectory) throws IOException {

        final boolean inMemory = false;

        if( StringUtils.isBlank( inputDirectory ) ){
            inputDirectory = DefaultPathUtil.getDefaultInputDirectory().toString();
        }

        if( StringUtils.isBlank( outputDirectory ) ){
            outputDirectory = DefaultPathUtil.getDefaultOutputDirectory().toString();
        }

        if( !Files.list(Paths.get(inputDirectory)).findAny().isPresent() ){
            LOG.error("The input directory should at-least contain the osm files. No files found. Exiting.");
            System.exit(-1);
        }

        if (!verifyNetwork(inputDirectory, outputDirectory)) {
            LOG.info("No network.dat file found. The system will generate the new files using provided osm data.");
            buildNetwork(inputDirectory, outputDirectory, inMemory);
        }
    }

    /**
     * This function helps deciding whether the system needs to go ahead with the network creation or not.
     * It checks if the network.dat, osm.mapdb and osm.mapdb.p files exist in the directory and return true in case the files are present; false otherwise.
     *
     * @param inputDirectory
     * @return true if the directory already contains network.dat and osm.mapdb files.
     */
    private static boolean verifyNetwork(String inputDirectory, String outputDirectory) throws IOException {

        Path networkFilePath = Paths.get(inputDirectory + File.separator + "network.dat");
//        Path mapDBFilePath = Paths.get(inputDirectory + File.separator + "osm.mapdb");
//        Path mapDBPFilePath = Paths.get(inputDirectory + File.separator + "osm.mapdb.p");

        if (Files.isReadable(Paths.get(inputDirectory)) &&
                Files.isRegularFile(networkFilePath) /**&&
                Files.isRegularFile(mapDBFilePath) && Files.isRegularFile(mapDBPFilePath)*/) {
            LOG.error("Output files already exist in the given directory.  If the existing files are corrupt or invalid, " +
                    "please delete the files and run the process again or use another directory to generate the network.");
            return true;
        }

        if( Files.list(Paths.get(outputDirectory)).findAny().isPresent() ){

            Iterator<Path> filesIterator = Files.list(Paths.get(outputDirectory)).iterator();
            while( filesIterator.hasNext() ){
                if( filesIterator.next().toFile().getName().endsWith(".dat")){
                    LOG.error("The output directory already contains the *.dat files. Exiting.");
                    System.exit(-1);
                }
            }
        }

        return false;
    }

    /**
     * This function builds the network by using the
     *
     * @param inputDirectory
     * @param inMemory
     */
    private static void buildNetwork(String inputDirectory, String outputDirectory, boolean inMemory) {
        File dir = new File(inputDirectory);

        if (!dir.isDirectory() && dir.canRead()) {
            LOG.error("'{}' is not a readable directory.", dir);
        }

        TransportNetwork transportNetwork = TransportNetwork.fromDirectory(dir);
        //In memory doesn't save it to disk others do (build, preFlight)
        if (!inMemory) {
            try {
                transportNetwork.write(new File(outputDirectory, "network.dat"));
            } catch (Exception e) {
                LOG.error("An error occurred during saving transit networks. Exiting.", e);
                System.exit(-1);
            }
        }
    }
}
