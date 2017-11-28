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
    public final static String ATTRIBUTE_AVAILABLE_ALTERNATIVES = "availableAlternatives";
    public final static String ATTRIBUTE_LOCATION = "location";
    public final static String ATTRIBUTE_PERSONAL_VEH_AVAILABLE = "personalVehicleAvailable";
    public final static String ATTRIBUTE_TRIP_LENGTH= "length";
    private final String personId;
    private final String mode;
    private final String expectedMaxUtility;
    private final String location;
    private final String availableAlternatives;
    private final String vehAvailable;
    private final Double length;

    public ModeChoiceEvent(double time, Id<Person> personId, String chosenMode, Double expectedMaxUtility,
                           String linkId, String availableAlternatives, Boolean vehAvailable, Double length) {
        super(time);

        this.personId = personId.toString();
        this.mode = chosenMode;
        this.expectedMaxUtility = expectedMaxUtility.toString();
        this.location = linkId;
        this.availableAlternatives = availableAlternatives;
        this.vehAvailable = vehAvailable == null ? "" : vehAvailable.toString();
        this.length = length;
    }

    @Override
    public Map<String, String> getAttributes() {
        Map<String, String> attr = super.getAttributes();

        attr.put(ATTRIBUTE_PERSON_ID, personId);
        attr.put(ATTRIBUTE_MODE, mode);
        attr.put(ATTRIBUTE_EXP_MAX_UTILITY, expectedMaxUtility);
        attr.put(ATTRIBUTE_LOCATION, location);
        attr.put(ATTRIBUTE_AVAILABLE_ALTERNATIVES, availableAlternatives);
        attr.put(ATTRIBUTE_PERSONAL_VEH_AVAILABLE, vehAvailable);
        attr.put(ATTRIBUTE_TRIP_LENGTH, length.toString());

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
