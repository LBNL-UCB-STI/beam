package beam.charging.infrastructure;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;

import beam.EVGlobalData;
import beam.charging.vehicle.AgentChargingState;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.events.UnplugEvent;
import beam.parking.lib.DebugLib;
import beam.sim.scheduler.CallBack;
import beam.transEnergySim.agents.VehicleAgent;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugStatus;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugType;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPoint;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;
import beam.transEnergySim.events.PluginEventHandler;
import beam.transEnergySim.vehicles.api.VehicleWithBattery;

import java.util.Collection;

public class ChargingPlugImpl implements ChargingPlug{
	
	private static final Logger log = Logger.getLogger(ChargingPlugImpl.class);
	
	private ChargingPoint chargingPoint;
	private Id<ChargingPlug> chargingPlugId;
	ChargingPlugStatus chargingPlugStatus;
	ChargingPlugType chargingPlugType;
	private VehicleWithBattery vehicle;
	private Double beginningOfChargingSession;
	private CallBack nextScheduledCallback;
	private boolean useInCalibration;

	public ChargingPlugImpl(Id<ChargingPlug> chargingPlugId, ChargingPoint chargingPoint, ChargingPlugType chargingPlugType, boolean useInCalibration){
		this.chargingPlugId = chargingPlugId;
		this.chargingPoint=chargingPoint;
		this.chargingPlugType = chargingPlugType;
		chargingPoint.addChargingPlug(this);
		chargingPlugStatus=ChargingPlugStatus.AVAILABLE;
		this.useInCalibration = useInCalibration;
	}
	
	@Override
	public ChargingPoint getChargingPoint() {
		return chargingPoint;
	}

	@Override
	public ChargingPlugStatus getChargingPlugStatus() {
		return chargingPlugStatus;
	}

	@Override
	public void plugVehicle(VehicleWithBattery vehicle) {
		if(this.getId().toString().equals("27")){
			DebugLib.emptyFunctionForSettingBreakPoint();
		}
		if(this.vehicle!=null){
			DebugLib.emptyFunctionForSettingBreakPoint();
		}
		this.vehicle = vehicle;
		this.beginningOfChargingSession = EVGlobalData.data.now;
		chargingPlugStatus=ChargingPlugStatus.IN_USE;
		this.getChargingSite().registerPlugUnavailable(this);
		this.getChargingPoint().registerPlugUnavailable(this);
	}
	public void unplugVehicle() {
		this.unplugVehicle(this.vehicle);
	}
	public void unplugVehicle(VehicleWithBattery vehicle) {
		PlugInVehicleAgent agent = (PlugInVehicleAgent)vehicle.getVehicleAgent();
		agent.setChargingState(AgentChargingState.POST_CHARGE_UNPLUGGED);
		EVGlobalData.data.controler.getEvents().processEvent(new UnplugEvent(EVGlobalData.data.now,agent,this));
		this.vehicle = null;
		this.beginningOfChargingSession = null;
	}
	
	public void registerPlugAvailable() {
		chargingPlugStatus=ChargingPlugStatus.AVAILABLE;
		this.getChargingSite().registerPlugAvailable(this);
		this.getChargingPoint().registerPlugAvailable(this);
	}

	@Override
	public double getActualChargingPowerInWatt() {
		if(this.vehicle==null){
			return 0.0;
		}else{
			return Math.min(this.chargingPlugType.getChargingPowerInKW()*1000.0,vehicle.getMaxChargingPowerInKW(this.chargingPlugType)*1000.0);
		}
	}

	@Override
	public Id<ChargingPlug> getId() {
		return chargingPlugId;
	}

	@Override
	public ChargingPlugType getChargingPlugType() {
		return this.chargingPlugType;
	}

	@Override
	public VehicleWithBattery getVehicle() {
		return this.vehicle;
	}

	@Override
	public double getMaxChargingPowerInWatt() {
		return this.chargingPlugType.getChargingPowerInKW()*1000.0;
	}

	@Override
	public double getEnergyDeliveredByTime(double time) {
		return this.chargingPoint.getChargingSite().getChargingNetworkOperator().determineEnergyDelivered(this, vehicle, time - this.beginningOfChargingSession);
	}

