package beam.charging.infrastructure;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;

import beam.EVGlobalData;
import beam.charging.management.ChargingQueueImpl;
import beam.charging.vehicle.AgentChargingState;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.parking.lib.DebugLib;
import beam.transEnergySim.agents.VehicleAgent;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugType;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPoint;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;

import com.google.common.collect.HashMultimap;

import java.util.Collection;
import java.util.LinkedList;

public class ChargingPointImpl implements ChargingPoint{
	private static final Logger log = Logger.getLogger(ChargingPointImpl.class);
	
	private ChargingSite chargingSite;
	private Id<ChargingPoint> chargingPointId;
	private int availableParkingSpots;
	private ChargingQueueImpl slowChargingQueue;
	private HashMultimap<ChargingPlugType,ChargingPlug> availableChargingPlugsByPlugType = HashMultimap.create();
	private LinkedList<ChargingPlug> allChargingPlugs = new LinkedList<>();

	public ChargingPointImpl(Id<ChargingPoint> chargingPointId, ChargingSite chargingSite, int adjecentParkingSpots){
		this.chargingPointId = chargingPointId;
		this.chargingSite=chargingSite;
		chargingSite.addChargingPoint(this);
		this.availableParkingSpots = adjecentParkingSpots;
	}
	
	@Override
	public void addChargingPlug(ChargingPlug plug) {
		this.chargingSite.addChargingPlug(plug);
		this.availableChargingPlugsByPlugType.put(plug.getChargingPlugType(), plug);
		this.allChargingPlugs.add(plug);
	}
	
	@Override
	public ChargingSite getChargingSite() {
		return chargingSite;
	}
	
	@Override
	public Id<ChargingPoint> getId() {
		return chargingPointId;
	}

	@Override
	public Collection<ChargingPlug> getAllChargingPlugs() {
		return this.allChargingPlugs;
	}
	
	@Override
	public Collection<ChargingPlug> getAvailableChargingPlugsOfPlugType(ChargingPlugType plugType) {
		return this.availableChargingPlugsByPlugType.get(plugType);
	}

	public int getNumberOfAvailableParkingSpots() {
		return availableParkingSpots;
	}
	
	@Override
	public void registerVehicleDeparture(VehicleAgent agent) {
		if(this.slowChargingQueue.isPhysicalSiteFull()){
			for(ChargingPlug eachPlug : getAllChargingPlugs()){
				if(eachPlug.getChargingPlugType().getNominalLevel() < 3)eachPlug.registerPlugAccessible();
			}
		}
		this.slowChargingQueue.removeVehicleFromPhysicalSite(agent);
	}

	@Override
	public void handleBeginChargeEvent(ChargingPlug plug, VehicleAgent agent) {
		if(plug.getChargingPlugType().getNominalLevel() < 3){
			if(!this.slowChargingQueue.addVehicleToPhysicalSite()){
				DebugLib.stopSystemAndReportInconsistency("Plug chosen but found to be not accessible when attempting to add to the slow charging queue");
			}
			if(this.slowChargingQueue.isPhysicalSiteFull()){
				for(ChargingPlug eachPlug : getAllChargingPlugs()){
					if(eachPlug.getChargingPlugType().getNominalLevel() < 3)eachPlug.registerPlugInaccessible();
				}
			}
			if(this.availableChargingPlugsByPlugType.get(plug.getChargingPlugType()).size() == 0){
				this.slowChargingQueue.addVehicleToChargingQueue(plug.getChargingPlugType(), agent);
			}else{
				ChargingPlug plugToUse = this.availableChargingPlugsByPlugType.get(plug.getChargingPlugType()).iterator().next();
				((PlugInVehicleAgent)agent).setSelectedChargingPlug(plugToUse);
				// Begin the charging session immediately, otherwise the queue will initiate the session when the queue has emptied
				EVGlobalData.data.chargingInfrastructureManager.handleBeginChargingSession(plugToUse, (PlugInVehicleAgent)agent);
			}
		}
	}
	
	@Override
	public void createSlowChargingQueue(int numberOfAvailableParkingSpots) {
		for(ChargingPlug plug : this.allChargingPlugs){
			if(plug.getChargingPlugType().getNominalLevel() < 3 && this.slowChargingQueue == null){
				this.slowChargingQueue = new ChargingQueueImpl(numberOfAvailableParkingSpots);
			}
		}
	}

	@Override
	public void handleEndChargingSession(ChargingPlug plug, VehicleAgent agent) {
		if(plug.getChargingPlugType().getNominalLevel() < 3 && this.slowChargingQueue.getNumInChargingQueue(plug.getChargingPlugType()) > 0){
			((PlugInVehicleAgent)agent.getVehicle().getVehicleAgent()).setChargingState(AgentChargingState.POST_CHARGE_UNPLUGGED);
			plug.unplugVehicle(agent.getVehicle());
			PlugInVehicleAgent nextAgent = (PlugInVehicleAgent)this.slowChargingQueue.dequeueVehicleFromChargingQueue(plug.getChargingPlugType());
			nextAgent.setSelectedChargingPlug(plug);
			registerPlugUnavailable(plug); // don't let someone come and nab in the interim
			double timeToDequeuNext = EVGlobalData.data.TIME_TO_ENGAGE_NEXT_FAST_CHARGING_SESSION;
//			log.info(EVGlobalData.data.now + " " + this.toString()+" scheduling beginChargingSession for veh "+ nextAgent + (EVGlobalData.data.now + timeToDequeuNext));
			EVGlobalData.data.scheduler.addCallBackMethod(EVGlobalData.data.now + timeToDequeuNext, nextAgent, "beginChargingSession",0.0, this);
		}
	}

	@Override
	public void removeVehicleFromQueue(ChargingPlug plug, VehicleAgent vehicle) {
		this.slowChargingQueue.removeVehicleFromChargingQueue(vehicle);
	}
	public String toString(){
		return "Point " + this.getId().toString() + ", " + this.availableChargingPlugsByPlugType.values().size() + "/" + this.allChargingPlugs.size() + " plugs avail, " + 
				this.slowChargingQueue.getNumAtPhysicalSite() + "/" + this.slowChargingQueue.maxSizeOfPhysicalSite() + " vehs at point";
	}

	@Override
	public void registerPlugAvailable(ChargingPlug plug) {
		this.availableChargingPlugsByPlugType.get(plug.getChargingPlugType()).add(plug);
	}

	@Override
	public void registerPlugUnavailable(ChargingPlug plug) {
		this.availableChargingPlugsByPlugType.get(plug.getChargingPlugType()).remove(plug);
	}

	@Override
	public boolean isSlowChargingQueueAccessible() {
		return !this.slowChargingQueue.isPhysicalSiteFull();
	}

	@Override
	public void resetAll() {
		if(this.slowChargingQueue!=null)slowChargingQueue.resetAll();
		for(ChargingPlug plug : this.allChargingPlugs){
			this.availableChargingPlugsByPlugType.put(plug.getChargingPlugType(), plug);
		}
	}

	@Override
	public int getNumInChargingQueue(ChargingPlug plug){
		return this.slowChargingQueue.getNumInChargingQueue(plug.getChargingPlugType());
	}
	
}
