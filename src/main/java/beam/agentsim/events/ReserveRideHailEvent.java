package beam.agentsim.events;

import beam.agentsim.agents.rideHail.RideHailingManager;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.internal.HasPersonId;

import java.util.Map;

/**
 * BEAM
 */
public class ReserveRideHailEvent extends Event implements HasPersonId {
    public final static String EVENT_TYPE = "ReserveRideHail";
    public final static String ATTRIBUTE_PERSON_ID = "customerId";
    public final static String ATTRIBUTE_DEPART_TIME = "departTime";
    public final static String ATTRIBUTE_PICKUP_LOCATION_X = "originY";
    public final static String ATTRIBUTE_PICKUP_LOCATION_Y = "originY";
    public final static String ATTRIBUTE_DROPOUT_LOCATION_X = "destinationX";
    public final static String ATTRIBUTE_DROPOUT_LOCATION_Y = "destinationY";

    private final Id<Person> customerId;
    private final long departTime;
    private final double originX;
    private final double originY;
    private final double destinationX;
    private final double destinationY;

    public ReserveRideHailEvent(double time, RideHailingManager.RideHailingRequest rideHailingRequest) {
        this(time, rideHailingRequest.customer().personId(), (long) rideHailingRequest.departAt().atTime(),
                rideHailingRequest.pickUpLocation().getX(), rideHailingRequest.pickUpLocation().getX(),
                rideHailingRequest.destination().getX(), rideHailingRequest.destination().getX());
    }


    public ReserveRideHailEvent(double time, Id<Person> personId, long departTime, double originX,
                                double originY, double destinationX, double destinationY) {
        super(time);

        this.customerId = personId;
        this.departTime = departTime;
        this.originX = originX;
        this.originY = originY;
        this.destinationX = destinationX;
        this.destinationY = destinationY;
    }

    @Override
    public Map<String, String> getAttributes() {
        Map<String, String> attr = super.getAttributes();

        attr.put(ATTRIBUTE_PERSON_ID, customerId.toString());
        attr.put(ATTRIBUTE_DEPART_TIME, Long.toString(departTime));
        attr.put(ATTRIBUTE_PICKUP_LOCATION_X, Double.toString(originX));
        attr.put(ATTRIBUTE_PICKUP_LOCATION_Y, Double.toString(originY));
        attr.put(ATTRIBUTE_DROPOUT_LOCATION_X, Double.toString(destinationX));
        attr.put(ATTRIBUTE_DROPOUT_LOCATION_Y, Double.toString(destinationY));

        return attr;
    }

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    @Override
    public Id<Person> getPersonId() {
        return customerId;
    }

    public static ReserveRideHailEvent apply(Event event) {
        if (!(event instanceof ReserveRideHailEvent) && EVENT_TYPE.equalsIgnoreCase(event.getEventType())) {
            return new ReserveRideHailEvent(event.getTime(),
                    Id.createPersonId(event.getAttributes().get(ATTRIBUTE_PERSON_ID)),
                    Id.createVehicleId(event.getAttributes().get(ATTRIBUTE_VEHICLE_ID)),
                    Long.parseLong(event.getAttributes().get(ATTRIBUTE_DEPART_TIME)),
                    Double.parseDouble(event.getAttributes().get(ATTRIBUTE_PICKUP_LOCATION_X)),
                    Double.parseDouble(event.getAttributes().get(ATTRIBUTE_PICKUP_LOCATION_Y)),
                    Double.parseDouble(event.getAttributes().get(ATTRIBUTE_DROPOUT_LOCATION_X)),
                    Double.parseDouble(event.getAttributes().get(ATTRIBUTE_DROPOUT_LOCATION_Y))
            );
        }
        return (ReserveRideHailEvent) event;
    }
}
