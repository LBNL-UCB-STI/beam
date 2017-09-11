package beam.agentsim.events;

import beam.router.RoutingModel;
import org.apache.commons.lang3.StringUtils;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.vehicles.Vehicle;

import java.util.Map;

/**
 * BEAM
 */
public class PathTraversalEvent extends Event {
    public final static String EVENT_TYPE = "PathTraversal";
    public final static String ATTRIBUTE_VIZ_DATA = "viz_data";
    public final static String ATTRIBUTE_LINK_IDS = "links";
    public final static String ATTRIBUTE_MODE = "mode";
    public final static String ATTRIBUTE_DEPARTURE_TIME = "departure_time";
    public final static String ATTRIBUTE_VEHICLE_ID = "vehicle";
    private final String linkIds;
    private final String vehicleId;
    private final String departureTime;
    private final String mode;
    private final RoutingModel.BeamLeg beamLeg;

    public PathTraversalEvent(double time, Id<Vehicle> vehicleId, RoutingModel.BeamLeg beamLeg) {
        super(time);

        this.linkIds = StringUtils.join(beamLeg.travelPath().linkIds(), ",");
        this.vehicleId = vehicleId.toString();
        this.departureTime = (new Double(time)).toString();
        this.mode = beamLeg.mode().value();
        this.beamLeg = beamLeg;
    }

    @Override
    public Map<String, String> getAttributes() {
        Map<String, String> attr = super.getAttributes();

        attr.put(ATTRIBUTE_VEHICLE_ID, vehicleId);
        attr.put(ATTRIBUTE_DEPARTURE_TIME, departureTime);
        attr.put(ATTRIBUTE_MODE, mode);
        attr.put(ATTRIBUTE_LINK_IDS, linkIds);
//    attr.put(ATTRIBUTE_VIZ_DATA, beamLeg.asJson.noSpaces)
        return attr;
    }

    public RoutingModel.BeamLeg getBeamLeg(){
        return this.beamLeg;
    }


    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }
}
