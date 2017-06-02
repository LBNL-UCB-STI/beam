package beam.sim.traveltime;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.mobsim.framework.MobsimTimer;
import org.matsim.core.mobsim.qsim.interfaces.AgentCounter;
import org.matsim.core.mobsim.qsim.qnetsimengine.*;
import org.matsim.vis.snapshotwriters.SnapshotLinkWidthCalculator;

/**
 * BEAM
 */
public class BeamQNetsimNetworkFactory extends QNetworkFactory {

    @Override void initializeFactory(AgentCounter agentCounter, MobsimTimer mobsimTimer, QNetsimEngine.NetsimInternalInterface netsimEngine1) {
    }
    @Override
    QNode createNetsimNode(Node node) {
        return null;

    }

    @Override QLinkI createNetsimLink(Link link, QNode queueNode) {
        return null;
    }
}
