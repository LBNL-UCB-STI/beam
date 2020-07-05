package beam.physsim.jdeqsim;

import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.PathTraversalLib;
import beam.sim.common.GeoUtils;
import beam.utils.DebugLib;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.transit.TransportNetwork;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.events.Event;

import java.util.LinkedList;
import java.util.Map;

public class AgentSimPhysSimInterfaceDebugger {
    private final GeoUtils geoUtils;
    private final TransportNetwork transportNetwork;

    public AgentSimPhysSimInterfaceDebugger(GeoUtils geoUtils, TransportNetwork transportNetwork) {
        this.geoUtils = geoUtils;
        this.transportNetwork = transportNetwork;
    }

    public void handleEvent(Event event) {
        exploreAdjacentLinkConnectivity(event);
    }

    private void exploreAdjacentLinkConnectivity(Event event) {
        Map<String, String> eventAttributes = event.getAttributes();
        String mode_ = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_MODE());
        String links_ = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_LINK_IDS());
        String vehicleType = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_TYPE());
        String vehicleId_ = eventAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID());

        if (mode_.equalsIgnoreCase("subway")) {
            DebugLib.emptyFunctionForSettingBreakPoint();
        }

        LinkedList<String> linkIds_ = PathTraversalLib.getLinkIdList(links_, ",");

        for (int i = 0; i < linkIds_.size() - 1; i++) {
            int linkIdInt = Integer.parseInt(linkIds_.get(i));
            int nextlinkIdInt = Integer.parseInt(linkIds_.get(i + 1));
            EdgeStore.Edge currentEdge = transportNetwork.streetLayer.edgeStore.getCursor(linkIdInt);
            ///System.out.println(linkIdInt + "-> (" + currentEdge.getFromVertex() + "," + currentEdge.getToVertex() + ")");
            EdgeStore.Edge nextEdge = transportNetwork.streetLayer.edgeStore.getCursor(nextlinkIdInt);

            double distanceBetweenEdgesInMeters = geoUtils.distUTMInMeters(new Coord(currentEdge.getGeometry().getCoordinate().x, currentEdge.getGeometry().getCoordinate().y), new Coord(nextEdge.getGeometry().getCoordinate().x, nextEdge.getGeometry().getCoordinate().y));
            if (currentEdge.getToVertex() == nextEdge.getFromVertex()) {
                DebugLib.emptyFunctionForSettingBreakPoint();
            } else {
                if (!(vehicleType.equalsIgnoreCase("tram") || vehicleType.equalsIgnoreCase("subway") || vehicleType.equalsIgnoreCase("rail") || vehicleType.equalsIgnoreCase("ferry") || vehicleType.equalsIgnoreCase("cable_car")) && distanceBetweenEdgesInMeters > 1000) {
                    DebugLib.emptyFunctionForSettingBreakPoint();
                    System.out.print("pathTraversalLinks not connected, vehicle Id:");
                    System.out.print(vehicleId_);
                    System.out.print(", mode:");
                    System.out.print(mode_);
                    System.out.print(", currentEdgeIndex:");
                    System.out.print(currentEdge.getEdgeIndex());
                    System.out.print(", nextEdgeIndex:");
                    System.out.print(nextEdge.getEdgeIndex());
                    System.out.print(", currentEdge.getToVertex():");
                    System.out.print(currentEdge.getToVertex());
                    System.out.print(", nextEdge.getFromVertex():");
                    System.out.print(nextEdge.getFromVertex());
                    System.out.print(", linkIds:");
                    System.out.print(linkIds_);
                    System.out.print(", errorPositionInRouteIndex:");
                    System.out.print(i);
                    System.out.print(", distanceBetweenEdgesInMeters:");
                    System.out.println(distanceBetweenEdgesInMeters);
                    System.out.println(event.toString());
                    DebugLib.emptyFunctionForSettingBreakPoint();
                }
            }
        }
    }
}
