package beam.agentsim.events;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.internal.HasPersonId;

import java.util.Map;

public class ReplanningEvent extends Event implements HasPersonId {

    public final static String EVENT_TYPE = "Replanning";
    public final static String ATTRIBUTE_PERSON = "person";

    private final Id<Person> personId;
    private Map<String, String> attr;

    public ReplanningEvent(final double time, final Id<Person> personId) {
        super(time);
        this.personId = personId;
    }

    public static ReplanningEvent apply(Event event) {
        if (!(event instanceof ReplanningEvent) && EVENT_TYPE.equalsIgnoreCase(event.getEventType())) {
            Map<String, String> attr = event.getAttributes();
            return new ReplanningEvent(event.getTime(), Id.createPersonId(attr.get(ATTRIBUTE_PERSON)));
        }
        return (ReplanningEvent) event;
    }

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    @Override
    public Id<Person> getPersonId() {
        return personId;
    }

    @Override
    public Map<String, String> getAttributes() {
        if (attr != null) return attr;

        attr = super.getAttributes();

        attr.put(ATTRIBUTE_PERSON, personId.toString());
        return attr;
    }
}
