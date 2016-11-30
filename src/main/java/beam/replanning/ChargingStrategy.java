package beam.replanning;

import java.io.IOException;

import org.jdom.Element;
import org.jdom.JDOMException;
import org.matsim.api.core.v01.Identifiable;

import beam.charging.vehicle.PlugInVehicleAgent;
import beam.logit.NestedLogit;
import beam.sim.SearchAdaptationAlternative;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;

// this is the charging strategy for a single leg
public interface ChargingStrategy extends Identifiable<ChargingStrategy> {
	
	double getScore();
	String getStrategyName();
	boolean hasChosenToChargeOnArrival(PlugInVehicleAgent agent);
	boolean hasChosenToChargeOnDeparture(PlugInVehicleAgent agent);
	ChargingPlug getChosenChargingAlternativeOnArrival(PlugInVehicleAgent agent);
	ChargingPlug getChosenChargingAlternativeOnDeparture(PlugInVehicleAgent agent);
	void setParameters(String parameterStringAsXML);
	void resetDecisions();
	void setId(int parseInt);
	void setName(String value);
	void setParameters(Element child);
	void setSearchAdaptationDecisionOnArrival(SearchAdaptationAlternative alternative);
	void setSearchAdaptationDecisionOnDeparture(SearchAdaptationAlternative alternative);
	SearchAdaptationAlternative getChosenAdaptationAlternativeOnArrival(PlugInVehicleAgent agent);
	SearchAdaptationAlternative getChosenAdaptationAlternativeOnDeparture(PlugInVehicleAgent agent);
	Double getSearchRadiusOnArrival();
	Double getSearchRadiusOnDeparture();
	ChargingStrategy copy();
	void resetInternalTracking();
}
