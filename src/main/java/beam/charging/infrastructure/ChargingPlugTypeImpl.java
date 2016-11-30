package beam.charging.infrastructure;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;

import beam.parking.lib.DebugLib;
import beam.parking.lib.obj.DoubleValueHashMap;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingLevel;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugStatus;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugType;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPoint;
import beam.transEnergySim.vehicles.api.Vehicle;
import beam.transEnergySim.vehicles.api.VehicleWithBattery;

import java.util.Collection;

public class ChargingPlugTypeImpl implements ChargingPlugType{
	
	private static final Logger log = Logger.getLogger(ChargingPlugTypeImpl.class);
	
	public Id<ChargingPlugType> chargingPlugTypeId;
	private double chargingPowerInKW;
	private double dischargingPowerInKW;
	private boolean isV2GCapable;
	private boolean isV1GCapable;
	private String plugTypeName;
	private int nominalChargingLevel;
	
	public ChargingPlugTypeImpl(Id<ChargingPlugType> chargingPlugTypeId, String plugTypeName, double chargingPowerInKW, double dischargingPowerInKW, boolean isV1GCapable, boolean isV2GCapable){
		this.chargingPlugTypeId = chargingPlugTypeId;
		this.chargingPowerInKW = chargingPowerInKW;
		this.dischargingPowerInKW = dischargingPowerInKW;
		this.isV1GCapable = isV1GCapable;
		this.isV2GCapable = isV2GCapable;
		this.plugTypeName = plugTypeName;
		if(chargingPowerInKW <= 1.5){
			this.nominalChargingLevel = 1;
		}else if(chargingPowerInKW <= 20.0){
			this.nominalChargingLevel = 2;
		}else{
			this.nominalChargingLevel = 3;
		}
	}
	
	@Override
	public double getChargingPowerInKW() {
		return this.chargingPowerInKW;
	}

	@Override
	public double getDischargingPowerInKW() {
		return this.dischargingPowerInKW;
	}

	@Override
	public boolean isV1GCapable() {
		return this.isV1GCapable;
	}

	@Override
	public boolean isV2GCapable() {
		return this.isV2GCapable;
	}

	@Override
	public String getPlugTypeName() {
		return this.plugTypeName;
	}
	public String toString() {
		return this.plugTypeName;
	}

	@Override
	public int getNominalLevel() {
		return this.nominalChargingLevel;
	}

}
