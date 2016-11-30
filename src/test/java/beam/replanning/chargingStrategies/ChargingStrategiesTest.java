package beam.replanning.chargingStrategies;

import org.junit.Before;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;

import beam.EVGlobalData;
import beam.EVSimTeleController;
import beam.TestUtilities;
import beam.charging.infrastructure.ChargingInfrastructureManagerImpl;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.transEnergySim.vehicles.api.Vehicle;
import junit.framework.TestCase;

public class ChargingStrategiesTest {
	protected EVSimTeleController evSimTeleController = new EVSimTeleController();
	 private ChargingInfrastructureManagerImpl chargingInfrastructureManager;

	@Before
	 public void setUp() {
		TestUtilities.setConfigFile("ChargingStrategiesTestConfig.xml");
		TestUtilities.setTestInputDirectory(ChargingStrategiesTest.class);
		
		evSimTeleController.init();
		evSimTeleController.attachLinkTree();
		this.chargingInfrastructureManager = new ChargingInfrastructureManagerImpl();
		EVGlobalData.data.chargingInfrastructureManager = this.chargingInfrastructureManager;
	 }

	/* TODO we probably need to test strategies during integration testing because it's dependent on agents and their plans which is non-trivial to initialize with dummies
	 * 
	public void testChargingStrategyNestedLogit(){
		PlugInVehicleAgent agent = PlugInVehicleAgent.getAgent(Id.createPersonId(1));
		agent.updateActivityTrackingOnStart();
		ChargingStrategyNestedLogit strategy = new ChargingStrategyNestedLogit();
		
		Activity activity = agent.getNextActivity();
		activity.setEndTime(20);
		activity.setMaximumDuration(50);
		activity.setStartTime(10.0);
		activity.setType("work");
		
		String parameterStringAsXML = "<parameters>"
			+ "<nestedLogit name=\"arrival\">"
			+ "	<elasticity>1</elasticity>"
	        + "  <nestedLogit name=\"yesCharge\">"
	        + "   <elasticity>1</elasticity>"
			+ "   <nestedLogit name=\"genericSitePlugTypeAlternative\">"
			+ "			<elasticity>1</elasticity>"
			+ "			<utility>"
			+ "				<param name=\"intercept\" type=\"INTERCEPT\">999999.0</param>"
			+ "				<param name=\"cost\" type=\"MULTIPLIER\">1.0</param>"
			+ "				<param name=\"chargerCapacity\" type=\"MULTIPLIER\">1.0</param>"
			+ "				<param name=\"distanceToActivity\" type=\"MULTIPLIER\">1.0</param>"
			+ "				<param name=\"remainingRange\" type=\"MULTIPLIER\">1.0</param>"
			+ "				<param name=\"remainingTravelDistanceInDay\" type=\"MULTIPLIER\">1.0</param>"
			+ "				<param name=\"nextTripTravelDistance\" type=\"MULTIPLIER\">1.0</param>"
			+ "				<param name=\"plannedDwellTime\" type=\"MULTIPLIER\">1.0</param>"
			+ "				<param name=\"isHomeActivity\" type=\"MULTIPLIER\">1.0</param>"
			+ "				<param name=\"isBEV\" type=\"MULTIPLIER\">1.0</param>"
			+ "			</utility>"
			+ "	 </nestedLogit>"
			+ "	</nestedLogit>"
			+ "	<nestedLogit name=\"noCharge\">"
			+ "		<elasticity>1</elasticity>"
			+ "			<nestedLogit name=\"tryAgainLater\">"
			+ "				<elasticity>0.5</elasticity>"
			+ "				<utility>"
			+ "					<param name=\"intercept\" type=\"INTERCEPT\">-1.0</param>"
			+ "					<param name=\"remainingRange\" type=\"MULTIPLIER\">1.0</param>"
			+ "					<param name=\"remainingTravelDistanceInDay\" type=\"MULTIPLIER\">1.0</param>"
			+ "					<param name=\"nextTripTravelDistance\" type=\"MULTIPLIER\">1.0</param>"
			+ "					<param name=\"plannedDwellTime\" type=\"MULTIPLIER\">1.0</param>"
			+ "					<param name=\"searchRadius\" type=\"MULTIPLIER\">1.0</param>"
			+ "					<param name=\"isWorkActivity\" type=\"MULTIPLIER\">1.0</param>"
			+ "					<param name=\"isHomeActivity\" type=\"MULTIPLIER\">1.0</param>"
			+ "					<param name=\"isBEV\" type=\"MULTIPLIER\">1.0</param>"
			+ "				</utility>"
			+ "			</nestedLogit>"
			+ "			<nestedLogit name=\"seekFartherAway\">"
			+ "				<elasticity>0.5</elasticity>"
			+ "				<utility>"
			+ "					<param name=\"intercept\" type=\"INTERCEPT\">-1.0</param>"
			+ "					<param name=\"remainingRange\" type=\"MULTIPLIER\">1.0</param>"
			+ "					<param name=\"remainingTravelDistanceInDay\" type=\"MULTIPLIER\">1.0</param>"
			+ "					<param name=\"nextTripTravelDistance\" type=\"MULTIPLIER\">1.0</param>"
			+ "					<param name=\"plannedDwellTime\" type=\"MULTIPLIER\">1.0</param>"
			+ "					<param name=\"searchRadius\" type=\"MULTIPLIER\">1.0</param>"
			+ "					<param name=\"isWorkActivity\" type=\"MULTIPLIER\">1.0</param>"
			+ "					<param name=\"isHomeActivity\" type=\"MULTIPLIER\">1.0</param>"
			+ "					<param name=\"isBEV\" type=\"MULTIPLIER\">1.0</param>"
			+ "				</utility>"
			+ "			</nestedLogit>"
			+ "			<nestedLogit name=\"abandon\">"
			+ "				<elasticity>0.5</elasticity>"
			+ "				<utility>"
			+ "					<param name=\"intercept\" type=\"INTERCEPT\">-1.0</param>"
			+ "					<param name=\"remainingRange\" type=\"MULTIPLIER\">1.0</param>"
			+ "					<param name=\"remainingTravelDistanceInDay\" type=\"MULTIPLIER\">1.0</param>"
			+ "					<param name=\"nextTripTravelDistance\" type=\"MULTIPLIER\">1.0</param>"
			+ "					<param name=\"plannedDwellTime\" type=\"MULTIPLIER\">1.0</param>"
			+ "					<param name=\"searchRadius\" type=\"MULTIPLIER\">1.0</param>"
			+ "					<param name=\"isWorkActivity\" type=\"MULTIPLIER\">1.0</param>"
			+ "					<param name=\"isHomeActivity\" type=\"MULTIPLIER\">1.0</param>"
			+ "					<param name=\"isBEV\" type=\"MULTIPLIER\">1.0</param>"
			+ "				</utility>"
			+ "			</nestedLogit>"
			+ "	</nestedLogit>"
			+ "</nestedLogit>"
			+ "<nestedLogit name=\"departure\">"
			+ "	<elasticity>1</elasticity>"
	        + "  <nestedLogit name=\"yesCharge\">"
	        + "   <elasticity>1</elasticity>"
			+ "   <nestedLogit name=\"genericSitePlugTypeAlternative\">"
			+ "		<elasticity>1</elasticity>"
			+ "		<utility>"
			+ "			<param name=\"intercept\" type=\"INTERCEPT\">999999.0</param>"
			+ "			<param name=\"cost\" type=\"MULTIPLIER\">1.0</param>"
			+ "			<param name=\"chargerCapacity\" type=\"MULTIPLIER\">1.0</param>"
			+ "			<param name=\"distanceToActivity\" type=\"MULTIPLIER\">1.0</param>"
			+ "			<param name=\"remainingRange\" type=\"MULTIPLIER\">1.0</param>"
			+ "			<param name=\"remainingTravelDistanceInDay\" type=\"MULTIPLIER\">1.0</param>"
			+ "			<param name=\"nextTripTravelDistance\" type=\"MULTIPLIER\">1.0</param>"
			+ "			<param name=\"plannedDwellTime\" type=\"MULTIPLIER\">1.0</param>"
			+ "			<param name=\"isHomeActivity\" type=\"MULTIPLIER\">1.0</param>"
			+ "			<param name=\"isBEV\" type=\"MULTIPLIER\">1.0</param>"
			+ "		</utility>"
			+ "	 </nestedLogit>"
			+ "	</nestedLogit>"
			+ "	<nestedLogit name=\"noCharge\">"
			+ "		<elasticity>1</elasticity>"
			+ "			<utility>"
			+ "				<param name=\"intercept\" type=\"INTERCEPT\">-1.0</param>"
			+ "				<param name=\"remainingRange\" type=\"MULTIPLIER\">1.0</param>"
			+ "				<param name=\"remainingTravelDistanceInDay\" type=\"MULTIPLIER\">1.0</param>"
			+ "				<param name=\"nextTripTravelDistance\" type=\"MULTIPLIER\">1.0</param>"
			+ "				<param name=\"plannedDwellTime\" type=\"MULTIPLIER\">1.0</param>"
			+ "				<param name=\"searchRadius\" type=\"MULTIPLIER\">1.0</param>"
			+ "				<param name=\"isWorkActivity\" type=\"MULTIPLIER\">1.0</param>"
			+ "				<param name=\"isHomeActivity\" type=\"MULTIPLIER\">1.0</param>"
			+ "				<param name=\"isBEV\" type=\"MULTIPLIER\">1.0</param>"
			+ "			</utility>"
			+ "		</nestedLogit>"
			+ "	</nestedLogit>"
			+ "</nestedLogit>"
			+ "</parameters>";
		strategy.setParameters(parameterStringAsXML);
		assertEquals(true,strategy.hasChosenToChargeOnArrival(agent));
		strategy.chooseAdaptationAlternativeOnArrival(agent);
	}
	*/
		
}
