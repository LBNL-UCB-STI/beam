package beam.charging.vehicle;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Identifiable;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.api.experimental.events.TeleportationArrivalEvent;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.qsim.InternalInterface;
import org.matsim.core.mobsim.qsim.agents.BasicPlanAgentImpl;
import org.matsim.core.mobsim.qsim.agents.PersonDriverAgentImpl;
import org.matsim.core.network.SearchableNetwork;

import beam.EVGlobalData;
import beam.charging.infrastructure.ChargingInfrastructureManagerImpl;
import beam.charging.infrastructure.ChargingPlugImpl;
import beam.charging.infrastructure.ChargingPointImpl;
import beam.charging.infrastructure.ChargingSiteImpl;
import beam.events.ArrivalChargingDecisionEvent;
import beam.events.DepartureChargingDecisionEvent;
import beam.events.NestedLogitDecisionEvent;
import beam.events.ParkWithoutChargingEvent;
import beam.events.ReassessDecisionEvent;
import beam.parking.lib.DebugLib;
import beam.parking.lib.GeneralLib;
import beam.playground.PlaygroundFun;
import beam.replanning.ChargingStrategy;
import beam.replanning.ChargingStrategyManager;
import beam.replanning.chargingStrategies.ChargingStrategyNestedLogit;
import beam.sim.SearchAdaptationAlternative;
import beam.sim.scheduler.CallBack;
import beam.sim.traveltime.RouteInformationElement;
import beam.sim.traveltime.TripInformation;
import beam.transEnergySim.agents.VehicleAgent;
import beam.transEnergySim.chargingInfrastructure.management.ChargingNetworkOperator;
import beam.transEnergySim.chargingInfrastructure.management.ChargingSitePolicy;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugType;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPoint;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;
import beam.transEnergySim.vehicles.api.BatteryElectricVehicle;
import beam.transEnergySim.vehicles.api.VehicleWithBattery;

import java.lang.reflect.Field;
import java.util.*;

// TODO: push abstract up hierarchie
// TODO: rename this class!!! 
public class PlugInVehicleAgent implements VehicleAgent, Identifiable<PlugInVehicleAgent> {
	private static final Logger log = Logger.getLogger(PlugInVehicleAgent.class);
	private static InternalInterface internalInterface;

	private Plan selectedPlan;
	int currentPlanElementIndex;
	private Person person;
	private Id<PlugInVehicleAgent> agentId;
	private MobsimAgent mobsimAgent;
	double currentSearchRadius;
	private ChargingPlug selectedChargingPlug;
	private Double remainingTravelDistanceInDayInMeters;
	private String vehicleType;
	private AgentChargingState chargingState = AgentChargingState.PARKED;
	private VehicleWithBattery vehicleWithBattery;
	private CallBack nextScheduledCallback;

	private Coord coord;
	private Activity previousActivity, nextActivity, currentActivity;
	private Leg previousLeg, nextLeg, currentLeg;
	private TripInformation tripInfoAsOfDeparture;
	private boolean shouldDepartAfterChargingSession = false;
	private Id<Link> currentLinkId;

	private static int decisionEventIdCounter = 0;
	private int currentDecisionEventId;
	private Coord homeLinkCoord;
	private ChargingSiteImpl homeSite;
	private ChargingPointImpl homePoint;
	private ChargingPlugImpl homePlug;

	public int createNewDecisionEventId() {
		currentDecisionEventId = decisionEventIdCounter++;
		return currentDecisionEventId;
	}

	public PlugInVehicleAgent(Id<Person> personId) {
		this.agentId = Id.create(personId, PlugInVehicleAgent.class);
		this.person = EVGlobalData.data.controler.getScenario().getPopulation().getPersons().get(personId);
		this.currentLinkId = ((Activity) getSelectedPersonPlan().getPlanElements().get(0)).getLinkId();
	}

	public void configureHomeCharger() {
		SearchableNetwork network = (SearchableNetwork) EVGlobalData.data.controler.getScenario().getNetwork();
		Link homeLink = null;
		for (PlanElement elem : getSelectedPersonPlan().getPlanElements()) {
			if (elem instanceof Activity) {
				if (((Activity) elem).getType().equals("Home")) {
					homeLink = network.getNearestLinkExactly(((Activity) elem).getCoord());
					this.homeLinkCoord = homeLink.getCoord();
				}
			}
		}
		HashMap<String, String> homeProperties = EVGlobalData.data.personHomeProperties.get(this.person.getId().toString());
		if (this.homeLinkCoord != null && !homeProperties.get("homeChargingPlugTypeId").trim().equals("")) {
			ChargingSitePolicy homeChargingPolicy = EVGlobalData.data.chargingInfrastructureManager
					.getChargingSitePolicyById(homeProperties.get("homeChargingPolicyId"));
			ChargingNetworkOperator homeNetworkOperator = EVGlobalData.data.chargingInfrastructureManager
					.getChargingNetworkOperatorById(homeProperties.get("homeChargingNetworkOperatorId"));
			this.homeSite = new ChargingSiteImpl(Id.create("-" + this.getPersonId(), ChargingSite.class), this.homeLinkCoord, homeChargingPolicy,
					homeNetworkOperator, true);
			this.homeSite.setNearestLink(homeLink);
			EVGlobalData.data.chargingInfrastructureManager.addChargingSite(this.homeSite.getId().toString(), this.homeSite);
			this.homePoint = new ChargingPointImpl(Id.create("-" + this.getPersonId(), ChargingPoint.class), this.homeSite, 1);
			ChargingPlugType homePlugType = null;
			if (homeProperties.get("homeChargingPlugTypeId").trim().equals("")) {
				for (ChargingPlugType plugType : this.vehicleWithBattery.getCompatiblePlugTypes()) {
					if (plugType.getNominalLevel() == 2) {
						homePlugType = plugType;
						break;
					} else if (plugType.getNominalLevel() == 1) {
						homePlugType = plugType;
					}
				}
				if (homePlugType == null)
					DebugLib.stopSystemAndReportInconsistency("Agent " + this.person.getId() + " with vehicle " + this.vehicleType
							+ " does not have a compatible plug type that is less than Level 2 or 1, which is what is expected for assigning a home charger.");
			} else {
				homePlugType = EVGlobalData.data.chargingInfrastructureManager.getChargingPlugTypeById(homeProperties.get("homeChargingPlugTypeId"));
			}
			this.homePlug = new ChargingPlugImpl(Id.create("-" + this.getPersonId(), ChargingPlug.class), this.homePoint, homePlugType);
			this.homePoint.createSlowChargingQueue(1);
		}
	}

