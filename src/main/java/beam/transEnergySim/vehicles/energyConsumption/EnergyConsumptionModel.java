/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2012 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package beam.transEnergySim.vehicles.energyConsumption;

import java.io.Serializable;
import java.util.Iterator;

import org.matsim.api.core.v01.network.Link;

import beam.parking.lib.DebugLib;
import beam.transEnergySim.vehicles.api.VehicleWithBattery;
/**
 * An energy consumption modell is needed to track energy consumption based on different delimiters. 
 * Usually driven distance and speed are of most importance, but time dependency (e.g. for heating) is also possible 
 * 
 * @author rashid_waraich
 * 			jbischoff
 * 
 */
public interface EnergyConsumptionModel extends Serializable{

	public abstract double getEnergyConsumptionRateInJoulesPerMeter(VehicleWithBattery vehicle);

	public abstract double getEnergyConsumptionForLinkInJoule(Link link, VehicleWithBattery vehicle, double averageSpeed);

	public abstract double getEnergyConsumptionForLinkInJoule(double distance, VehicleWithBattery vehicle, double averageSpeed);

}
