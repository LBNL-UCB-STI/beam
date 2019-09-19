package beam.agentsim.events;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.internal.HasPersonId;

import java.util.Map;

public class PersonCostEvent extends Event implements HasPersonId {

    public final static String EVENT_TYPE = "PersonCost";
    public final static String ATTRIBUTE_PERSON = "person";
    public final static String ATTRIBUTE_MODE = "mode";
    public static final String ATTRIBUTE_INCENTIVE = "incentive";
    public static final String ATTRIBUTE_TOLL_COST = "tollCost";
    public static final String ATTRIBUTE_NET_COST = "netCost";

    private final Id<Person> personId;
    private final String mode;
    private final double incentive;
    private final double tollCost;
    private final double netCost;

    public PersonCostEvent(final double time, final Id<Person> personId, String mode, double incentive, double tollCost, double netCost) {
        super(time);
        this.personId = personId;
        this.mode = mode;
        this.incentive = incentive;
        this.tollCost = tollCost;
        this.netCost = netCost;
    }

    public static PersonCostEvent apply(Event event) {
        if (!(event instanceof PersonCostEvent) && EVENT_TYPE.equalsIgnoreCase(event.getEventType())) {
            Map<String, String> attr = event.getAttributes();
            return new PersonCostEvent(event.getTime(), Id.createPersonId(attr.get(ATTRIBUTE_PERSON)),
                    attr.get(ATTRIBUTE_MODE),
                    Double.parseDouble(attr.get(ATTRIBUTE_INCENTIVE)),
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

    public double getNetCost() {
        return netCost;
    }

    public double getIncentive() {
        return incentive;
    }

    public double getTollCost() {
        return tollCost;
    }

    public String getMode() { return mode; }

    @Override
    public Map<String, String> getAttributes() {
        Map<String, String> attr = super.getAttributes();
        attr.put(ATTRIBUTE_PERSON, personId.toString());
        attr.put(ATTRIBUTE_MODE, mode);
        attr.put(ATTRIBUTE_INCENTIVE, Double.toString(incentive));
        attr.put(ATTRIBUTE_TOLL_COST, Double.toString(tollCost));
        attr.put(ATTRIBUTE_NET_COST, Double.toString(netCost));
        return attr;
    }
}