	public PersonDriverAgentImpl getPersonDriverAgentImpl() {
		return (PersonDriverAgentImpl) getMobsimAgent();
	}

	public AgentChargingState getChargingState() {
		return chargingState;
	}

	public void setChargingState(AgentChargingState chargingState) {
		this.chargingState = chargingState;
	}

	public String getVehicleType() {
		return vehicleType;
	}

	public void setVehicleType(String vehicleType) {
		this.vehicleType = vehicleType;
	}

	public ChargingPlug getSelectedChargingPlug() {
		return this.selectedChargingPlug;
	}

	public void setSelectedChargingPlug(ChargingPlug plug) {
		this.selectedChargingPlug = plug;
	}

	public VehicleWithBattery getVehicleWithBattery() {
		return vehicleWithBattery;
	}

	public int getCurrentLegIndex() {
		return currentPlanElementIndex;
	}

	public double getBatteryCapacity() {
		return getVehicleWithBattery().getUsableBatteryCapacityInJoules();
	}

	public double getSoC() {
		return getVehicleWithBattery().getSocInJoules();
	}

	public double getSoCAsFraction() {
		return getVehicleWithBattery().getSocInJoules() / getVehicleWithBattery().getUsableBatteryCapacityInJoules();
	}

	public Plan getSelectedPersonPlan() {
		return this.person.getSelectedPlan();
	}

	public void performChargingDecisionAlgorithmOnArrival() {
		if(getId().toString().equals("1171641") && EVGlobalData.data.controler.getIterationNumber() == 3){
			DebugLib.emptyFunctionForSettingBreakPoint();
		}
		try {
			//TODO we need more robust way of tracking where we are in the plan and pulling the appropriate strategy
			ChargingStrategy chargingStrategyForThisLeg = ChargingStrategyManager.data.getReplanable(person.getId()).getSelectedEvDailyPlan().getChargingStrategyForLeg(getCurrentLegIndex());
			if (chargingStrategyForThisLeg == null)chargingStrategyForThisLeg = ChargingStrategyManager.data.getReplanable(person.getId()).getSelectedEvDailyPlan().getChargingStrategyForLeg(getCurrentLegIndex() - 1);
			ChargingPlug chosenPlug;

			currentSearchRadius = EVGlobalData.data.INITIAL_SEARCH_RADIUS;
			boolean stillSearching = true;
			while (stillSearching) {
				chosenPlug = null;
				if (chargingStrategyForThisLeg.hasChosenToChargeOnArrival(this)) {
					chosenPlug = chargingStrategyForThisLeg.getChosenChargingAlternativeOnArrival(this);
					if (chosenPlug != null) {
						setSelectedChargingPlug(chosenPlug);
						stillSearching = false;
					}
				}
				if (chosenPlug == null) {
					if (chargingStrategyForThisLeg.getChosenAdaptationAlternativeOnArrival(this) == SearchAdaptationAlternative.CONTINUE_SEARCH_IN_LARGER_AREA) {
						extendSearchArea();
						if (this.currentSearchRadius > EVGlobalData.data.MAX_SEARCH_RADIUS) {
							setChargingState(AgentChargingState.PARKED);
							chargingStrategyForThisLeg.setSearchAdaptationDecisionOnArrival(SearchAdaptationAlternative.ABORT);
							stillSearching = false;
						}
					} else if (chargingStrategyForThisLeg.getChosenAdaptationAlternativeOnArrival(this) == SearchAdaptationAlternative.TRY_CHARGING_LATER) {
						setChargingState(AgentChargingState.PARKED);
						if(isInLastActivity()){
							chargingStrategyForThisLeg.setSearchAdaptationDecisionOnArrival(SearchAdaptationAlternative.ABORT);
						}else{
							// TRY_AGAIN_LATER
							scheduleTryAgainLater();
						}
						stillSearching = false;
					} else if (chargingStrategyForThisLeg.getChosenAdaptationAlternativeOnArrival(this) == SearchAdaptationAlternative.ABORT) {
						setChargingState(AgentChargingState.PARKED);
						stillSearching = false;
					} else {
						DebugLib.stopSystemAndReportInconsistency(chargingStrategyForThisLeg.getChosenAdaptationAlternativeOnArrival(this).toString());
					}
				}
			}
			// Logging the decision
			ArrivalChargingDecisionEvent decisionEvent = new ArrivalChargingDecisionEvent(EVGlobalData.data.now, this, chargingStrategyForThisLeg);
			EVGlobalData.data.eventLogger.processEvent(decisionEvent);
			if(chargingStrategyForThisLeg instanceof ChargingStrategyNestedLogit){
				EVGlobalData.data.eventLogger.processEvent(new NestedLogitDecisionEvent(EVGlobalData.data.now, decisionEvent.getDecisionEventId(), (ChargingStrategyNestedLogit) chargingStrategyForThisLeg));
			} 

			if(getSelectedChargingPlug() != null && this.currentActivity !=null){
				beginChargeEvent();
			}else if (getSelectedChargingPlug() == null) {
				EVGlobalData.data.eventLogger.processEvent(new ParkWithoutChargingEvent(EVGlobalData.data.now, this));
				EVGlobalData.data.scheduler.addCallBackMethod(EVGlobalData.data.now + EVGlobalData.data.averageWalkingTimeFromParkToActivity, this,
						"beginActivity", 0.0, this);
			} else {
				beginChargeEvent();
				EVGlobalData.data.scheduler.addCallBackMethod(EVGlobalData.data.now + getChargingRelatedOverheadTime(selectedChargingPlug), this,
						"beginActivity", 0.0, this);
			}
		} catch (Exception e) {
			EVGlobalData.data.testingHooks.errorDuringExecution = true;
			log.error(e.getMessage());
			e.printStackTrace();
		}
	}

