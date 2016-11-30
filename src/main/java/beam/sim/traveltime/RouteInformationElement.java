package beam.sim.traveltime;

import java.io.Serializable;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

public class RouteInformationElement implements Serializable {
	private double travelTime, averageSpeed, travelDistance;
	private String linkId;

	// Zero-arg constructor necessary to use kyro for serailization
	public RouteInformationElement(){
	}
	public RouteInformationElement(Link link, double linkTravelTime) {
		this.linkId = link.getId().toString();
		this.travelTime = linkTravelTime;
		this.travelDistance = link.getLength();
		this.averageSpeed = linkTravelTime==0.0 ? 1.0 : link.getLength() / linkTravelTime;
	}

	public Id<Link> getLinkId() {
		return Id.createLinkId(this.linkId);
	}

	public double getAverageSpeed() {
		return this.averageSpeed;
	}

	public double getLinkTravelTime() {
		return this.travelTime;
	}
	
	public double getLinkTravelDistance(){
		return this.travelDistance;
	}

}
