package beam.utils.router;

import com.conveyal.r5.analyst.fare.SimpleInRoutingFareCalculator;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.profile.StreetPath;
import com.conveyal.r5.streets.*;
import com.conveyal.r5.transit.TransportNetwork;

import java.io.File;
import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.LinkedList;

public class Main {
    private static final EdgeStore.EdgeFlag mode = EdgeStore.EdgeFlag.ALLOWS_CAR;
    private static final StreetMode streetMode = StreetMode.CAR;

    private TransportNetwork transportNetwork = TransportNetwork.fromDirectory(new File("test/input/sf-light/r5"));
    private TurnCostCalculator turnCostCalculator = new TurnCostCalculator(transportNetwork.streetLayer, true) {
        @Override
        public int computeTurnCost(int fromEdge, int toEdge, StreetMode streetMode) {
            return 0;
        }
    };

    private TravelCostCalculator travelCostCalculator = (edge, legDurationSeconds, traversalTimeMilliseconds) -> traversalTimeMilliseconds;
    private TravelTimeCalculator travelTimeCalculator = (edge, durationSeconds, streetMode, req) -> 10;


    public static void main(String[] args) {
        Main main = new Main();

        EdgeStore.Edge startEdge1 = main.edgeById(20109); //This link doesn't have mode ALLOWS_CAR
        EdgeStore.Edge stopEdge1 = main.edgeById(66763);
        //EdgeStore.Edge stopEdge2 = main.edgeById(46525);//This link doesn't have mode ALLOWS_CAR

        Iterator<EdgeStore.Edge> edges = main.routeV1(startEdge1, stopEdge1);
        main.pathModeCheck(edges);
    }

    private boolean pathModeCheck(Iterator<EdgeStore.Edge> it) {
        boolean checkFlag = true;
        LinkedList<EdgeStore.Edge> edges = new LinkedList<>();

        while(it.hasNext()){
            EdgeStore.Edge nextEdge = it.next();
            edges.add(nextEdge);

            if (!nextEdge.getFlag(mode)){
                checkFlag = false;
            }
        }

        if (!checkFlag) {
            System.out.println("Failed with path where edge are:");
            edges.forEach((e) -> System.out.println(e.getEdgeIndex() + " -> " + e.toString()));
        }
        return checkFlag;
    }

    private EdgeStore.Edge edgeById(int edgeId) {
        try {
            return transportNetwork.streetLayer.edgeStore.getCursor(edgeId);
        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        }
    }

    private VertexStore.Vertex vertexById(int vertexId) {
        try {
            return transportNetwork.streetLayer.edgeStore.vertexStore.getCursor(vertexId);
        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        }
    }

    private Iterator<EdgeStore.Edge> routeV1(EdgeStore.Edge startEdge, EdgeStore.Edge stopEdge) {
        VertexStore.Vertex startVertex = vertexById(startEdge.getFromVertex());
        VertexStore.Vertex stopVertex = vertexById(stopEdge.getFromVertex());

        StreetPath streetPath = calculateRouteV1(startVertex, stopVertex);

        return streetPath.getEdges().stream().map(this::edgeById).iterator();
    }

    private StreetPath calculateRouteV1(VertexStore.Vertex startVertex, VertexStore.Vertex stopVertex) {
        StreetRouter streetRouter = createStreetRouter(startVertex, stopVertex);

        streetRouter.setOrigin(startVertex.getLat(), startVertex.getLon());
        streetRouter.setDestination(stopVertex.getLat(), stopVertex.getLon());
        streetRouter.route();

        StreetRouter.State lastState = streetRouter.getState(streetRouter.getDestinationSplit());

        if (lastState != null) {
            return new StreetPath(lastState, transportNetwork, false);
        } else {
            throw new IllegalArgumentException("Last state is not found!");
        }
    }

    private StreetRouter createStreetRouter(VertexStore.Vertex startVertex, VertexStore.Vertex stopVertex) {
        StreetRouter streetRouter = new StreetRouter(
                transportNetwork.streetLayer,
                travelTimeCalculator,
                turnCostCalculator,
                travelCostCalculator
        );

        ProfileRequest profileRequest = createProfileRequest(startVertex, stopVertex);

        streetRouter.profileRequest = profileRequest;
        streetRouter.streetMode = streetMode;

        return streetRouter;
    }

    private ProfileRequest createProfileRequest(VertexStore.Vertex startVertex, VertexStore.Vertex stopVertex) {
        ProfileRequest profileRequest = new ProfileRequest();
        // Warning: carSpeed is not used for link traversal (rather, the OSM travel time model is used),
        // but for R5-internal bushwhacking from network to coordinate, AND ALSO for the A* remaining weight heuristic,
        // which means that this value must be an over(!)estimation, otherwise we will miss optimal routes,
        // particularly in the presence of tolls.
        profileRequest.carSpeed = 60f;
        profileRequest.maxWalkTime = 30;
        profileRequest.maxCarTime = 30;
        profileRequest.maxBikeTime = 30;
        // Maximum number of transit segments. This was previously hardcoded as 4 in R5, now it is a parameter
        // that defaults to 8 unless I reset it here. It is directly related to the amount of work the
        // transit router has to do.
        profileRequest.maxRides = 4;
        profileRequest.streetTime = 2 * 60;
        profileRequest.maxTripDurationMinutes = 4 * 60;
        profileRequest.wheelchair = false;
        profileRequest.bikeTrafficStress = 4;
        profileRequest.zoneId = transportNetwork.getTimeZone();
        profileRequest.monteCarloDraws = 10;
        profileRequest.date = OffsetDateTime.parse("2017-09-22T00:00:00-07:00").toLocalDate();
        // Doesn't calculate any fares, is just a no-op placeholder
        profileRequest.inRoutingFareCalculator = new SimpleInRoutingFareCalculator();
        profileRequest.suboptimalMinutes = 0;

        profileRequest.fromLon = startVertex.getLon();
        profileRequest.fromLat = startVertex.getLat();
        profileRequest.toLon = stopVertex.getLon();
        profileRequest.toLat = stopVertex.getLat();
        profileRequest.fromTime = 1500;
        profileRequest.toTime = profileRequest.fromTime + 61;

        profileRequest.reverseSearch = false;

        return profileRequest;
    }

}