	public void performChargingDecisionAlgorithmOnDeparture() {
		DebugLib.traceAgent(this.getId(), "6825504");

		ChargingStrategy chargingStrategyForThisLeg = ChargingStrategyManager.data.getReplanable(person.getId()).getSelectedEvDailyPlan().getChargingStrategyForLeg(getCurrentLegIndex());
		ChargingPlug chosenPlug;

		currentSearchRadius = EVGlobalData.data.INITIAL_SEARCH_RADIUS;
		boolean stillSearching = true;
		while (stillSearching) {
			chosenPlug = null;
			if (chargingStrategyForThisLeg.hasChosenToChargeOnDeparture(this)) {
				chosenPlug = chargingStrategyForThisLeg.getChosenChargingAlternativeOnDeparture(this);
				if (chosenPlug != null) {
					setSelectedChargingPlug(chosenPlug);
					stillSearching = false;
					scheduleEnRouteChargingArrival();
				}
			}
			if (chosenPlug == null) {
				if (this.hasEnoughEnergyToMakeTrip()) {
					// ABORT
					stillSearching = false;
					setChargingState(AgentChargingState.TRAVELING);
					chargingStrategyForThisLeg.setSearchAdaptationDecisionOnDeparture(SearchAdaptationAlternative.ABORT);
					scheduleArrival();
				} else {
					// CONTINUE_SEARCH_IN_LARGER_AREA OR STRANDED
					chargingStrategyForThisLeg.setSearchAdaptationDecisionOnDeparture(SearchAdaptationAlternative.CONTINUE_SEARCH_IN_LARGER_AREA);
					extendSearchArea();
					if (!this.hasEnoughEnergyToConsiderHalfSearchRadius() || this.currentSearchRadius > EVGlobalData.data.MAX_SEARCH_RADIUS) {
						chargingStrategyForThisLeg.setSearchAdaptationDecisionOnDeparture(SearchAdaptationAlternative.STRANDED);
						setChargingState(AgentChargingState.STRANDED);
						EVGlobalData.data.eventLogger.processEvent(new PersonStuckEvent(EVGlobalData.data.now, this.getPersonId(), this.currentLinkId,
								EVGlobalData.data.PLUGIN_ELECTRIC_VEHICLES));
						stillSearching = false;
					}
				}
			}
		}
		DepartureChargingDecisionEvent decisionEvent = new DepartureChargingDecisionEvent(EVGlobalData.data.now, this, chargingStrategyForThisLeg);
		EVGlobalData.data.eventLogger.processEvent(decisionEvent);
	} 
	private boolean hasEnoughEnergyToConsiderHalfSearchRadius() {
		return !this.vehicleWithBattery.isBEV() || this.vehicleWithBattery.getSocInJoules() >= 0.5
				* this.vehicleWithBattery.getElectricDriveEnergyConsumptionModel().getEnergyConsumptionRateInJoulesPerMeter()
				* this.getCurrentSearchRadius();
	}

	private void scheduleEnRouteChargingArrival() {
		adjustTravelDistanceInDayForEnRoute();
		this.tripInfoAsOfDeparture = getTripInformation(EVGlobalData.data.now, getCurrentLink(), getSelectedChargingSite().getNearestLink().getId());
		EVGlobalData.data.scheduler.addCallBackMethod(EVGlobalData.data.now + tripInfoAsOfDeparture.getTripTravelTime(), this, "performReassessDecision", 0.0, this);
		setChargingState(AgentChargingState.EN_ROUTE_TO_CHARGER);
	}

	private void adjustTravelDistanceInDayForEnRoute() {
		double origDist, subtrip1Dist, subtrip2Dist; 
		origDist = getTripInfoAsOfDeparture().getTripDistance();
		
		TripInformation subtrip1, subtrip2;
		Id<Link> chargingLinkId = getSelectedChargingSite().getNearestLink().getId();
		subtrip1 = getTripInformation(EVGlobalData.data.now, getTripInfoAsOfDeparture().getRouteInfoElements().getFirst().getLinkId(), chargingLinkId);
		subtrip1Dist = subtrip1.getTripDistance();
		
		double timeOfSecondDeparture = EVGlobalData.data.now + subtrip1.getTripTravelTime() + getSelectedChargingSite().estimateChargingSessionDuration(getSelectedChargingPlug().getChargingPlugType(),this.getVehicleWithBattery());
		subtrip2 = getTripInformation(timeOfSecondDeparture, chargingLinkId, getTripInfoAsOfDeparture().getRouteInfoElements().getLast().getLinkId());
		subtrip2Dist = subtrip2.getTripDistance();
		this.remainingTravelDistanceInDayInMeters += -origDist + subtrip1Dist + subtrip2Dist;
	}

	public Id<Link> getCurrentLink() {
		return this.currentLinkId;
	}

	public Coord getCurrentCoord() {
		return this.coord;
	}

	private void setCurrentCoord(Coord coord) {
		this.coord = coord;
	}

