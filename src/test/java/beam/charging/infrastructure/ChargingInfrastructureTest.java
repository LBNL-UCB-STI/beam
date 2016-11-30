package beam.charging.infrastructure;

import static org.junit.Assert.*;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;

import org.junit.Before;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.config.Config;

import beam.EVGlobalData;
import beam.EVSimTeleController;
import beam.TestUtilities;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugStatus;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugType;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;
import beam.transEnergySim.vehicles.api.VehicleWithBattery;
import junit.framework.TestCase;

public class ChargingInfrastructureTest {
	protected EVSimTeleController evSimTeleController = new EVSimTeleController();
	ChargingInfrastructureManagerImpl chargingInfrastructureManager;
	Coord coordOfSpecificCharger;

	 @Before
	 public void setUp() {
		TestUtilities.setConfigFile("ChargingInfrastructureTestConfig.xml");
		TestUtilities.setTestInputDirectory(ChargingInfrastructureTest.class);
		
		evSimTeleController.init();

		evSimTeleController.attachLinkTree();

		this.chargingInfrastructureManager = new ChargingInfrastructureManagerImpl();
		EVGlobalData.data.chargingInfrastructureManager = this.chargingInfrastructureManager;
		this.coordOfSpecificCharger = EVGlobalData.data.transformFromWGS84.transform(new Coord(-123.0434,38.31453));
	 }

	/*
	 * INFRASTRUCTURE MANAGER
	 */
	@Test
	public void testChargingInfrastructureManager(){
		// Does infrastructure manager return all sites in the domain?
		assertEquals(chargingInfrastructureManager.getAllAccessibleChargingSitesInArea(new Coord(0.0,0.0), Double.MAX_VALUE).size(),122);
		// Does infrastructure manager return a particular site?
		assertEquals(chargingInfrastructureManager.getAllAccessibleChargingSitesInArea(this.coordOfSpecificCharger, 1).size(),1);
	}
		
	/*
	 * SITE
	 */
	@Test
	public void testChargingSite(){
		ChargingSite aSite = chargingInfrastructureManager.getAllAccessibleChargingSitesInArea(this.coordOfSpecificCharger, 1).iterator().next();
		
		// Does the site return the expected number of plugs
		assertEquals(aSite.getAllChargingPlugs().size(),1);
		
		HashMap<String, ChargingPlugType> plugTypesByID = new HashMap<>();
		for(ChargingPlugType plugType : chargingInfrastructureManager.getChargingPlugTypes()){
			plugTypesByID.put(plugType.getPlugTypeName(), plugType);
		}
		// Does the site return the expected number of plugs by Type?
		assertEquals(0,aSite.getAccessibleChargingPlugsOfChargingPlugType(plugTypesByID.get("chademo")).size());
		assertEquals(0,aSite.getAccessibleChargingPlugsOfChargingPlugType(plugTypesByID.get("j-1772-1")).size());
		assertEquals(1,aSite.getAccessibleChargingPlugsOfChargingPlugType(plugTypesByID.get("j-1772-2")).size());
		assertEquals(0,aSite.getAccessibleChargingPlugsOfChargingPlugType(plugTypesByID.get("sae-combo-1")).size());
		assertEquals(0,aSite.getAccessibleChargingPlugsOfChargingPlugType(plugTypesByID.get("sae-combo-2")).size());
		assertEquals(0,aSite.getAccessibleChargingPlugsOfChargingPlugType(plugTypesByID.get("sae-combo-3")).size());
		assertEquals(0,aSite.getAccessibleChargingPlugsOfChargingPlugType(plugTypesByID.get("tesla-1")).size());
		assertEquals(0,aSite.getAccessibleChargingPlugsOfChargingPlugType(plugTypesByID.get("tesla-2")).size());
		assertEquals(0,aSite.getAccessibleChargingPlugsOfChargingPlugType(plugTypesByID.get("tesla-3")).size());
	}
	
	/*
	 * PLUG
	 */
	@Test
	public void testChargingPlug(){
		ChargingSite aSite = chargingInfrastructureManager.getAllAccessibleChargingSitesInArea(this.coordOfSpecificCharger, 1).iterator().next();
		// Does the plug have the correct status?
		ChargingPlug thePlug = aSite.getAllChargingPlugs().iterator().next();
		assertEquals(thePlug.getChargingPlugStatus(),ChargingPlugStatus.AVAILABLE);
	}
	
}
