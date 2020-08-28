package beam.agentsim.events;

import beam.router.model.EmbodiedBeamTrip;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.internal.HasPersonId;

import java.util.Map;

/**
 * BEAM
 */
public class ModeChoiceEvent extends Event implements HasPersonId {
    public final static String EVENT_TYPE = "ModeChoice";
    public final static String ATTRIBUTE_MODE = "mode";
    public final static String ATTRIBUTE_CURRENT_TOUR_MODE = "currentTourMode";
    public final static String ATTRIBUTE_PERSON_ID = "person";
    //    public final static String VERBOSE_ATTRIBUTE_EXP_MAX_UTILITY = "expectedMaximumUtility";
//    public final static String VERBOSE_ATTRIBUTE_LOCATION = "location";
    public final static String ATTRIBUTE_EXP_MAX_UTILITY = "expectedMaximumUtility";
    public final static String ATTRIBUTE_AVAILABLE_ALTERNATIVES = "availableAlternatives";
    public final static String ATTRIBUTE_LOCATION = "location";
    public final static String ATTRIBUTE_PERSONAL_VEH_AVAILABLE = "personalVehicleAvailable";
    public final static String ATTRIBUTE_TRIP_LENGTH = "length";
    public final static String ATTRIBUTE_TOUR_INDEX = "tourIndex";
    public final EmbodiedBeamTrip chosenTrip;
    public final Id<Person> personId;
    public final String mode;
    public final String currentTourMode;
    public final String expectedMaxUtility;
    public final String location;
    public final String availableAlternatives;
    public final String vehAvailable;
    public final Double length;
    public final Integer tourIndex;

    public ModeChoiceEvent(double time, Id<Person> personId, String chosenMode, String currentTourMode, Double expectedMaxUtility,
                           String linkId, String availableAlternatives, Boolean vehAvailable, Double length,
                           Integer tourIndex, EmbodiedBeamTrip chosenTrip) {
        super(time);

        this.personId = personId;
        this.mode = chosenMode;
        this.currentTourMode = currentTourMode;
        this.expectedMaxUtility = expectedMaxUtility.toString();
        this.location = linkId;
        this.availableAlternatives = availableAlternatives;
        this.vehAvailable = vehAvailable == null ? "" : vehAvailable.toString();
        this.length = length;
        this.tourIndex = tourIndex;
        this.chosenTrip = chosenTrip;
    }

    public static ModeChoiceEvent apply(Event event) {
        if (!(event instanceof ModeChoiceEvent) && EVENT_TYPE.equalsIgnoreCase(event.getEventType())) {
            Map<String, String> attr = event.getAttributes();
            return new ModeChoiceEvent(event.getTime(),
                    Id.createPersonId(attr.get(ATTRIBUTE_PERSON_ID)),
                    attr.get(ATTRIBUTE_MODE),
                    attr.get(ATTRIBUTE_CURRENT_TOUR_MODE),
                    Double.parseDouble(attr.get(ATTRIBUTE_EXP_MAX_UTILITY)),
                    attr.get(ATTRIBUTE_LOCATION),
                    attr.get(ATTRIBUTE_AVAILABLE_ALTERNATIVES),
                    Boolean.parseBoolean(attr.get(ATTRIBUTE_PERSONAL_VEH_AVAILABLE)),
                    Double.parseDouble(attr.get(ATTRIBUTE_TRIP_LENGTH)),
                    Integer.parseInt(attr.get(ATTRIBUTE_TOUR_INDEX)),
                    null
            );
        }
        return (ModeChoiceEvent) event;
    }

    @Override
    public Map<String, String> getAttributes() {
        Map<String, String> attr = super.getAttributes();
        attr.put(ATTRIBUTE_PERSON_ID, personId.toString());
        attr.put(ATTRIBUTE_MODE, mode);
        attr.put(ATTRIBUTE_CURRENT_TOUR_MODE, currentTourMode);
        attr.put(ATTRIBUTE_EXP_MAX_UTILITY, expectedMaxUtility);
        attr.put(ATTRIBUTE_LOCATION, location);
        attr.put(ATTRIBUTE_AVAILABLE_ALTERNATIVES, availableAlternatives);
        attr.put(ATTRIBUTE_PERSONAL_VEH_AVAILABLE, vehAvailable);
        attr.put(ATTRIBUTE_TRIP_LENGTH, length.toString());
        attr.put(ATTRIBUTE_TOUR_INDEX, tourIndex.toString());
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

    @Override
    public Id<Person> getPersonId() {
        return personId;
    }

    public String getMode() { return mode; }

}