	public void performReassessDecision() {
		if(getId().toString().equals("1171641") && EVGlobalData.data.controler.getIterationNumber() == 3){
			DebugLib.emptyFunctionForSettingBreakPoint();
		}
		updateEnergyUse();
		this.currentLinkId = getSelectedChargingSite().getNearestLink().getId();
		setCurrentCoord(getSelectedChargingSite().getCoord());
		String choice = "engageWithOriginalPlug";
		if (!getSelectedChargingPlug().isAccessible()) {
			Iterator<ChargingPlug> iterator = getSelectedChargingPlug().getChargingSite()
					.getAccessibleChargingPlugsOfChargingPlugType(getSelectedChargingPlug().getChargingPlugType()).iterator();
			if (!iterator.hasNext()) {
				iterator = getSelectedChargingPlug().getChargingSite().getAllAccessibleChargingPlugs().iterator();
			}
			if (!iterator.hasNext()) {
				abortReassessDecision();
				return;
			}

			ChargingPlug chargingPlug = null;
			if (ChargingInfrastructureManagerImpl.isSocAbove80Pct(this)) {
				boolean plugFound = false;
				while (iterator.hasNext()) {
					chargingPlug = iterator.next();

					if (!ChargingInfrastructureManagerImpl.isFastCharger(chargingPlug.getChargingPlugType())) {
						plugFound = true;
						break;
					}
				}

				if (!plugFound) {
					abortReassessDecision();
					return;
				}

			} else {
				chargingPlug = iterator.next();
			}

			choice = "engageWithAlternatePlug";
			setSelectedChargingPlug(chargingPlug);
		}
		EVGlobalData.data.eventLogger.processEvent(new ReassessDecisionEvent(EVGlobalData.data.now, this, choice));
		beginChargeEvent();
		this.shouldDepartAfterChargingSession = true;
	}

	private void abortReassessDecision() {
		EVGlobalData.data.eventLogger.processEvent(new ReassessDecisionEvent(EVGlobalData.data.now, this, "abort"));
		this.setSelectedChargingPlug(null);
		this.tripInfoAsOfDeparture = getTripInformation(EVGlobalData.data.now, this.getCurrentLink(), this.nextActivity.getLinkId());
		performChargingDecisionAlgorithmOnDeparture();
	}

	private boolean hasEnoughEnergyToMakeTrip() {
		return !this.vehicleWithBattery.isBEV() || this.vehicleWithBattery.getSocInJoules() >= this.tripInfoAsOfDeparture
				.getTripEnergyConsumption(this.vehicleWithBattery.getElectricDriveEnergyConsumptionModel());
	}

	private void scheduleTryAgainLater() {
		/*
		 * TODO find nearest charging site and use
		 * ChargingSite.estimateTimeUntilAvailableCharger which will use a
		 * simple heuristic, e.g.: min over all plugs of # vehicles in queue *
		 * average charge time by level (avg. charge time can be based on
		 * plugshare or chargepoint data)
		 */
		// TODO make this a config param
		Double waitTime = 1800.0;
		// Checkt to make sure the decision is not performed after the
		// next/current activity completes
		if (getCurrentOrNextActivity().getEndTime() - EVGlobalData.data.now > waitTime + EVGlobalData.data.CHARGING_STATION_USE_CONSTANT_OVERHEAD_TIME) {
			EVGlobalData.data.scheduler.addCallBackMethod(EVGlobalData.data.now + waitTime, this, "performChargingDecisionAlgorithmOnArrival", 0.0,this);
		}
	}

	// push method up
	private void extendSearchArea() {
		currentSearchRadius *= EVGlobalData.data.SEARCH_RADIUS_INCREMENTATION_FACTOR;
	}

	private static HashMap<Id, PlugInVehicleAgent> agents = null;

	public static void resetDecisionEventIdCounter(){
		decisionEventIdCounter = 0;
	}
	
	public static void init() {
		agents = new HashMap<>();
		resetDecisionEventIdCounter();
	}

	public static PlugInVehicleAgent getAgent(Id<Person> id) {
		if (!agents.containsKey(id)) {
			PlugInVehicleAgent agent = new PlugInVehicleAgent(id);
			agents.put(id, agent);
		}
		return agents.get(id);
	}

	public void beginChargeEvent() {
		EVGlobalData.data.chargingInfrastructureManager.handleBeginChargeEvent(selectedChargingPlug, this);
	}

	private void scheduleBeginChargingSession(double when) {
		this.nextScheduledCallback = EVGlobalData.data.scheduler.addCallBackMethod(when, this, "beginChargingSession", 0.0, this);
	}

	public void beginChargingSession() {
		if (selectedChargingPlug == null) {
			// TODO this was to avoid a rare nullPointer, but the logical flaw
			// causing it should be debugged instead
			log.warn("Method PlugInVehicleAgent::beginChargingSession called but selectedChargingPlug is null for agent " + this.getPersonId());
			return;
		}
		EVGlobalData.data.chargingInfrastructureManager.handleBeginChargingSession(selectedChargingPlug, this);
	}

	private void decrementRemainingTravelInDay(double distance) {
		if (this.remainingTravelDistanceInDayInMeters == null) {
			setEstimatedTravelDistanceInDay();
		}
		this.remainingTravelDistanceInDayInMeters -= distance;
		if (this.remainingTravelDistanceInDayInMeters < -EVGlobalData.data.EQUALITY_EPSILON) {
			log.warn("Field PlugInVehicleAgent::remainingTravelDistanceInDayInMeters was negative " + this.remainingTravelDistanceInDayInMeters);
		}
	}

	public String toString() {
		return "Agent " + this.person.getId() + " (" + this.chargingState + ")";
	}

	public double getCurrentSearchRadius() {
		return this.currentSearchRadius;
	}

