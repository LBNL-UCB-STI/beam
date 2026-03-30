/* *********************************************************************** *
 * project: org.matsim.*
 * AgentDepartureEvent.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2007, 2008 by the members listed in the COPYING,  *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package beam.agentsim.events;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.internal.HasPersonId;

import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

public class BeamPersonDepartureEvent extends org.matsim.api.core.v01.events.PersonDepartureEvent implements HasPersonId {

    public static final String EVENT_TYPE = "departure";

    public static final String ATTRIBUTE_PERSON = "person";
    public static final String ATTRIBUTE_LINK = "link";
    public static final String ATTRIBUTE_LEGMODE = "legMode";

    public final static String ATTRIBUTE_TRIP_ID = "trip_id";
    public final static String ATTRIBUTE_PAYLOAD_WEIGHT_IN_KG = "PayloadWeightInKg";
    public final static String ATTRIBUTE_PAYLOAD_IDS = "PayloadIds";

    private final String tripId;
    private final List<String> payloadIds;
    private final String payloadWeightInKg;

    public BeamPersonDepartureEvent(final double time, final Id<Person> agentId, final Id<Link> linkId, final String legMode, final String tripId, final List<String> payloadIds, final String payloadWeightInKg) {
        super(time, agentId, linkId, legMode);
        this.tripId = tripId;
        this.payloadIds = payloadIds != null ? new ArrayList<>(payloadIds) : new ArrayList<>();
        this.payloadWeightInKg = payloadWeightInKg;
    }

    public BeamPersonDepartureEvent(final double time, final Id<Person> agentId, final Id<Link> linkId, final String legMode, final String tripId) {
        super(time, agentId, linkId, legMode);
        this.tripId = tripId;
        this.payloadIds = new ArrayList<>();
        this.payloadWeightInKg = "0.0";
    }

    public String getTripId() {
        return this.tripId;
    }

    public List<String> getPayloadIds() {
        return new ArrayList<>(this.payloadIds);
    }

    public String getPayloadWeightInKg() {
        return this.payloadWeightInKg;
    }

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    @Override
    public Map<String, String> getAttributes() {
        Map<String, String> attr = super.getAttributes();
        attr.put(ATTRIBUTE_PERSON, this.getPersonId().toString());
        attr.put(ATTRIBUTE_LINK, (this.getLinkId() == null ? null : this.getLinkId().toString()));
        if (this.getLegMode() != null) {
            attr.put(ATTRIBUTE_LEGMODE, this.getLegMode());
        } else {
            attr.put(ATTRIBUTE_LEGMODE, "");
        }
        List<String> cleanedPayloadIds = this.payloadIds.stream()
                .map(id -> id == null ? "" : id.trim())
                .filter(id -> !id.isEmpty() && !id.equals("[]") && !id.equals("[[]]"))
                .collect(Collectors.toList());
        if (!cleanedPayloadIds.isEmpty()) {
            attr.put(ATTRIBUTE_PAYLOAD_IDS, cleanedPayloadIds.toString());
        } else {
            attr.put(ATTRIBUTE_PAYLOAD_IDS, "");
        }
        if (this.tripId != null) {
            attr.put(ATTRIBUTE_TRIP_ID, this.tripId);
        } else {
            attr.put(ATTRIBUTE_TRIP_ID, "");
        }
        if (!Objects.equals(this.payloadWeightInKg, "")) {
            attr.put(ATTRIBUTE_PAYLOAD_WEIGHT_IN_KG, this.payloadWeightInKg);
        } else {
            attr.put(ATTRIBUTE_PAYLOAD_WEIGHT_IN_KG, "0.0");
        }
        return attr;
    }
}
