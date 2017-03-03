package beam.sim.traveltime;

import java.io.Serializable;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

public class RouteInformationElement implements Serializable {
	private double travelTime, travelDistance;
	private String linkId;

	// Zero-arg constructor necessary to use kyro for serailization
	public RouteInformationElement(){
	}
	public RouteInformationElement(Link link, double linkTravelTime) {
		this.linkId = link.getId().toString();
		this.travelTime = linkTravelTime;
		this.travelDistance = link.getLength();
	}

	public Id<Link> getLinkId() {
		return Id.createLinkId(this.linkId);
	}

	public double getAverageSpeed() {
		return travelTime==0.0 ? 1.0 : travelDistance / travelTime;
	}

	public double getLinkTravelTime() {
		return this.travelTime;
	}
	
	public double getLinkTravelDistance(){
		return this.travelDistance;
	}

}
