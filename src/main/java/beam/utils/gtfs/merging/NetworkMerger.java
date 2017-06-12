/*
 * *********************************************************************** *
 * project: org.matsim.*                                                   *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2015 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** *
 */

package beam.utils.gtfs.merging;

import com.google.common.collect.Sets;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.Set;

/**
 * Merges two networks to a new one.
 *
 * @author boescpa
 * @author sfeygin (modifying)
 */
public class NetworkMerger {
	private static Logger log = Logger.getLogger(NetworkMerger.class);

	public static Network mergeNetworks(final Network networkA, final Network networkB) {
		final Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		scenario.getConfig().transit().setUseTransit(true);
		final Network mergedNetwork = scenario.getNetwork();
		final NetworkFactory factory = mergedNetwork.getFactory();

		log.info("Merging networks...");
		// Nodes
		for (Node node : networkA.getNodes().values()) {
			Node newNode = factory.createNode(Id.create(node.getId().toString(), Node.class), node.getCoord());
			mergedNetwork.addNode(newNode);
		}

        networkB.getNodes().values().stream().filter(node -> !networkA.getNodes().containsKey(node.getId())).forEach(node -> {
            Node newNode = factory.createNode(Id.create(node.getId().toString(), Node.class), node.getCoord());
            mergedNetwork.addNode(newNode);
        });

		// Links
		double capacityFactor = mergedNetwork.getCapacityPeriod() / networkA.getCapacityPeriod();
		for (Link link : networkA.getLinks().values()) {
				Id<Node> fromNodeId = Id.create(link.getFromNode().getId().toString(), Node.class);
				Id<Node> toNodeId = Id.create(link.getToNode().getId().toString(), Node.class);
				Link newLink = factory.createLink(Id.create(link.getId().toString(), Link.class),
						mergedNetwork.getNodes().get(fromNodeId), mergedNetwork.getNodes().get(toNodeId));
				newLink.setAllowedModes(link.getAllowedModes());
				newLink.setCapacity(link.getCapacity() * capacityFactor);
				newLink.setFreespeed(link.getFreespeed());
				newLink.setLength(link.getLength());
				newLink.setNumberOfLanes(link.getNumberOfLanes());
				mergedNetwork.addLink(newLink);
		}

		capacityFactor = mergedNetwork.getCapacityPeriod() / networkB.getCapacityPeriod();
		for (Link link : networkB.getLinks().values()) {
            final Set<String> allowedModes = link.getAllowedModes();
            if(mergedNetwork.getLinks().containsKey(link.getId())){
                final Link existingLink = mergedNetwork.getLinks().get(link.getId());
                final Set<String> existingModes = existingLink.getAllowedModes();
                if(!existingModes.equals(allowedModes)){
                    existingLink.setAllowedModes(Sets.union(existingModes,allowedModes));
                }
            }
            else{
                Id<Node> fromNodeId = Id.create(link.getFromNode().getId().toString(), Node.class);
                Id<Node> toNodeId = Id.create(link.getToNode().getId().toString(), Node.class);
                Link newLink = factory.createLink(Id.create(link.getId().toString(), Link.class),
                        mergedNetwork.getNodes().get(fromNodeId), mergedNetwork.getNodes().get(toNodeId));
                newLink.setAllowedModes(allowedModes);
                newLink.setCapacity(link.getCapacity() * capacityFactor);
                newLink.setFreespeed(link.getFreespeed());
                newLink.setLength(link.getLength());
                newLink.setNumberOfLanes(link.getNumberOfLanes());
                mergedNetwork.addLink(newLink);
            }
		}

		log.info(" Merging Stats:");
		log.info("  Number of links network A: " + networkA.getLinks().size());
		log.info("  Number of nodes network A: " + networkA.getNodes().size());
		log.info("  Number of links network B: " + networkB.getLinks().size());
		log.info("  Number of nodes network B: " + networkB.getNodes().size());
		log.info("  Sum of links: " + (networkA.getLinks().size() + networkB.getLinks().size()));
		log.info("  Sum of nodes: " + (networkA.getNodes().size() + networkB.getNodes().size()));
		log.info("  Number of links merged: " + mergedNetwork.getLinks().size());
		log.info("  Number of nodes merged: " + mergedNetwork.getNodes().size());

		log.info("Merging networks... done.");
		return mergedNetwork;
	}


}
