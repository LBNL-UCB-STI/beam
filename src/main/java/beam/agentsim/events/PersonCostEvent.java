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
    public static final String ATTRIBUTE_RAW_COST = "rawCost";
    public static final String ATTRIBUTE_SUBSIDY = "subsidy";
    public static final String ATTRIBUTE_TOLL_COST = "tollCost";
    public static final String ATTRIBUTE_NET_COST = "netCost";

    private final Id<Person> personId;
    private String mode;
    private String costType;
    private double rawCost;
    private double subsidy;
    private double tollCost;
    private double netCost;

    private final AtomicReference<Map<String, String>> attributes;

    public PersonCostEvent(final double time, final Id<Person> personId, String mode, double rawCost, double subsidy, double tollCost, double netCost) {
        super(time);
        this.personId = personId;
        this.mode = mode;
        this.rawCost = rawCost;
        this.subsidy = subsidy;
        this.tollCost = tollCost;
        this.netCost = netCost;
        this.attributes = new AtomicReference<>(Collections.emptyMap());
    }

    public static PersonCostEvent apply(Event event) {
        if (!(event instanceof PersonCostEvent) && EVENT_TYPE.equalsIgnoreCase(event.getEventType())) {
            Map<String, String> attr = event.getAttributes();
            return new PersonCostEvent(event.getTime(), Id.createPersonId(attr.get(ATTRIBUTE_PERSON)),
                    attr.get(ATTRIBUTE_MODE),
                    Double.parseDouble(attr.get(ATTRIBUTE_RAW_COST)),
                    Double.parseDouble(attr.get(ATTRIBUTE_SUBSIDY)),
                    Double.parseDouble(attr.get(ATTRIBUTE_TOLL_COST)),
                    Double.parseDouble(attr.get(ATTRIBUTE_NET_COST)));
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
        attr.put(ATTRIBUTE_RAW_COST, Double.toString(rawCost));
        attr.put(ATTRIBUTE_SUBSIDY, Double.toString(subsidy));
        attr.put(ATTRIBUTE_TOLL_COST, Double.toString(tollCost));
        attr.put(ATTRIBUTE_NET_COST, Double.toString(netCost));

        attributes.set(attr);

        return attr;
    }
}
