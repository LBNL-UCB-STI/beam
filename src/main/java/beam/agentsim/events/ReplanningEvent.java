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

    private final Id<Person> personId;
    private final String reason;

    public ReplanningEvent(final double time, final Id<Person> personId, final String reason) {
        super(time);
        this.personId = personId;
        this.reason = reason;
    }

    public static ReplanningEvent apply(Event event) {
        if (!(event instanceof ReplanningEvent) && EVENT_TYPE.equalsIgnoreCase(event.getEventType())) {
            Map<String, String> attr = event.getAttributes();
            return new ReplanningEvent(event.getTime(),
                    Id.createPersonId(attr.get(ATTRIBUTE_PERSON)),
                    attr.get(ATTRIBUTE_REPLANNING_REASON)
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
        return attr;
    }
}
