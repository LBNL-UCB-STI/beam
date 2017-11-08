package beam.playground.r5;

import com.conveyal.r5.transit.TransportNetwork;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

    public static void main(String args[]) {

        buildNetwork(null);

    }

    public static void buildNetwork(String directoryPath) {

        final boolean inMemory = false;

        //Sets default network path inside the user directory
        String inputDirectory = Paths.get(System.getProperty("user.home"), "beam", "network").toString();

        //Overrides the default directory path with the user provided path.
        if (StringUtils.isNotBlank(directoryPath)) {
            inputDirectory = directoryPath;
        }

        if (!verifyNetwork(inputDirectory)) {
            LOG.info("No network.dat and mapdb files found. The system will generate the new files using provided osm data.");
            buildNetwork(inputDirectory, inMemory);
        }
    }

    /**
     * This function helps deciding whether the system needs to go ahead with the network creation or not.
     * It checks if the network.dat, osm.mapdb and osm.mapdb.p files exist in the directory and return true in case the files are present; false otherwise.
     *
     * @param inputDirectory
     * @return true if the directory already contains network.dat and osm.mapdb files.
     */
    private static boolean verifyNetwork(String inputDirectory) {

        Path networkFilePath = Paths.get(inputDirectory + File.separator + "network.dat");
        Path mapDBFilePath = Paths.get(inputDirectory + File.separator + "osm.mapdb");
        Path mapDBPFilePath = Paths.get(inputDirectory + File.separator + "osm.mapdb.p");

        if (Files.isReadable(Paths.get(inputDirectory)) &&
                Files.isRegularFile(networkFilePath) &&
                Files.isRegularFile(mapDBFilePath) && Files.isRegularFile(mapDBPFilePath)) {
            LOG.error("Output files already exist in the given directory.  If the existing files are corrupt or invalid, please delete the files and run the process again or use another directory to generate the network.");
            return true;
        }

        return false;
    }

    /**
     * This function builds the network by using the
     *
     * @param inputDirectory
     * @param inMemory
     */
    private static void buildNetwork(String inputDirectory, boolean inMemory) {
        File dir = new File(inputDirectory);

        if (!dir.isDirectory() && dir.canRead()) {
            LOG.error("'{}' is not a readable directory.", dir);
        }

        TransportNetwork transportNetwork = TransportNetwork.fromDirectory(dir);
        //In memory doesn't save it to disk others do (build, preFlight)
        if (!inMemory) {
            try {
                transportNetwork.write(new File(dir, "network.dat"));
            } catch (Exception e) {
                LOG.error("An error occurred during saving transit networks. Exiting.", e);
                System.exit(-1);
            }
        }
    }
}