	public Double getCurrentSearchRadiusInMiles() {
		return getCurrentSearchRadius() / 1609.34;
	}

	public double getChargingRelatedOverheadTime() {
		if (selectedChargingPlug == null) {
			return 0;
		} else {
			return getChargingRelatedOverheadTime(selectedChargingPlug);
		}
	}

	private double getChargingRelatedOverheadTime(ChargingPlug chargingPlug) {
		Coord currentActCoord = this.getLinkCoordOfNextActivity();

		double distanceToChargingStation = getDistanceToChargingStation(currentActCoord, chargingPlug);

		return EVGlobalData.data.CHARGING_STATION_USE_CONSTANT_OVERHEAD_TIME + getWalkTime(distanceToChargingStation);
	}

	private double getWalkTime(double distanceToChargingStation) {
		return distanceToChargingStation / EVGlobalData.data.AVERAGE_WALK_SPEED_TO_CHARGING_STATION
				* EVGlobalData.data.WALK_DISTANCE_ADJUSTMENT_FACTOR;
	}

	private double getDistanceToChargingStation(Coord currentActCoord, ChargingPlug chargingPlug) {
		Coord currentChargingSiteCoord = chargingPlug.getChargingPoint().getChargingSite().getCoord();
		double distanceToChargingStation = GeneralLib.getDistance(currentActCoord, currentChargingSiteCoord);
		return distanceToChargingStation;
	}

	public double getCurrentOrNextActivityDuration() {
		return (getCurrentOrNextActivity().getMaximumDuration() < 0.0 ? EVGlobalData.data.AVERAGE_ENDING_ACTIVITY_DURATION : getCurrentOrNextActivity().getMaximumDuration());
	}

	// TODO confirm that units of Coords here are appropriate / configurable /
	// known to user
	public Double getDistanceToNextActivity(Coord currentLocation) {
		Coord linkCoordOfNextActivity = getLinkCoordOfNextActivity();
		return Math.pow(Math.pow((currentLocation.getX() - linkCoordOfNextActivity.getX()), 2.0)
				+ Math.pow((currentLocation.getY() - linkCoordOfNextActivity.getY()), 2.0), 0.5);
	}

	public Double getDistanceToNextActivityInMiles(Coord currentLocation) {
		return getDistanceToNextActivity(currentLocation) / 1609.34;
	}

	public double getRemainingTravelDistanceInDayInMeters() {
		if (this.remainingTravelDistanceInDayInMeters == null) {
			setEstimatedTravelDistanceInDay();
		}
		return this.remainingTravelDistanceInDayInMeters;
	}

	public Double getRemainingTravelDistanceInDayInMiles() {
		return getRemainingTravelDistanceInDayInMeters() / 1609.34;
	}

	public void setEstimatedTravelDistanceInDay() {
		if (this.chargingState != AgentChargingState.TRAVELING) {
			this.remainingTravelDistanceInDayInMeters = 0.0;
		}
		List<PlanElement> planElements = getSelectedPersonPlan().getPlanElements();
		Activity lastActivity = null, nextActivity = null;
		int i = 0;
		while (true) {
			if (getCurrentLegIndex() + i + 1 > planElements.size())
				break;
			PlanElement elem = planElements.get(getCurrentLegIndex() + i);
			if ((elem instanceof Activity)) {
				lastActivity = (Activity) elem;
			} else if (elem instanceof Leg && lastActivity != null) {
				int departDay = (new Double(Math.ceil((lastActivity.getEndTime() - EVGlobalData.data.timeMarkingNewDay) / 86400.0))).intValue();
				if (departDay == EVGlobalData.data.currentDay) {
					nextActivity = (Activity) planElements.get(getCurrentLegIndex() + i + 1);
					this.remainingTravelDistanceInDayInMeters += getTripInformation(lastActivity.getEndTime(), lastActivity.getLinkId(),
							nextActivity.getLinkId()).getTripDistance();
				} else if (departDay > EVGlobalData.data.currentDay) {
					break;
				}
				lastActivity = null;
			}
			i++;
		}
	}

	public void registerArrival() {

	}

	// TODO verify this is correct
	public Double getNextLegTravelDistanceInMeters() {
		if (getActivityFollowingCurrentPlanElement(2) == null)
			return 0.0;
		return getTripInformation(nextLeg.getDepartureTime(), this.nextActivity.getLinkId(), getActivityFollowingCurrentPlanElement(2).getLinkId())
				.getTripDistance();
	}

	public Double getNextLegTravelDistanceInMiles() {
		return getNextLegTravelDistanceInMeters() / 1609.34;
	}

	public boolean isBEV() {
		return vehicleWithBattery instanceof BatteryElectricVehicle;
	}

	public double getOnTheWayChargingSessionDuration() {
		if (selectedChargingPlug == null) {
			DebugLib.emptyFunctionForSettingBreakPoint();
		}
		Link originLink = this.selectedChargingPlug.getChargingSite().getNearestLink();
		Link destinationLink = EVGlobalData.data.controler.getScenario().getNetwork().getLinks().get(this.nextActivity.getLinkId());
		double minChargingTimeToCompleteTrip = this.getVehicleWithBattery()
				.getRequiredEnergyInJoulesToDriveDistance(getTripInformation(EVGlobalData.data.now, originLink, destinationLink).getTripDistance())
				/ selectedChargingPlug.getActualChargingPowerInWatt();

		return Math.min(minChargingTimeToCompleteTrip, (getBatteryCapacity() - getSoC()) / selectedChargingPlug.getActualChargingPowerInWatt());
	}

	public Coord getLinkCoordOfCurrentOrNextActivity() {
		SearchableNetwork network = (SearchableNetwork) EVGlobalData.data.controler.getScenario().getNetwork();
		return network.getNearestLinkExactly(getCurrentOrNextActivity().getCoord()).getCoord();
	}

