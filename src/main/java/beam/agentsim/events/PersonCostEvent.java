package beam.agentsim.events;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.internal.HasPersonId;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class PersonCostEvent extends Event implements HasPersonId {

    public final static String EVENT_TYPE = "PersonCost";
    public final static String ATTRIBUTE_PERSON = "person";
    public final static String ATTRIBUTE_MODE = "mode";
    public static final String ATTRIBUTE_COST_TYPE = "costType";
    public static final String ATTRIBUTE_COST = "cost";

    public static final String COST_TYPE_COST = "Cost";
    public static final String COST_TYPE_SUBSIDY = "Subsidy";

    private final Id<Person> personId;
    private String mode;
    private String costType;
    private double cost;

    private final AtomicReference<Map<String, String>> attributes;

    public PersonCostEvent(final double time, final Id<Person> personId, String mode, String costType, double cost) {
        super(time);
        this.personId = personId;
        this.mode = mode;
        this.costType = costType;
        this.cost = cost;
        this.attributes = new AtomicReference<>(Collections.emptyMap());
    }

    public static PersonCostEvent apply(Event event) {
        if (!(event instanceof PersonCostEvent) && EVENT_TYPE.equalsIgnoreCase(event.getEventType())) {
            Map<String, String> attr = event.getAttributes();
            return new PersonCostEvent(event.getTime(), Id.createPersonId(attr.get(ATTRIBUTE_PERSON)), attr.get(ATTRIBUTE_MODE), attr.get(ATTRIBUTE_COST_TYPE), Double.parseDouble(attr.get(ATTRIBUTE_COST)));
        }
        return (PersonCostEvent) event;
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
        Map<String, String> attr = attributes.get();
        if (attr != Collections.EMPTY_MAP) return attr;

        attr = super.getAttributes();

        attr.put(ATTRIBUTE_PERSON, personId.toString());
        attr.put(ATTRIBUTE_MODE, mode);
        attr.put(ATTRIBUTE_COST_TYPE, costType);
        attr.put(ATTRIBUTE_COST, Double.toString(cost));

        attributes.set(attr);

        return attr;
    }
}
