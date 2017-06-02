package beam.sim.traveltime;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.mobsim.qsim.interfaces.NetsimLink;
import org.matsim.core.mobsim.qsim.interfaces.NetsimNetwork;
import org.matsim.core.mobsim.qsim.interfaces.NetsimNode;
import org.matsim.vis.snapshotwriters.VisLink;

import java.util.Map;

/**
 * BEAM
 */
public class BeamNetsimNetwork implements NetsimNetwork {
    @Override
    public Map<Id<Link>, ? extends VisLink> getVisLinks() {
        return null;
    }

    @Override
    public Network getNetwork() {
        return null;
    }

    @Override
    public Map<Id<Link>, ? extends NetsimLink> getNetsimLinks() {
        return null;
    }

    @Override
    public Map<Id<Node>, ? extends NetsimNode> getNetsimNodes() {
        return null;
    }

    @Override
    public NetsimLink getNetsimLink(Id<Link> id) {
        return null;
    }

    @Override
    public NetsimNode getNetsimNode(Id<Node> id) {
        return null;
    }
}