	public Coord getLinkCoordOfNextActivity() {
		if(this.nextActivity==null)return getLinkCoordOfCurrentOrNextActivity();
		SearchableNetwork network = (SearchableNetwork) EVGlobalData.data.controler.getScenario().getNetwork();
		return network.getNearestLinkExactly(this.nextActivity.getCoord()).getCoord();
	}

	public Coord getLinkCoordOfPreviouActivity() {
		SearchableNetwork network = (SearchableNetwork) EVGlobalData.data.controler.getScenario().getNetwork();
		return network.getNearestLinkExactly(this.previousActivity.getCoord()).getCoord();
	}

	// TODO verify
	public double[] getExtraEnergyAndTimeToChargeAt(ChargingSite site, ChargingPlugType plugType) {
		double origEnergy, origTime, subtrip1Energy, subtrip1Time, subtrip2Energy, subtrip2Time; 
		origTime = getTripInfoAsOfDeparture().getTripTravelTime();
		origEnergy = getTripInfoAsOfDeparture().getTripEnergyConsumption(this.vehicleWithBattery.getElectricDriveEnergyConsumptionModel());
		
		TripInformation subtrip1, subtrip2;
		subtrip1 = getTripInformation(EVGlobalData.data.now, getTripInfoAsOfDeparture().getRouteInfoElements().getFirst().getLinkId(), site.getNearestLink().getId());
		subtrip1Time = subtrip1.getTripTravelTime();
		subtrip1Energy = subtrip1.getTripEnergyConsumption(this.vehicleWithBattery.getElectricDriveEnergyConsumptionModel());
		
		double timeOfSecondDeparture = EVGlobalData.data.now + subtrip1Time + site.estimateChargingSessionDuration(plugType,this.getVehicleWithBattery());
		subtrip2 = getTripInformation(timeOfSecondDeparture, site.getNearestLink().getId(), getTripInfoAsOfDeparture().getRouteInfoElements().getLast().getLinkId());
		subtrip2Time = subtrip2.getTripTravelTime();
		subtrip2Energy = subtrip2.getTripEnergyConsumption(this.vehicleWithBattery.getElectricDriveEnergyConsumptionModel());
		return new double[] { subtrip1Energy + subtrip2Energy - origEnergy, timeOfSecondDeparture + subtrip2Time - EVGlobalData.data.now - origTime };
	}

	public TripInformation getTripInformation(double time, Id<Link> fromId, Id<Link> toId) {
		return getTripInformation(time, EVGlobalData.data.controler.getScenario().getNetwork().getLinks().get(fromId),
				EVGlobalData.data.controler.getScenario().getNetwork().getLinks().get(toId));
	}

	public TripInformation getTripInformation(double now, Link startLink, Link endLink) {
		return EVGlobalData.data.router.getTripInformation(now, startLink, endLink);
	}

	public ChargingSite getSelectedChargingSite() {
		return this.selectedChargingPlug.getChargingSite();
	}

	public void handleDeparture() {
		if(getId().toString().equals("1171641") && EVGlobalData.data.controler.getIterationNumber() == 3){
			DebugLib.emptyFunctionForSettingBreakPoint();
		}
		if(this.chargingState == AgentChargingState.STRANDED)return;
		if (this.chargingState == AgentChargingState.PRE_CHARGE || this.chargingState == AgentChargingState.CHARGING || this.chargingState == AgentChargingState.POST_CHARGE_PLUGGED || this.chargingState == AgentChargingState.POST_CHARGE_UNPLUGGED) {
			EVGlobalData.data.chargingInfrastructureManager.registerVehicleDeparture(selectedChargingPlug, this);
			if (this.chargingState == AgentChargingState.PRE_CHARGE || this.chargingState == AgentChargingState.CHARGING ){
				EVGlobalData.data.chargingInfrastructureManager.interruptChargingEvent(selectedChargingPlug, this);
				EVGlobalData.data.scheduler.removeCallback(this.nextScheduledCallback);
			}
			if(this.chargingState != AgentChargingState.PRE_CHARGE && this.chargingState != AgentChargingState.POST_CHARGE_UNPLUGGED){
				selectedChargingPlug.unplugVehicle(getVehicleWithBattery());
			}
			selectedChargingPlug = null;
		}
		if (!this.shouldDepartAfterChargingSession) {
			updateActivityTrackingOnEnd();
			updateTravelInformationOnDeparture();
		} else {
			this.tripInfoAsOfDeparture = getTripInformation(EVGlobalData.data.now, this.getCurrentLink(), this.nextActivity.getLinkId());
			this.shouldDepartAfterChargingSession = false;
		}
		performChargingDecisionAlgorithmOnDeparture();
	}

	public void handleArrival() {
		if(getId().toString().equals("1171641") && EVGlobalData.data.controler.getIterationNumber() == 3){
			DebugLib.emptyFunctionForSettingBreakPoint();
		}
		this.currentLinkId = this.nextActivity.getLinkId();
		updateEnergyUse();
		decrementRemainingTravelInDay(this.tripInfoAsOfDeparture.getTripDistance());
		performChargingDecisionAlgorithmOnArrival();
	}

	public void beginActivity() {
		try {
			if (this.currentActivity != null)
				return; // Skip if driver already engaged in an activity
			setCurrentCoord(getLinkCoordOfNextActivity());
			getMobsimAgent().notifyArrivalOnLinkByNonNetworkMode(getMobsimAgent().getDestinationLinkId());
			EVGlobalData.data.controler.getEvents().processEvent(
					new TeleportationArrivalEvent(EVGlobalData.data.now, getMobsimAgent().getId(), this.tripInfoAsOfDeparture.getTripDistance()));
			getMobsimAgent().endLegAndComputeNextState(EVGlobalData.data.now);
			PlugInVehicleAgent.internalInterface.arrangeNextAgentState(getMobsimAgent());
			updateActivityTrackingOnStart();
		} catch (Exception e) {
			EVGlobalData.data.testingHooks.errorDuringExecution = true;
			DebugLib.stopSystemAndReportInconsistency(e.getMessage());
		}
	}

