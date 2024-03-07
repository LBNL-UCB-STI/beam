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

public class BeamPersonDepartureEvent extends org.matsim.api.core.v01.events.PersonDepartureEvent implements HasPersonId {

	public static final String EVENT_TYPE = "departure";

	public static final String ATTRIBUTE_PERSON = "person";
	public static final String ATTRIBUTE_LINK = "link";
	public static final String ATTRIBUTE_LEGMODE = "legMode";

	public final static String ATTRIBUTE_TRIP_ID = "tripId";
	private final String tripId;

	public BeamPersonDepartureEvent(final double time, final Id<Person> agentId, final Id<Link> linkId, final String legMode, final String tripId) {
		super(time, agentId, linkId, legMode);
		this.tripId = tripId;
	}


	public String getTripId() {
		return this.tripId;
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
		}
		if (this.tripId != null) {
			attr.put(ATTRIBUTE_TRIP_ID, this.tripId);
		}
		return attr;
	}
}