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
//    public final static String VERBOSE_ATTRIBUTE_EXP_MAX_UTILITY = "expectedMaximumUtility";
//    public final static String VERBOSE_ATTRIBUTE_LOCATION = "location";
    public final static String ATTRIBUTE_EXP_MAX_UTILITY = "expectedMaximumUtility";
    public final static String ATTRIBUTE_LOCATION = "location";
    private final String personId;
    private final String mode;
    private final String expectedMaxUtility;
    private final String location;

    public ModeChoiceEvent(double time, Id<Person> personId, String chosenMode) {
        this(time, personId, chosenMode, Double.NaN, "");
    }
    public ModeChoiceEvent(double time, Id<Person> personId, String chosenMode, Double expectedMaxUtility, String linkId) {
        super(time);

        this.personId = personId.toString();
        this.mode = chosenMode;
        this.expectedMaxUtility = expectedMaxUtility.toString();
        this.location = linkId;
    }

    @Override
    public Map<String, String> getAttributes() {
        Map<String, String> attr = super.getAttributes();

        attr.put(ATTRIBUTE_PERSON_ID, personId);
        attr.put(ATTRIBUTE_MODE, mode);
        attr.put(ATTRIBUTE_EXP_MAX_UTILITY, expectedMaxUtility);
        attr.put(ATTRIBUTE_LOCATION, location);

        return attr;
    }

    public Map<String, String> getVerboseAttributes() {
        Map<String, String> attr = getAttributes();
        attr.put(ATTRIBUTE_EXP_MAX_UTILITY, expectedMaxUtility);
        attr.put(ATTRIBUTE_LOCATION, location);
        return attr;
    }


    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }
}
