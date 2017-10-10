package beam.physsim.jdeqsim;

import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.PathTraversalLib;
import beam.router.r5.NetworkCoordinator;
import beam.sim.BeamServices;
import beam.utils.DebugLib;
import com.conveyal.r5.streets.EdgeStore;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.utils.collections.Tuple;

import java.util.LinkedList;

public class AgentSimPhysSimInterfaceDebugger {

    // TODO: push this parameter to config
    public static final boolean DEBUGGER_ON = false;
    private BeamServices services;


    public AgentSimPhysSimInterfaceDebugger(BeamServices services){

        this.services = services;
    }


    public void handleEvent(Event event) {
        exploreAdjacentLinkConnectivity(event);
    }


    private void exploreAdjacentLinkConnectivity(Event event){
        String mode_ = event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_MODE);
        String links_ = event.getAttributes().get("links");
        String vehicleType = event.getAttributes().get("vehicle_type");
        String vehicleId_ = event.getAttributes().get("vehicle_id");
        double fuel = Double.parseDouble(event.getAttributes().get("fuel"));

        if (mode_.equalsIgnoreCase("subway")){
            DebugLib.emptyFunctionForSettingBreakPoint();
        }

        LinkedList<String> linkIds_ = PathTraversalLib.getLinkIdList(links_,",");

        Tuple<Coord, Coord> startAndEndCoordinates = PathTraversalLib.getStartAndEndCoordinates(event.getAttributes());


        for (int i=0;i< linkIds_.size()-1;i++){
            int linkIdInt=Integer.parseInt(linkIds_.get(i));
            int nextlinkIdInt=Integer.parseInt(linkIds_.get(i+1));
            EdgeStore.Edge currentEdge = NetworkCoordinator.transportNetwork().streetLayer.edgeStore.getCursor(linkIdInt);
            ///System.out.println(linkIdInt + "-> (" + currentEdge.getFromVertex() + "," + currentEdge.getToVertex() + ")");
            EdgeStore.Edge nextEdge = NetworkCoordinator.transportNetwork().streetLayer.edgeStore.getCursor(nextlinkIdInt);

            double distanceBetweenEdgesInMeters=services.geo().distInMeters(new Coord(currentEdge.getGeometry().getCoordinate().x,currentEdge.getGeometry().getCoordinate().y), new Coord(nextEdge.getGeometry().getCoordinate().x, nextEdge.getGeometry().getCoordinate().y));
            if (currentEdge.getToVertex()==nextEdge.getFromVertex()){
                DebugLib.emptyFunctionForSettingBreakPoint();
            } else {
                if (!(vehicleType.equalsIgnoreCase("tram") || vehicleType.equalsIgnoreCase("subway") || vehicleType.equalsIgnoreCase("rail") || vehicleType.equalsIgnoreCase("ferry") || vehicleType.equalsIgnoreCase("cable_car")) && distanceBetweenEdgesInMeters>1000){
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
