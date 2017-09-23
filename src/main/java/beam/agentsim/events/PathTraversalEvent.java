package beam.agentsim.events;

import beam.router.RoutingModel;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import scala.collection.IndexedSeq;

import java.util.Map;

/**
 * BEAM
 */
public class PathTraversalEvent extends Event {
    public final static String EVENT_TYPE = "PathTraversal";
    public final static String ATTRIBUTE_VIZ_DATA = "viz_data";

    public static final String ATTRIBUTE_LENGTH = "length";
    public static final String ATTRIBUTE_FUEL = "fuel";
    public static final String ATTRIBUTE_NUM_PASS = "num_passengers";

    public final static String ATTRIBUTE_LINK_IDS = "links";
    public final static String ATTRIBUTE_MODE = "mode";
    public final static String ATTRIBUTE_DEPARTURE_TIME = "departure_time";
    public final static String ATTRIBUTE_VEHICLE_ID = "vehicle_id";
    public final static String ATTRIBUTE_VEHICLE_TYPE = "vehicle_type";

    private final VehicleType vehicleType;
    private final String linkIds;
    private final String vehicleId;
    private final String departureTime;
    private final String mode;
    private final Double length;
    private final String fuel;
    private final Integer numPass;

    private final RoutingModel.BeamLeg beamLeg;

    public PathTraversalEvent(double time, Id<Vehicle> vehicleId, VehicleType vehicleType, Integer numPass, RoutingModel.BeamLeg beamLeg) {
        super(time);
        this.vehicleType = vehicleType;
        if (vehicleType.getEngineInformation()!=null && vehicleType.getEngineInformation().getFuelType() != null) {
            fuel=vehicleType.getEngineInformation().getFuelType().name();
        } else{
            fuel="NA";
        }

        this.linkIds = ((IndexedSeq)beamLeg.travelPath().linkIds()).mkString(",");

        this.vehicleId = vehicleId.toString();
        this.departureTime = (new Double(time)).toString();
        this.mode = beamLeg.mode().value();
        this.beamLeg = beamLeg;
        this.length = beamLeg.travelPath().distanceInM();
        this.numPass = numPass;
    }

    @Override
    public Map<String, String> getAttributes() {
        Map<String, String> attr = super.getAttributes();

        attr.put(ATTRIBUTE_VEHICLE_ID, vehicleId);
        attr.put(ATTRIBUTE_VEHICLE_TYPE, vehicleType.getDescription());  // XXXX: Is this really what we need here?
        attr.put(ATTRIBUTE_LENGTH, length.toString());
        attr.put(ATTRIBUTE_NUM_PASS, numPass.toString());

        attr.put(ATTRIBUTE_DEPARTURE_TIME, departureTime);
        attr.put(ATTRIBUTE_MODE, mode);
        attr.put(ATTRIBUTE_LINK_IDS, linkIds);
        attr.put(ATTRIBUTE_FUEL,fuel);

//        attr.put(ATTRIBUTE_VIZ_DATA, beamLeg.asJson.noSpaces)

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
