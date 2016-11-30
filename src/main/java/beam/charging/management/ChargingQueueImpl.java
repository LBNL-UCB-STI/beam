package beam.charging.management;

import java.util.LinkedHashMap;
import java.util.LinkedList;

import beam.transEnergySim.agents.VehicleAgent;
import beam.transEnergySim.chargingInfrastructure.management.ChargingQueue;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugType;

public class ChargingQueueImpl implements ChargingQueue {
	LinkedHashMap<ChargingPlugType,LinkedList<VehicleAgent>> chargingQueue = new LinkedHashMap<ChargingPlugType,LinkedList<VehicleAgent>>();
	int maxAtPhysicalSite, numAtPhysicalSite, numInChargingQueue;
	
	public ChargingQueueImpl(int maxSizeAtPhysicalSite){
		this.maxAtPhysicalSite = maxSizeAtPhysicalSite;
		this.numAtPhysicalSite = 0;
		this.numInChargingQueue = 0;
	}
	@Override
	public int getNumAtPhysicalSite() {
		return this.numAtPhysicalSite;
	}
	@Override
	public int maxSizeOfPhysicalSite(){
		return this.maxAtPhysicalSite;
	}

	@Override
	public boolean addVehicleToPhysicalSite() {
		if(this.numAtPhysicalSite == this.maxAtPhysicalSite)return false;
		this.numAtPhysicalSite++;
		return true;
	}
	@Override
	public boolean addVehicleToChargingQueue(ChargingPlugType plugType, VehicleAgent vehicle) {
		if(this.chargingQueue.get(plugType) == null)this.chargingQueue.put(plugType, new LinkedList<VehicleAgent>());
		if(this.numAtPhysicalSite == this.maxAtPhysicalSite)return false;
		this.chargingQueue.get(plugType).add(vehicle);
		this.numInChargingQueue++;
		return true;
	}

	@Override
	public boolean isPhysicalSiteFull() {
		return this.numAtPhysicalSite >= this.maxAtPhysicalSite;
	}

	@Override
	public VehicleAgent dequeueVehicleFromChargingQueue(ChargingPlugType plugType) {
		this.numInChargingQueue--;
		return this.chargingQueue.get(plugType).removeFirst();
	}

	@Override
	public VehicleAgent peekAtChargingQueue(ChargingPlugType chargingPlugType) {
		return chargingQueue.get(chargingPlugType).peek();
	}

	@Override
	public boolean removeVehicleFromChargingQueue(VehicleAgent vehicle) {
		boolean result = false;
		for(LinkedList<VehicleAgent> queue : this.chargingQueue.values()){
			result = result || queue.remove(vehicle);
		}
		if(result)this.numInChargingQueue--;
		return result;
	}

	@Override
	public void removeVehicleFromPhysicalSite(VehicleAgent vehicle) {
		boolean result = false;
		for(LinkedList<VehicleAgent> queue : this.chargingQueue.values()){
			result = result || queue.remove(vehicle);
		}
		if(result)this.numInChargingQueue--;
		this.numAtPhysicalSite--;
	}
	@Override
	public int getNumInChargingQueue(ChargingPlugType chargingPlugType) {
		return this.numInChargingQueue;
	}
	public void resetAll() {
		this.numAtPhysicalSite = 0;
		this.numInChargingQueue = 0;
		this.chargingQueue.clear();
	}
}