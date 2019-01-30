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
    public static final String ATTRIBUTE_FUEL_TYPE = "fuelType";
    public static final String ATTRIBUTE_FUEL = "fuel";
    public static final String ATTRIBUTE_NUM_PASS = "numPassengers";

    public final static String ATTRIBUTE_LINK_IDS = "links";
    public final static String ATTRIBUTE_MODE = "mode";
    public final static String ATTRIBUTE_DEPARTURE_TIME = "departureTime";
    public final static String ATTRIBUTE_ARRIVAL_TIME = "arrivalTime";
    public final static String ATTRIBUTE_VEHICLE_ID = "vehicle";
    public final static String ATTRIBUTE_DRIVER_ID = "driver";
    public final static String ATTRIBUTE_VEHICLE_TYPE = "vehicleType";
    public final static String ATTRIBUTE_VEHICLE_CAPACITY = "capacity";
    public final static String ATTRIBUTE_START_COORDINATE_X = "startX";
    public final static String ATTRIBUTE_START_COORDINATE_Y = "startY";
    public final static String ATTRIBUTE_END_COORDINATE_X = "endX";
    public final static String ATTRIBUTE_END_COORDINATE_Y = "endY";
    public final static String ATTRIBUTE_END_LEG_FUEL_LEVEL = "endLegFuelLevel";
    public final static String ATTRIBUTE_TOLL_PAID = "tollPaid";
    public final static String ATTRIBUTE_SEATING_CAPACITY = "seatingCapacity";

    private final AtomicReference<Map<String, String>> attributes;

    private final String vehicleType;
    private final String vehicleId;
    private final String driverId;
    private final String mode;
    private final String fuelType;
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
    private final Integer seatingCapacity;
    private final double amountPaid;

    private String linkTravelTimes;

    public PathTraversalEvent(double time, Id<Vehicle> vehicleId, String driverId, BeamVehicleType vehicleType, Integer numPass, BeamLeg beamLeg, double fuelConsumed, double endLegFuelLevel, double amountPaid) {
        this(time, vehicleId, driverId,  vehicleType.id().toString(), beamLeg.mode().value(), numPass, endLegFuelLevel,
                (int)(vehicleType.seatingCapacity()  + vehicleType.standingRoomCapacity()),
                (vehicleType.primaryFuelType() == null) ? "" : vehicleType.primaryFuelType().toString(), fuelConsumed,
                beamLeg.travelPath().distanceInM(), beamLeg.travelPath().linkIds().mkString(","), beamLeg.travelPath().linkTravelTime().mkString(","), beamLeg.startTime(), beamLeg.endTime(),
                beamLeg.travelPath().startPoint().loc().getX(), beamLeg.travelPath().startPoint().loc().getY(), beamLeg.travelPath().endPoint().loc().getX(),
                beamLeg.travelPath().endPoint().loc().getY(),(int)vehicleType.seatingCapacity(),
                amountPaid);
    }

    public PathTraversalEvent(double time, Id<Vehicle> vehicleId,String driverId, String vehicleType, String mode, Integer numPass, double endLegFuelLevel, int capacity, String fuelType, double fuel,
                              double legLength, String linkIds, String linkTravelTimes, long departureTime, long arrivalTime, double startX, double startY, double endX,
                              double endY, int seatingCapacity, double amountPaid) {
        super(time);
        this.vehicleType = vehicleType;
        this.vehicleId = vehicleId.toString();
        this.driverId = driverId;
        this.mode = mode;
        this.numPass = numPass;
        this.endLegFuelLevel = endLegFuelLevel;
        this.capacity = capacity;
        this.fuelType = fuelType;
        this.fuel = fuel;
        this.legLength = legLength;
        this.linkIds = linkIds;
        this.linkTravelTimes = linkTravelTimes;
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
        this.startX = startX;
        this.startY = startY;
        this.endX = endX;
        this.endY = endY;
        this.attributes = new AtomicReference<>(Collections.emptyMap());
        this.seatingCapacity = seatingCapacity;
        this.amountPaid = amountPaid;
    }

    @Override
    public Map<String, String> getAttributes() {
        Map<String, String> attr = attributes.get();
        if (attr != Collections.EMPTY_MAP) return attr;

        attr = super.getAttributes();

        attr.put(ATTRIBUTE_VEHICLE_ID, vehicleId);
        attr.put(ATTRIBUTE_DRIVER_ID, driverId);
        attr.put(ATTRIBUTE_VEHICLE_TYPE, vehicleType);
        attr.put(ATTRIBUTE_LENGTH, Double.toString(legLength));
        attr.put(ATTRIBUTE_NUM_PASS, numPass.toString());

        attr.put(ATTRIBUTE_DEPARTURE_TIME, Long.toString(departureTime));
        attr.put(ATTRIBUTE_ARRIVAL_TIME, Long.toString(arrivalTime));
        attr.put(ATTRIBUTE_MODE, mode);
        attr.put(ATTRIBUTE_LINK_IDS, linkIds);
        attr.put(ATTRIBUTE_FUEL_TYPE, fuelType);
        attr.put(ATTRIBUTE_FUEL, fuel.toString());
        attr.put(ATTRIBUTE_VEHICLE_CAPACITY, capacity.toString());

        attr.put(ATTRIBUTE_START_COORDINATE_X, Double.toString(startX));
        attr.put(ATTRIBUTE_START_COORDINATE_Y, Double.toString(startY));
        attr.put(ATTRIBUTE_END_COORDINATE_X, Double.toString(endX));
        attr.put(ATTRIBUTE_END_COORDINATE_Y, Double.toString(endY));
        attr.put(ATTRIBUTE_END_LEG_FUEL_LEVEL, Double.toString(endLegFuelLevel));
        attr.put(ATTRIBUTE_SEATING_CAPACITY, Integer.toString(seatingCapacity));
        attr.put(ATTRIBUTE_TOLL_PAID, Double.toString(amountPaid));

        attributes.set(attr);

        return attr;
    }

    public String getVehicleId() {
        return this.vehicleId;
    }

    public String getLinkTravelTimes() {
        return this.linkTravelTimes;
    }

    public long getDepartureTime() {
        return departureTime;
    }

    public long getArrivalTime() {
        return arrivalTime;
    }

    public String getVehicleType() {
        return vehicleType;
    }

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }
}
