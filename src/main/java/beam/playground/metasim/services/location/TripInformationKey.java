package beam.playground.metasim.services.location;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

public class TripInformationKey {
	int timeSlot;
	Id<Link> fromId, toId;

	public TripInformationKey(Link from, Link to, int timeSlot){
		this.fromId = from.getId();
		this.toId = to.getId();
		this.timeSlot = timeSlot;
	}
	@Override
	public boolean equals(Object o){
		TripInformationKey key = (TripInformationKey)o;
		return key.fromId.equals(fromId) && key.toId.equals(toId) && key.timeSlot == timeSlot;
	}
}
