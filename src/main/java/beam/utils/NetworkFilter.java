package beam.utils;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;

/**
 * Utility class to create a filtered copy of a MATSim network
 * that only includes links with specified allowed modes
 */
public class NetworkFilter {

    /**
     * Creates a new network containing only links that allow Car mode
     *
     * @param originalNetwork the original MATSim network
     * @return a new network with filtered links
     */
    public static Network filterNetworkByCarMode(Network originalNetwork) {
        return filterNetworkByMode(originalNetwork, "car");
    }

    /**
     * Creates a new network containing only links that allow the specified mode
     *
     * @param originalNetwork the original MATSim network
     * @param modeToKeep the mode to filter by (e.g., "car", "bike", "pt")
     * @return a new network with filtered links
     */
    public static Network filterNetworkByMode(Network originalNetwork, String modeToKeep) {
        Network filteredNetwork = NetworkUtils.createNetwork();

        // Copy network properties
        filteredNetwork.setCapacityPeriod(originalNetwork.getCapacityPeriod());
        filteredNetwork.setEffectiveLaneWidth(originalNetwork.getEffectiveLaneWidth());
        filteredNetwork.setEffectiveCellSize(originalNetwork.getEffectiveCellSize());
        if (originalNetwork.getName() != null) {
            filteredNetwork.setName(originalNetwork.getName() + "_" + modeToKeep + "_only");
        }

        // Copy all nodes
        for (Node node : originalNetwork.getNodes().values()) {
            Node newNode = filteredNetwork.getFactory().createNode(
                    node.getId(),
                    node.getCoord()
            );
            filteredNetwork.addNode(newNode);
        }

        // Copy only links with the specified mode
        for (Link link : originalNetwork.getLinks().values()) {
            if (link.getAllowedModes().contains(modeToKeep)) {
                Node fromNode = filteredNetwork.getNodes().get(link.getFromNode().getId());
                Node toNode = filteredNetwork.getNodes().get(link.getToNode().getId());

                Link newLink = filteredNetwork.getFactory().createLink(
                        link.getId(),
                        fromNode,
                        toNode
                );

                newLink.setLength(link.getLength());
                newLink.setNumberOfLanes(link.getNumberOfLanes());
                newLink.setFreespeed(link.getFreespeed());
                newLink.setCapacity(link.getCapacity());
                newLink.setAllowedModes(link.getAllowedModes());

                filteredNetwork.addLink(newLink);
            }
        }

        return filteredNetwork;
    }
}