package beam.charging.infrastructure;

import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugType;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;

public class ChargingDecisionAlternative {

	public ChargingSite site;
	public ChargingPlugType plugType;

	public ChargingDecisionAlternative(ChargingSite site, ChargingPlugType plugType) {
		this.site = site;
		this.plugType = plugType;
	}
	public String toString(){
		return this.site+"-"+this.plugType;
	}
}
