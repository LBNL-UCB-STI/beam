package beam.agentsim.events;

import org.matsim.api.core.v01.events.Event;

import java.util.Map;

public class AgencyRevenueEvent extends Event {

    public final static String EVENT_TYPE = "AgencyRevenue";
    public final static String ATTRIBUTE_AGENCY_ID = "agencyId";
    public final static String ATTRIBUTE_REVENUE = "revenue";

    private final String agencyId;
    public final double revenue;

    public AgencyRevenueEvent(final double time, final String agencyId, double revenue) {
        super(time);
        this.agencyId = agencyId;
        this.revenue = revenue;
    }

    public static AgencyRevenueEvent apply(Event event) {
        if (!(event instanceof AgencyRevenueEvent) && EVENT_TYPE.equalsIgnoreCase(event.getEventType())) {
            Map<String, String> attr = event.getAttributes();
            return new AgencyRevenueEvent(event.getTime(), attr.get(ATTRIBUTE_AGENCY_ID),
                    Double.parseDouble(attr.get(ATTRIBUTE_REVENUE)));
        }
        return (AgencyRevenueEvent) event;
    }

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    public String getAgencyId() {
        return agencyId;
    }

    @Override
    public Map<String, String> getAttributes() {
        Map<String, String> attr = super.getAttributes();
        attr.put(ATTRIBUTE_AGENCY_ID, agencyId);
        attr.put(ATTRIBUTE_REVENUE, Double.toString(revenue));
        return attr;
    }
}
