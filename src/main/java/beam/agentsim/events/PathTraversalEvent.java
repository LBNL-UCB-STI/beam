package beam.agentsim.events;

import beam.router.RoutingModel;
import org.matsim.api.core.v01.Coord;
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
    public final static String ATTRIBUTE_ARRIVAL_TIME = "arrival_time";
    public final static String ATTRIBUTE_VEHICLE_ID = "vehicle_id";
    public final static String ATTRIBUTE_VEHICLE_TYPE = "vehicle_type";
    public final static String ATTRIBUTE_VEHICLE_CAPACITY = "capacity";
//    public final static String VERBOSE_ATTRIBUTE_START_COORDINATE_X = "start.x";
//    public final static String VERBOSE_ATTRIBUTE_START_COORDINATE_Y = "start.y";
//    public final static String VERBOSE_ATTRIBUTE_END_COORDINATE_X = "end.x";
//    public final static String VERBOSE_ATTRIBUTE_END_COORDINATE_Y = "end.y";
    public final static String ATTRIBUTE_START_COORDINATE_X = "start.x";
    public final static String ATTRIBUTE_START_COORDINATE_Y = "start.y";
    public final static String ATTRIBUTE_END_COORDINATE_X = "end.x";
    public final static String ATTRIBUTE_END_COORDINATE_Y = "end.y";

    private final VehicleType vehicleType;
    private final String linkIds;
    private final String vehicleId;
    private final String departureTime, arrivalTime;
    private final String mode;
    private final Double length;
    private final String fuel;
    private final Integer numPass;
    private final Integer capacity;
    private final Coord startCoord;
    private final Coord endCoord;

    private final RoutingModel.BeamLeg beamLeg;

    public PathTraversalEvent(double time, Id<Vehicle> vehicleId, VehicleType vehicleType, Integer numPass, RoutingModel.BeamLeg beamLeg, Coord startCoord, Coord endCoord) {
        super(time);
        this.vehicleType = vehicleType;

        this.linkIds = ((IndexedSeq)beamLeg.travelPath().linkIds()).mkString(",");

        this.vehicleId = vehicleId.toString();
        this.departureTime = (new Double(time)).toString();
        this.arrivalTime = (new Double(time + beamLeg.duration())).toString();
        this.mode = beamLeg.mode().value();
        this.beamLeg = beamLeg;
        this.length = beamLeg.travelPath().distanceInM();
        this.numPass = numPass;
        if (vehicleType.getCapacity()!=null) {
            this.capacity = vehicleType.getCapacity().getSeats() + vehicleType.getCapacity().getStandingRoom();
        }else{
            this.capacity = 0;
        }
        this.startCoord=startCoord;
        this.endCoord=endCoord;
        if (vehicleType.getEngineInformation()!=null){
            fuel=new Double(vehicleType.getEngineInformation().getGasConsumption() * this.length).toString();
        } else{
            fuel="NA";
        }
    }

    @Override
    public Map<String, String> getAttributes() {
        Map<String, String> attr = super.getAttributes();

        attr.put(ATTRIBUTE_VEHICLE_ID, vehicleId);
        attr.put(ATTRIBUTE_VEHICLE_TYPE, vehicleType.getDescription());  // XXXX: Is this really what we need here?
        attr.put(ATTRIBUTE_LENGTH, length.toString());
        attr.put(ATTRIBUTE_NUM_PASS, numPass.toString());

        attr.put(ATTRIBUTE_DEPARTURE_TIME, departureTime);
        attr.put(ATTRIBUTE_ARRIVAL_TIME, arrivalTime);
        attr.put(ATTRIBUTE_MODE, mode);
        attr.put(ATTRIBUTE_LINK_IDS, linkIds);
        attr.put(ATTRIBUTE_FUEL,fuel);
        attr.put(ATTRIBUTE_VEHICLE_CAPACITY,capacity.toString());

        if (startCoord!=null){
            attr.put(ATTRIBUTE_START_COORDINATE_X,Double.toString(startCoord.getX()));
            attr.put(ATTRIBUTE_START_COORDINATE_Y,Double.toString(startCoord.getY()));
        } else {
            attr.put(ATTRIBUTE_START_COORDINATE_X,"");
            attr.put(ATTRIBUTE_START_COORDINATE_Y,"");
        }


        if (endCoord!=null){
            attr.put(ATTRIBUTE_END_COORDINATE_X,Double.toString(endCoord.getX()));
            attr.put(ATTRIBUTE_END_COORDINATE_Y,Double.toString(endCoord.getY()));
        } else {
            attr.put(ATTRIBUTE_END_COORDINATE_X,"");
            attr.put(ATTRIBUTE_END_COORDINATE_Y,"");
        }

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
