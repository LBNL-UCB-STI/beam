package beam.agentsim.events;

import org.matsim.api.core.v01.Coord;
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
    public final static String ATTRIBUTE_PERSON_ID = "person";
    public final static String ATTRIBUTE_DEPART_TIME = "departTime";
    public final static String ATTRIBUTE_PICKUP_LOCATION_X = "startX";
    public final static String ATTRIBUTE_PICKUP_LOCATION_Y = "startY";
    public final static String ATTRIBUTE_DROPOUT_LOCATION_X = "endX";
    public final static String ATTRIBUTE_DROPOUT_LOCATION_Y = "endY";

    public final Id<Person> customerId;
    public final long departTime;
    public final double originX;
    public final double originY;
    public final double destinationX;
    public final double destinationY;

    public ReserveRideHailEvent(double time, Id<Person> personId, long departTime, Coord pickUpLocation, Coord dropOutLocation) {
        this(time,
                personId,
                departTime,
                pickUpLocation.getX(),
                pickUpLocation.getY(),
                dropOutLocation.getX(),
                dropOutLocation.getY());
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

    public static ReserveRideHailEvent apply(Event event) {
        if (!(event instanceof ReserveRideHailEvent) && EVENT_TYPE.equalsIgnoreCase(event.getEventType())) {
            Map<String, String> attr = event.getAttributes();
            return new ReserveRideHailEvent(event.getTime(),
                    Id.createPersonId(attr.get(ATTRIBUTE_PERSON_ID)),
                    Long.parseLong(attr.get(ATTRIBUTE_DEPART_TIME)),
                    Double.parseDouble(attr.get(ATTRIBUTE_PICKUP_LOCATION_X)),
                    Double.parseDouble(attr.get(ATTRIBUTE_PICKUP_LOCATION_Y)),
                    Double.parseDouble(attr.get(ATTRIBUTE_DROPOUT_LOCATION_X)),
                    Double.parseDouble(attr.get(ATTRIBUTE_DROPOUT_LOCATION_Y))
            );
        }
        return (ReserveRideHailEvent) event;
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
}
