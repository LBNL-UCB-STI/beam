package beam.playground.point2point;

import com.conveyal.r5.transit.TransportNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Created by ahmar.nadeem on 5/28/2017.
 */
public class PointToPointMain {

    private static final Logger LOG = LoggerFactory.getLogger(PointToPointMain.class);

    public static void main(String commandArguments[]) {

        final boolean inMemory = false;

        File dir = new File(commandArguments[1]);

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