	@Override
	public ChargingSite getChargingSite() {
		return this.chargingPoint.getChargingSite();
	}

	@Override
	public double estimateChargingSessionDuration() {
		if(this.chargingPlugType.getNominalLevel()==3){
			DebugLib.emptyFunctionForSettingBreakPoint();
		}
		return this.chargingPoint.getChargingSite().estimateChargingSessionDuration(this.getChargingPlugType(),this.getVehicle());
	}
	
	@Override
	public void handleBeginChargeEvent(){
	}
	@Override
	public void handleEndChargingSession(){
//		log.info(EVGlobalData.data.now + " Plug "+this.toString()+" executing handleEndChargingSession");
		if(this.chargingPlugType.getNominalLevel()==3){
			DebugLib.emptyFunctionForSettingBreakPoint();
		}
		this.vehicle.addEnergyToVehicleBattery(this.getEnergyDeliveredByTime(EVGlobalData.data.now));
		double sessionDuration = this.estimateChargingSessionDuration();
		if(sessionDuration > EVGlobalData.data.EQUALITY_EPSILON){
			this.beginningOfChargingSession = EVGlobalData.data.now;
//			log.info(EVGlobalData.data.now + " Plug "+this.toString()+" REscheduling handleEndChargingSession for " + (EVGlobalData.data.now + sessionDuration));
			this.nextScheduledCallback = EVGlobalData.data.scheduler.addCallBackMethod(EVGlobalData.data.now + sessionDuration, this, "handleEndChargingSession",0.0,this);
		}else{
			PlugInVehicleAgent agent = (PlugInVehicleAgent)this.getVehicle().getVehicleAgent();
			registerPlugAvailable();
			EVGlobalData.data.chargingInfrastructureManager.handleEndChargingSession(this, agent);
		}
	}

	@Override
	public void registerPlugInaccessible() {
		this.chargingPoint.getChargingSite().registerPlugInaccessible(this);
	}
	@Override
	public void registerPlugAccessible() {
		this.chargingPoint.getChargingSite().registerPlugAccessible(this);
	}

	@Override
	public void handleBeginChargingSession(VehicleAgent agent) {
		if(this.getId().toString().equals("2253")){
			DebugLib.emptyFunctionForSettingBreakPoint();
		}
		if(this.getVehicle()!=null){
			PlugInVehicleAgent pluginAgent = (PlugInVehicleAgent)this.getVehicle().getVehicleAgent();
			pluginAgent.setChargingState(AgentChargingState.POST_CHARGE_UNPLUGGED);
			unplugVehicle(this.getVehicle());
		}
		plugVehicle(((VehicleWithBattery)agent.getVehicle()));
		double sessionDurationEstimate = estimateChargingSessionDuration();
//		log.info(EVGlobalData.data.now + " Plug "+this.toString()+" scheduling handleEndChargingSession for "+ (EVGlobalData.data.now + sessionDurationEstimate));
		this.nextScheduledCallback = EVGlobalData.data.scheduler.addCallBackMethod(EVGlobalData.data.now + sessionDurationEstimate, this, "handleEndChargingSession",0.0,this);
	}

	@Override
	public void handleChargingSessionInterruption() {
		EVGlobalData.data.scheduler.removeCallback(this.nextScheduledCallback);
	}
	public String toString(){
		return this.chargingPlugType.getPlugTypeName() + " (" + this.getId().toString() + ")";
	}

	@Override
	public boolean isAvailable() {
		return this.chargingPlugStatus == ChargingPlugStatus.AVAILABLE;
	}

	@Override
	public boolean isAccessible() {
		return (this.chargingPlugType.getNominalLevel() < 3) ? this.chargingPoint.isSlowChargingQueueAccessible() : this.getChargingSite().isFastChargingQueueAccessible();
	}

	@Override
	public void resetAll() {
		chargingPlugStatus=ChargingPlugStatus.AVAILABLE;
		vehicle = null;
		beginningOfChargingSession = null;
		nextScheduledCallback = null;
	}

	@Override
	public boolean useInCalibration() {
		return this.useInCalibration;
	}
}
