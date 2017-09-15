package beam.agentsim.events;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Person;

import java.util.Map;

/**
 * BEAM
 */
public class ModeChoiceEvent extends Event {
    public final static String EVENT_TYPE = "ModeChoice";
    public final static String ATTRIBUTE_MODE = "mode";
    public final static String ATTRIBUTE_PERSON_ID = "person";
    public final static String VERBOSE_ATTRIBUTE_ALTERNATIVES = "alternatives";
    private final String personId;
    private final String mode;
    public final String alternatives;

    public ModeChoiceEvent(double time, Id<Person> personId, String chosenMode) {
        super(time);

        this.personId = personId.toString();
        this.mode = chosenMode;
        this.alternatives = "dummy";
    }

    @Override
    public Map<String, String> getAttributes() {
        Map<String, String> attr = super.getAttributes();

        attr.put(ATTRIBUTE_PERSON_ID, personId);
        attr.put(ATTRIBUTE_MODE, mode);

        return attr;
    }


    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }
}