	public void updateActivityTrackingOnStart() {
		updateCurrentPlanElementIndex();
		if (this.nextActivity == null)
			this.nextActivity = getActivityFollowingCurrentPlanElement(0);
		this.currentActivity = this.nextActivity;
		this.currentLinkId = this.currentActivity.getLinkId();
		this.nextActivity = getActivityFollowingCurrentPlanElement(1);
		this.previousLeg = this.currentLeg;
		this.currentLeg = null;
		if (this.nextLeg == null)
			this.nextLeg = getLegFollowingCurrentPlanElement(1);
	}

	public void updateActivityTrackingOnEnd() {
		updateCurrentPlanElementIndex();
		this.previousActivity = getActivityPreviousToCurrentPlanElement(-1);
		this.currentActivity = null;
		this.currentLinkId = this.previousActivity.getLinkId();
		if (this.nextActivity == null)
			this.nextActivity = getActivityFollowingCurrentPlanElement(0);
		if (this.nextLeg == null)
			this.nextLeg = getLegFollowingCurrentPlanElement(1);
		this.currentLeg = this.nextLeg;
		this.nextLeg = getLegFollowingCurrentPlanElement(1);
	}

	private Activity getActivityPreviousToCurrentPlanElement(int initialOffset) {
		for (int i = initialOffset; i + this.currentPlanElementIndex >= 0; i--) {
			if (this.selectedPlan.getPlanElements().get(i + this.currentPlanElementIndex) instanceof Activity) {
				return (Activity) this.selectedPlan.getPlanElements().get(i + this.currentPlanElementIndex);
			}
		}
		return null;
	}

	private Activity getActivityFollowingCurrentPlanElement(int initialOffset) {
		for (int i = initialOffset; i + this.currentPlanElementIndex < this.selectedPlan.getPlanElements().size(); i++) {
			if (this.selectedPlan.getPlanElements().get(i + this.currentPlanElementIndex) instanceof Activity) {
				return (Activity) this.selectedPlan.getPlanElements().get(i + this.currentPlanElementIndex);
			}
		}
		return null;
	}

	private Leg getLegFollowingCurrentPlanElement(int initialOffset) {
		for (int i = initialOffset; i + this.currentPlanElementIndex < this.selectedPlan.getPlanElements().size(); i++) {
			if (this.selectedPlan.getPlanElements().get(i) instanceof Leg) {
				return (Leg) this.selectedPlan.getPlanElements().get(i);
			}
		}
		return null;
	}

	private void updateCurrentPlanElementIndex() {
		Field f;
		try {
			f = getPersonDriverAgentImpl().getClass().getDeclaredField("basicAgentDelegate");
			f.setAccessible(true);
			BasicPlanAgentImpl basicPlanAgent = (BasicPlanAgentImpl) f.get(getPersonDriverAgentImpl());
			f = basicPlanAgent.getClass().getDeclaredField("currentPlanElementIndex");
			f.setAccessible(true);
			this.currentPlanElementIndex = (Integer) f.get(basicPlanAgent);
		} catch (NoSuchFieldException e) {
			e.printStackTrace();
		} catch (SecurityException e) {
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		}
	}

	private void updateTravelInformationOnDeparture() {
		this.tripInfoAsOfDeparture = getTripInformation(EVGlobalData.data.now, this.previousActivity.getLinkId(), this.nextActivity.getLinkId());
		this.nextActivity
				.setMaximumDuration(this.nextActivity.getEndTime() - this.previousActivity.getEndTime() - tripInfoAsOfDeparture.getTripTravelTime());
		this.currentLeg.setTravelTime(tripInfoAsOfDeparture.getTripTravelTime());
		this.currentLeg.getRoute().setTravelTime(tripInfoAsOfDeparture.getTripTravelTime());
		this.currentLeg.getRoute().setDistance(tripInfoAsOfDeparture.getTripDistance());
	}

	private void scheduleArrival() {
		this.tripInfoAsOfDeparture = getTripInformation(EVGlobalData.data.now, getCurrentLink(), this.nextActivity.getLinkId());
		double arrivalTime = EVGlobalData.data.now + this.tripInfoAsOfDeparture.getTripTravelTime();

		EVGlobalData.data.scheduler.addCallBackMethod(arrivalTime, this, "handleArrival", 0.0, this);
	}

	// TODO this is duplicative of code already in the vehicle class, should be
	// harmonized but will require some reorganization of TES
	private void updateEnergyUse() {
		double electricEnergyConsumed = this.tripInfoAsOfDeparture
				.getTripEnergyConsumption(this.vehicleWithBattery.getElectricDriveEnergyConsumptionModel());
		if (isBEV()) {
			if (electricEnergyConsumed > getSoC()) {
				DebugLib.stopSystemAndReportInconsistency("Ran out of energy for agent "+this.getId());
			} else {
				this.vehicleWithBattery.useBattery(electricEnergyConsumed);
			}
		} else {
			if (electricEnergyConsumed > getSoC()) {
				double hybridEnergyConsumed = this.tripInfoAsOfDeparture.getTripEnergyConsumption(
						this.vehicleWithBattery.getHybridDriveEnergyConsumptionModel()) * (1.0 - getSoC() / electricEnergyConsumed);
				electricEnergyConsumed = getSoC();
				this.vehicleWithBattery.useHybridFuel(hybridEnergyConsumed);
			}
			this.vehicleWithBattery.useBattery(electricEnergyConsumed);
		}
	}

