package beam.sim.traveltime;

import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedList;

import beam.EVGlobalData;
import beam.transEnergySim.vehicles.api.VehicleWithBattery;
import beam.transEnergySim.vehicles.energyConsumption.EnergyConsumptionModel;

public class TripInformation implements Serializable {
	LinkedList<RouteInformationElement> routeInformationElements = new LinkedList<>();
	double departureTime, tripTravelTime = 0.0, tripTravelDistance = 0.0, tripAverageSpeed;
	HashMap<EnergyConsumptionModel,HashMap<VehicleWithBattery,Double>> tripEnergyConsumptionByModel = new HashMap<>();
	String routeAsString = "";
	
	// Zero-arg constructor necessary to use kyro for serailization
	public TripInformation(){
	}
	public TripInformation(double departureTime, LinkedList<RouteInformationElement> route){
		this.departureTime = departureTime;
		routeInformationElements = route;
		for(RouteInformationElement elem : routeInformationElements){
			tripTravelTime += elem.getLinkTravelTime();
			tripTravelDistance += elem.getLinkTravelDistance();
			routeAsString += elem.getLinkId() + " ";
		}
		tripAverageSpeed = tripTravelDistance / tripTravelTime;
	}
	
	public LinkedList<RouteInformationElement> getRouteInfoElements() {
		return routeInformationElements;
	}

	public double getTripDistance() {
		return this.tripTravelDistance;
	}

	public double getTripTravelTime() {
		return this.tripTravelTime;
	}
	
	public double getTripAverageSpeed() {
		return this.tripAverageSpeed;
	}
	
	public double getTripEnergyConsumption(EnergyConsumptionModel model, VehicleWithBattery vehicle){
		//TODO this used to have a cache, add it back
		double energyConsumed = 0.0;
		for(RouteInformationElement elem : routeInformationElements){
			energyConsumed += model.getEnergyConsumptionForLinkInJoule(EVGlobalData.data.controler.getScenario().getNetwork().getLinks().get(elem.getLinkId()), vehicle, elem.getAverageSpeed());
		}
		return energyConsumed;
	}

	public String routeAsString() {
		return this.routeAsString;
	}
}
