package beam.agentsim.events;

import beam.agentsim.agents.modalbehaviors.DrivesVehicle;
import beam.agentsim.agents.planning.Tour;
import beam.router.Modes;
import beam.router.TourModes;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.internal.HasPersonId;
import scala.collection.Seq;
import scala.collection.immutable.Vector;

import java.util.Map;

/**
 * BEAM
 */
public class TourModeChoiceEvent extends Event implements HasPersonId {
    public final static String EVENT_TYPE = "TourModeChoice";
    public final static String ATTRIBUTE_PERSON_ID = "person";

    public final static String ATTRIBUTE_TOUR_MODE = "tourMode";
    public final static String ATTRIBUTE_MODE_TO_TOUR_MODE = "modeToTourMode";
    public final static String ATTRIBUTE_AVAILABLE_VEHICLES = "availableVehicles";
    public final static String ATTRIBUTE_TOUR_ACTIVITIES = "tourActivities";
    public final static String ATTRIBUTE_TOUR_TRIP_IDS = "tourTripIDs";
    public final static String ATTRIBUTE_AVAILABLE_MODES = "availableModes";
    public final static String ATTRIBUTE_TOUR_MODE_UTILITY = "tourModeUtility";
    public final static String ATTRIBUTE_CURRENT_ACTIVITY = "startActivity";
    public final static String ATTRIBUTE_CURRENT_ACTIVITY_X = "startX";
    public final static String ATTRIBUTE_CURRENT_ACTIVITY_Y = "startY";

    public final Id<Person> personId;
    public final String tourMode;
    public final Tour currentTour;
    public final String modeToTourModeString;
    public final String availablePersonalStreetVehiclesString;
    public final String tourActivitiesString;
    public final String tourTripIdsString;
    public final String availableModesString;
    public final String tourModeToUtilityString;
    public final String startActivityType;
    public final Double startX;
    public final Double startY;


    public TourModeChoiceEvent(double time,
                               Id<Person> personId,
                               String tourMode,
                               Tour currentTour,
                               Vector<DrivesVehicle.VehicleOrToken> availablePersonalStreetVehicles,
                               scala.collection.immutable.Map<TourModes.BeamTourMode, Seq<Modes.BeamMode>> modeToTourMode,
                               scala.collection.immutable.Map<TourModes.BeamTourMode, scala.Double> tourModeUtils,
                               Vector<Modes.BeamMode> availableModes,
                               Activity startActivity) {
        this(time,
                personId,
                tourMode,
                currentTour,
                modeToTourMode.mkString("-"),
                availablePersonalStreetVehicles.mkString("-"),
                currentTour == null ? "" : currentTour.activities().mkString("-"),
                currentTour == null ? "" : currentTour.trips().mkString("-"),
                availableModes.mkString("-"),
                tourModeUtils.mkString(""),
                startActivity.getType(),
                startActivity.getCoord().getX(),
                startActivity.getCoord().getY());
    }

    public TourModeChoiceEvent(double time,
                               Id<Person> personId,
                               String tourMode,
                               Tour currentTour,
                               String modeToTourModeString,
                               String availablePersonalStreetVehiclesString,
                               String tourActivitiesString,
                               String tourTripIdsString,
                               String availableModesString,
                               String tourModeToUtilityString,
                               String startActivityType,
                               Double startX,
                               Double startY) {
        super(time);

        this.personId = personId;
        this.tourMode = tourMode;
        this.currentTour = currentTour;
        this.modeToTourModeString = modeToTourModeString;
        this.availablePersonalStreetVehiclesString = availablePersonalStreetVehiclesString;
        this.tourActivitiesString = tourActivitiesString;
        this.tourTripIdsString = tourTripIdsString;
        this.availableModesString = availableModesString;
        this.tourModeToUtilityString = tourModeToUtilityString;
        this.startActivityType = startActivityType;
        this.startX = startX;
        this.startY = startY;
    }

    public static TourModeChoiceEvent apply(Event event) {
        if (!(event instanceof TourModeChoiceEvent) && EVENT_TYPE.equalsIgnoreCase(event.getEventType())) {
            Map<String, String> attr = event.getAttributes();
            return new TourModeChoiceEvent(event.getTime(),
                    Id.createPersonId(attr.get(ATTRIBUTE_PERSON_ID)),
                    attr.get(ATTRIBUTE_TOUR_MODE),
                    null,
                    attr.get(ATTRIBUTE_MODE_TO_TOUR_MODE),
                    attr.get(ATTRIBUTE_AVAILABLE_VEHICLES),
                    attr.get(ATTRIBUTE_TOUR_ACTIVITIES),
                    attr.get(ATTRIBUTE_TOUR_TRIP_IDS),
                    attr.get(ATTRIBUTE_AVAILABLE_MODES),
                    attr.get(ATTRIBUTE_TOUR_MODE_UTILITY),
                    attr.get(ATTRIBUTE_CURRENT_ACTIVITY),
                    Double.parseDouble(attr.get(ATTRIBUTE_CURRENT_ACTIVITY_Y)),
                    Double.parseDouble(attr.get(ATTRIBUTE_CURRENT_ACTIVITY_X))
            );
        }
        return (TourModeChoiceEvent) event;
    }


    @Override
    public Map<String, String> getAttributes() {
        Map<String, String> attr = super.getAttributes();
        attr.put(ATTRIBUTE_PERSON_ID, personId.toString());
        attr.put(ATTRIBUTE_TOUR_MODE, tourMode);
        attr.put(ATTRIBUTE_MODE_TO_TOUR_MODE, modeToTourModeString);
        attr.put(ATTRIBUTE_AVAILABLE_VEHICLES, availablePersonalStreetVehiclesString);
        attr.put(ATTRIBUTE_TOUR_ACTIVITIES, tourActivitiesString);
        attr.put(ATTRIBUTE_TOUR_TRIP_IDS, tourTripIdsString);
        attr.put(ATTRIBUTE_AVAILABLE_MODES, availableModesString);
        attr.put(ATTRIBUTE_TOUR_MODE_UTILITY, tourModeToUtilityString);
        attr.put(ATTRIBUTE_CURRENT_ACTIVITY, startActivityType);
        attr.put(ATTRIBUTE_CURRENT_ACTIVITY_X, Double.toString(startX));
        attr.put(ATTRIBUTE_CURRENT_ACTIVITY_Y, Double.toString(startY));
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
}
