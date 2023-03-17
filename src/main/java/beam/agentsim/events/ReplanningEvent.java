package beam.agentsim.events;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.internal.HasPersonId;

import java.util.Map;

public class ReplanningEvent extends Event implements HasPersonId {

    public final static String EVENT_TYPE = "Replanning";
    public final static String ATTRIBUTE_PERSON = "person";
    public final static String ATTRIBUTE_REPLANNING_REASON = "reason";
    public final static String ATTRIBUTE_START_COORDINATE_X = "startX";
    public final static String ATTRIBUTE_START_COORDINATE_Y = "startY";
    public final static String ATTRIBUTE_END_COORDINATE_X = "endX";
    public final static String ATTRIBUTE_END_COORDINATE_Y = "endY";

    private final Id<Person> personId;
    private final String reason;

    private final String startX;

    private final String startY;

    private final String endX;

    private final String endY;

    public ReplanningEvent(final double time, final Id<Person> personId, final String reason) {
        super(time);
        this.personId = personId;
        this.reason = reason;
        this.startX = "";
        this.startY = "";
        this.endX = "";
        this.endY = "";
    }

    public ReplanningEvent(final double time,
                           final Id<Person> personId,
                           final String reason,
                           final Double startX,
                           final Double startY,
                           final Double endX,
                           final Double endY) {
        super(time);
        this.personId = personId;
        this.reason = reason;
        this.startX = startX.toString();
        this.startY = startY.toString();
        this.endX = endX.toString();
        this.endY = endY.toString();
    }

    public ReplanningEvent(final double time,
                           final Id<Person> personId,
                           final String reason,
                           final Double startX,
                           final Double startY) {
        super(time);
        this.personId = personId;
        this.reason = reason;
        this.startX = startX.toString();
        this.startY = startY.toString();
        this.endX = "";
        this.endY = "";
    }

    public static ReplanningEvent apply(Event event) {
        if (!(event instanceof ReplanningEvent) && EVENT_TYPE.equalsIgnoreCase(event.getEventType())) {
            Map<String, String> attr = event.getAttributes();
            return new ReplanningEvent(event.getTime(),
                    Id.createPersonId(attr.get(ATTRIBUTE_PERSON)),
                    attr.get(ATTRIBUTE_REPLANNING_REASON),
                    Double.valueOf(attr.get(ATTRIBUTE_START_COORDINATE_X).replaceFirst("^$", "0.0")),
                    Double.valueOf(attr.get(ATTRIBUTE_START_COORDINATE_Y).replaceFirst("^$", "0.0")),
                    Double.valueOf(attr.get(ATTRIBUTE_END_COORDINATE_X).replaceFirst("^$", "0.0")),
                    Double.valueOf(attr.get(ATTRIBUTE_END_COORDINATE_Y).replaceFirst("^$", "0.0"))
            );
        }
        return (ReplanningEvent) event;
    }

    @Override
    public Id<Person> getPersonId() {
        return personId;
    }

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    public String getReason(){ return reason; }

    @Override
    public Map<String, String> getAttributes() {
        Map<String, String> attr = super.getAttributes();
        attr.put(ATTRIBUTE_PERSON, personId.toString());
        attr.put(ATTRIBUTE_REPLANNING_REASON, reason);
        attr.put(ATTRIBUTE_START_COORDINATE_X, startX);
        attr.put(ATTRIBUTE_START_COORDINATE_Y, startY);
        attr.put(ATTRIBUTE_END_COORDINATE_X, endX);
        attr.put(ATTRIBUTE_END_COORDINATE_Y, endY);
        return attr;
    }
}
