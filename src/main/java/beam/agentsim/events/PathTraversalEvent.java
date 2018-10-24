package beam.agentsim.events;

import beam.agentsim.agents.vehicles.BeamVehicleType;
import beam.router.model.BeamLeg;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.vehicles.Vehicle;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
    public final static String ATTRIBUTE_VEHICLE_ID = "vehicle";
    public final static String ATTRIBUTE_VEHICLE_TYPE = "vehicle_type";
    public final static String ATTRIBUTE_VEHICLE_CAPACITY = "capacity";
    public final static String ATTRIBUTE_START_COORDINATE_X = "start.x";
    public final static String ATTRIBUTE_START_COORDINATE_Y = "start.y";
    public final static String ATTRIBUTE_END_COORDINATE_X = "end.x";
    public final static String ATTRIBUTE_END_COORDINATE_Y = "end.y";
    public final static String ATTRIBUTE_END_LEG_FUEL_LEVEL = "end_leg_fuel_level";

    private final AtomicReference<Map<String, String>> attributes;

    private final String vehicleType;
    private final String vehicleId;
    private final String mode;
    private final Double fuel;
    private final Integer numPass;
    private final Integer capacity;
    private final double endLegFuelLevel;
    private final double legLength;
    private final String linkIds;
    private final long departureTime;
    private final long arrivalTime;
    private final double startX;
    private final double startY;
    private final double endX;
    private final double endY;

    public PathTraversalEvent(double time, Id<Vehicle> vehicleId, BeamVehicleType vehicleType, Integer numPass, BeamLeg beamLeg, double fuelConsumed, double endLegFuelLevel) {
        this(time, vehicleId, vehicleType.vehicleTypeId(), beamLeg.mode().value(), numPass, endLegFuelLevel,
                (int)(vehicleType.seatingCapacity()  + vehicleType.standingRoomCapacity()),
                fuelConsumed,
                beamLeg.travelPath().distanceInM(), beamLeg.travelPath().linkIds().mkString(","), beamLeg.startTime(), beamLeg.endTime(),
                beamLeg.travelPath().startPoint().loc().getX(), beamLeg.travelPath().startPoint().loc().getY(), beamLeg.travelPath().endPoint().loc().getX(),
                beamLeg.travelPath().endPoint().loc().getY());
    }

    public PathTraversalEvent(double time, Id<Vehicle> vehicleId, String vehicleType, String mode, Integer numPass, double endLegFuelLevel, int capacity, double fuel,
                              double legLength, String linkIds, long departureTime, long arrivalTime, double startX, double startY, double endX,
                              double endY) {
        super(time);
        this.vehicleType = vehicleType;
        this.vehicleId = vehicleId.toString();
        this.mode = mode;
        this.numPass = numPass;
        this.endLegFuelLevel = endLegFuelLevel;
        this.capacity = capacity;
        this.fuel = fuel;
        this.legLength = legLength;
        this.linkIds = linkIds;
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
        this.startX = startX;
        this.startY = startY;
        this.endX = endX;
        this.endY = endY;
        this.attributes = new AtomicReference<>(Collections.emptyMap());
    }

    public static PathTraversalEvent apply(Event event) {
        if (!(event instanceof PathTraversalEvent) && EVENT_TYPE.equalsIgnoreCase(event.getEventType())) {
            Map<String, String> attr = event.getAttributes();
            return new PathTraversalEvent(event.getTime(),
                    Id.createVehicleId(attr.get(ATTRIBUTE_VEHICLE_ID)),
                    attr.get(ATTRIBUTE_VEHICLE_TYPE),
                    attr.get(ATTRIBUTE_MODE),
                    Integer.parseInt(attr.get(ATTRIBUTE_NUM_PASS)),
                    Double.parseDouble(attr.getOrDefault(ATTRIBUTE_END_LEG_FUEL_LEVEL, "0")),
                    Integer.parseInt(attr.get(ATTRIBUTE_VEHICLE_CAPACITY)),
                    Double.parseDouble(attr.get(ATTRIBUTE_FUEL)),
                    Double.parseDouble(attr.get(ATTRIBUTE_LENGTH)),
                    attr.get(ATTRIBUTE_LINK_IDS),
                    Long.parseLong(attr.get(ATTRIBUTE_DEPARTURE_TIME)),
                    Long.parseLong(attr.get(ATTRIBUTE_ARRIVAL_TIME)),
                    Double.parseDouble(attr.get(ATTRIBUTE_START_COORDINATE_X)),
                    Double.parseDouble(attr.get(ATTRIBUTE_START_COORDINATE_Y)),
                    Double.parseDouble(attr.get(ATTRIBUTE_END_COORDINATE_X)),
                    Double.parseDouble(attr.get(ATTRIBUTE_START_COORDINATE_Y))
            );
        }
        return (PathTraversalEvent) event;
    }

    @Override
    public Map<String, String> getAttributes() {
        Map<String, String> attr = attributes.get();
        if (attr != Collections.EMPTY_MAP) return attr;

        attr = super.getAttributes();

        attr.put(ATTRIBUTE_VEHICLE_ID, vehicleId);
        attr.put(ATTRIBUTE_VEHICLE_TYPE, vehicleType);
        attr.put(ATTRIBUTE_LENGTH, Double.toString(legLength));
        attr.put(ATTRIBUTE_NUM_PASS, numPass.toString());

        attr.put(ATTRIBUTE_DEPARTURE_TIME, Long.toString(departureTime));
        attr.put(ATTRIBUTE_ARRIVAL_TIME, Long.toString(arrivalTime));
        attr.put(ATTRIBUTE_MODE, mode);
        attr.put(ATTRIBUTE_LINK_IDS, linkIds);
        attr.put(ATTRIBUTE_FUEL, fuel.toString());
        attr.put(ATTRIBUTE_VEHICLE_CAPACITY, capacity.toString());

        attr.put(ATTRIBUTE_START_COORDINATE_X, Double.toString(startX));
        attr.put(ATTRIBUTE_START_COORDINATE_Y, Double.toString(startY));
        attr.put(ATTRIBUTE_END_COORDINATE_X, Double.toString(endX));
        attr.put(ATTRIBUTE_END_COORDINATE_Y, Double.toString(endY));
        attr.put(ATTRIBUTE_END_LEG_FUEL_LEVEL, Double.toString(endLegFuelLevel));

        attributes.set(attr);

        return attr;
    }

    public String getVehicleId() {
        return this.vehicleId;
    }

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }
}
