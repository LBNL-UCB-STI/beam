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

    public static final String ATTRIBUTE_LENGTH = "length";
    public static final String ATTRIBUTE_FUEL = "fuel";
    public static final String ATTRIBUTE_NUM_PASS = "num_passengers";

    public final static String ATTRIBUTE_LINK_IDS = "links";
    public final static String ATTRIBUTE_MODE = "mode";
    public final static String ATTRIBUTE_DEPARTURE_TIME = "departure_time";
    public final static String ATTRIBUTE_ARRIVAL_TIME = "arrival_time";
    public final static String ATTRIBUTE_VEHICLE_ID = "vehicle_id";
    public final static String ATTRIBUTE_VEHICLE_TYPE = "vehicle_type";
    public final static String ATTRIBUTE_VEHICLE_CAPACITY = "capacity";
    public final static String ATTRIBUTE_START_COORDINATE_X = "start.x";
    public final static String ATTRIBUTE_START_COORDINATE_Y = "start.y";
    public final static String ATTRIBUTE_END_COORDINATE_X = "end.x";
    public final static String ATTRIBUTE_END_COORDINATE_Y = "end.y";
    public final static String ATTRIBUTE_END_LEG_FUEL_LEVEL = "end_leg_fuel_level";

    private final VehicleType vehicleType;
    private final String vehicleId;
    private final String mode;
    private final String fuel;
    private final Integer numPass;
    private final Integer capacity;
    private final double endLegFuelLevel;

    private final RoutingModel.BeamLeg beamLeg;

    public PathTraversalEvent(double time, Id<Vehicle> vehicleId, VehicleType vehicleType, Integer numPass, RoutingModel.BeamLeg beamLeg, double endLegFuelLevel) {
        super(time);
        this.vehicleType = vehicleType;
        this.vehicleId = vehicleId.toString();
        this.mode = beamLeg.mode().value();
        this.beamLeg = beamLeg;
        this.numPass = numPass;
        this.endLegFuelLevel = endLegFuelLevel;
        if (vehicleType.getCapacity()!=null) {
            this.capacity = vehicleType.getCapacity().getSeats() + vehicleType.getCapacity().getStandingRoom();
        }else{
            this.capacity = 0;
        }
        if (vehicleType.getEngineInformation()!=null){
            fuel= Double.toString(vehicleType.getEngineInformation().getGasConsumption() * beamLeg.travelPath().distanceInM());
        } else{
            fuel="NA";
        }
    }

    @Override
    public Map<String, String> getAttributes() {
        Map<String, String> attr = super.getAttributes();

        attr.put(ATTRIBUTE_VEHICLE_ID, vehicleId);
        attr.put(ATTRIBUTE_VEHICLE_TYPE, vehicleType.getDescription());  // XXXX: Is this really what we need here?
        attr.put(ATTRIBUTE_LENGTH, Double.toString(beamLeg.travelPath().distanceInM()));
        attr.put(ATTRIBUTE_NUM_PASS, numPass.toString());

        attr.put(ATTRIBUTE_DEPARTURE_TIME, Long.toString(beamLeg.startTime()));
        attr.put(ATTRIBUTE_ARRIVAL_TIME, Long.toString(beamLeg.endTime()));
        attr.put(ATTRIBUTE_MODE, mode);
        attr.put(ATTRIBUTE_LINK_IDS, ((IndexedSeq)beamLeg.travelPath().linkIds()).mkString(","));
        attr.put(ATTRIBUTE_FUEL,fuel);
        attr.put(ATTRIBUTE_VEHICLE_CAPACITY,capacity.toString());

        attr.put(ATTRIBUTE_START_COORDINATE_X, Double.toString(beamLeg.travelPath().startPoint().loc().getX()));
        attr.put(ATTRIBUTE_START_COORDINATE_Y, Double.toString(beamLeg.travelPath().startPoint().loc().getY()));
        attr.put(ATTRIBUTE_END_COORDINATE_X, Double.toString(beamLeg.travelPath().endPoint().loc().getX()));
        attr.put(ATTRIBUTE_END_COORDINATE_Y, Double.toString(beamLeg.travelPath().endPoint().loc().getY()));
        attr.put(ATTRIBUTE_END_LEG_FUEL_LEVEL, Double.toString(endLegFuelLevel));
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