	@Override
	public VehicleWithBattery getVehicle() {
		return this.vehicleWithBattery;
	}

	public void setVehicle(VehicleWithBattery vehicle) {
		this.vehicleWithBattery = vehicle;
	}

	public Activity getCurrentOrNextActivity() {
		return (this.currentActivity != null ? this.currentActivity : this.nextActivity);
	}

	public Activity getNextActivity() {
		return this.nextActivity;
	}

	public Leg getCurrentLeg() {
		return this.currentLeg;
	}

	public static void setInternalInterface(InternalInterface internalInterface) {
		PlugInVehicleAgent.internalInterface = internalInterface;
	}

	@Override
	public Id<Person> getPersonId() {
		return this.person.getId();
	}

	public Activity getPreviousActivity() {
		return previousActivity;
	}

	public void setPreviousActivity(Activity previousActivity) {
		this.previousActivity = previousActivity;
	}

	public Activity getCurrentActivity() {
		return currentActivity;
	}

	public void setCurrentActivity(Activity currentActivity) {
		this.currentActivity = currentActivity;
	}

	public Leg getPreviousLeg() {
		return previousLeg;
	}

	public void setPreviousLeg(Leg previousLeg) {
		this.previousLeg = previousLeg;
	}

	public Leg getNextLeg() {
		return nextLeg;
	}

	public void setNextLeg(Leg nextLeg) {
		this.nextLeg = nextLeg;
	}

	public void setNextActivity(Activity nextActivity) {
		this.nextActivity = nextActivity;
	}

	public void setCurrentLeg(Leg currentLeg) {
		this.currentLeg = currentLeg;
	}

	public TripInformation getTripInfoAsOfDeparture() {
		return tripInfoAsOfDeparture;
	}

	public boolean shouldDepartAfterChargingSession() {
		return shouldDepartAfterChargingSession;
	}

	public int getCurrentDecisionEventId() {
		return currentDecisionEventId;
	}

	public void resetAll() {
		this.chargingState = AgentChargingState.PARKED;
		this.currentLinkId = ((Activity) getSelectedPersonPlan().getPlanElements().get(0)).getLinkId();
		setCurrentCoord(((Activity) getSelectedPersonPlan().getPlanElements().get(0)).getCoord());
		this.selectedPlan = person.getSelectedPlan();
		this.selectedChargingPlug = null;
		this.currentPlanElementIndex = 0;
		this.shouldDepartAfterChargingSession = false;
		this.previousActivity = null; this.nextActivity = null; this.currentActivity = null; this.previousLeg = null; this.nextLeg = null; this.currentLeg = null;
		if(this.homeSite!=null)this.homeSite.resetAll();
		setMobsimAgent(null);
		getVehicle().useBattery(getVehicle().getSocInJoules());
		getVehicle().addEnergyToVehicleBattery(getBatteryCapacity()*EVGlobalData.data.simulationStartSocFraction.get(getPersonId()));
		setEstimatedTravelDistanceInDay();
		resetChargingStrategyInternalTracking();
	}

	private void resetChargingStrategyInternalTracking() {
		for (int i = 0; i < person.getSelectedPlan().getPlanElements().size(); i++) {
			ChargingStrategy chargingStrategyForLeg = ChargingStrategyManager.data.getReplanable(person.getId()).getSelectedEvDailyPlan().getChargingStrategyForLeg(i);
			if(chargingStrategyForLeg != null){
				chargingStrategyForLeg.resetInternalTracking();
			}
		}
	}

	public MobsimAgent getMobsimAgent() {
		return mobsimAgent;
	}

	// information cached on agent
	public void setMobsimAgent(MobsimAgent mobsimAgent) {
		this.mobsimAgent = mobsimAgent;
	}

	public ChargingSite getHomeSite() {
		return this.homeSite;
	}

	@Override
	public Id<PlugInVehicleAgent> getId() {
		return this.agentId;
	}

	public boolean isInLastActivity() {
		return this.currentPlanElementIndex == getSelectedPersonPlan().getPlanElements().size() - 1;
	}

	public LinkedList<RouteInformationElement> getReachableRouteInfoAlongTrip() {
		LinkedList<RouteInformationElement> route = getTripInfoAsOfDeparture().getRouteInfoElements();
		if(vehicleWithBattery.isBEV()){
			if(getSoC() < getTripInfoAsOfDeparture().getTripEnergyConsumption(vehicleWithBattery.getElectricDriveEnergyConsumptionModel()) + this.getCurrentSearchRadius()*vehicleWithBattery.getElectricDriveEnergyConsumptionModel().getEnergyConsumptionRateInJoulesPerMeter()){
				LinkedList<RouteInformationElement> newRoute = new LinkedList<>();
				double cumulativeTripEnergy = this.getCurrentSearchRadius()*vehicleWithBattery.getElectricDriveEnergyConsumptionModel().getEnergyConsumptionRateInJoulesPerMeter();
				for(RouteInformationElement infoElement : route){
					if(cumulativeTripEnergy > getSoC() )break;
					newRoute.add(infoElement);
					cumulativeTripEnergy += vehicleWithBattery.getElectricDriveEnergyConsumptionModel().getEnergyConsumptionForLinkInJoule(infoElement.getLinkTravelDistance(),50.0,infoElement.getAverageSpeed());
				}
				route = newRoute;
			}
		}
		return route;
	}

	public boolean canReachDestinationPlusSearchDistance() {
		return getSoC() >= getTripInfoAsOfDeparture().getTripEnergyConsumption(vehicleWithBattery.getElectricDriveEnergyConsumptionModel()) + this.getCurrentSearchRadius()*vehicleWithBattery.getElectricDriveEnergyConsumptionModel().getEnergyConsumptionRateInJoulesPerMeter();
	}

}