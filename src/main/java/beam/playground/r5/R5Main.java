package beam.playground.r5;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.conveyal.r5.api.ProfileResponse;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.point_to_point.builder.PointToPointQuery;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.profile.StreetPath;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;

public class R5Main {
  private static String GRAPH_FILE = "/network.dat";
  private static String OSM_FILE = "/osm.mapdb";
  private Logger LOG = LoggerFactory.getLogger(getClass());
  private TransportNetwork transportNetwork = null;

  public static void main(String[] args) throws Exception {

    R5Main self = new R5Main();
    // Loading graph
    self.init();
    //calculate Route
    self.calcRoute();
  }

  private void init() throws Exception {
    // TODO: network.dat and its paths need to externalize
    loadGraph(Paths.get(System.getProperty("user.home"),"beam", "network").toString());
  }

  private void loadGraph(String networkDir) throws Exception {
    File networkFile = null;
    File mapdbFile = null;
    if (Files.exists(Paths.get(networkDir))) {
      Path networkPath = Paths.get(networkDir, GRAPH_FILE);
      if (Files.isReadable(networkPath)) {
        networkFile = networkPath.toFile();
      }

      Path osmPath = Paths.get(networkDir, OSM_FILE);
      if (Files.isReadable(osmPath)) {
        mapdbFile = osmPath.toFile();
      }
    }

    if (networkFile == null) {
      networkFile = new File(getClass().getResource(GRAPH_FILE).getFile());
    }

    if (mapdbFile == null) {
      mapdbFile = new File(getClass().getResource(OSM_FILE).getFile());
    }

    // Loading graph
    transportNetwork = TransportNetwork.read(networkFile);

    // Optional used to get street names:
    transportNetwork.readOSM(mapdbFile);
  }

  private long calcRoute() {
    StreetRouter streetRouter = new StreetRouter(transportNetwork.streetLayer);
    ProfileRequest profileRequest = buildRequest(false);
    streetRouter.profileRequest = profileRequest;
    streetRouter.streetMode = StreetMode.WALK;

    // TODO use target pruning instead of a distance limit
    streetRouter.distanceLimitMeters = 100_000;

    streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon);
    streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon);

    streetRouter.route();

    // Gets lowest weight state for end coordinate split
    StreetRouter.State lastState = streetRouter.getState(streetRouter.getDestinationSplit());

    StreetPath streetPath = new StreetPath(lastState, transportNetwork, false);

    long totalDistance = 0;
    int stateIdx = 0;

    // TODO: this can be improved since end and start vertices are the same
    // in all the edges.
    for (StreetRouter.State state : streetPath.getStates()) {
      Integer edgeIdx = state.backEdge;
      if (!(edgeIdx == -1 || edgeIdx == null)) {
        EdgeStore.Edge edge = transportNetwork.streetLayer.edgeStore.getCursor(edgeIdx);
        LOG.info("{} - Lat/Long for edgeIndex [{}] are [{}]", stateIdx++, edgeIdx, edge.getGeometry());
        LOG.info("\tmode [{}]", state.streetMode);
        LOG.info("\tweight [{}]", state.weight);
        LOG.info("\tduration sec [{}:{}]", state.getDurationSeconds()/60, state.getDurationSeconds()%60);        
        LOG.info("\tdistance [{}]", state.distance / 1000);       
      }
    }
    return totalDistance;
  }

  private long calcRoute2() {
    PointToPointQuery pointToPointQuery = new PointToPointQuery(transportNetwork);
    // Gets a response:
    ProfileResponse profileResponse = pointToPointQuery.getPlan(buildRequest(false));
    System.out.println(profileResponse);
    return profileResponse.getOptions().size();

  }

  private ProfileRequest buildRequest(boolean isTransit) {
    ProfileRequest profileRequest = new ProfileRequest();
    // Set timezone to timezone of transport network
    profileRequest.zoneId = transportNetwork.getTimeZone();
    profileRequest.fromLat = 45.547716775429045;
    profileRequest.fromLon = -122.68020629882812;
    profileRequest.toLat = 45.554628830194815;
    profileRequest.toLon = -122.66613006591795;
    profileRequest.wheelchair = false;
    profileRequest.bikeTrafficStress = 4;
    // TODO: time need to get from request
    profileRequest.setTime("2015-02-05T07:30+05:00", "2015-02-05T10:30+05:00");
    if (isTransit) {
      profileRequest.transitModes = EnumSet.of(TransitModes.TRANSIT, TransitModes.BUS,
          TransitModes.SUBWAY, TransitModes.RAIL);
    }
    profileRequest.accessModes = EnumSet.of(LegMode.WALK);
    profileRequest.egressModes = EnumSet.of(LegMode.WALK);
    profileRequest.directModes = EnumSet.of(LegMode.WALK, LegMode.BICYCLE);

    return profileRequest;
  }
}
