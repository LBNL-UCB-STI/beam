package beam.charging.infrastructure;

import beam.charging.spatialGroups.ChargingSiteSpatialGroupImpl;
import beam.transEnergySim.chargingInfrastructure.management.ChargingSiteSpatialGroup;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

import beam.EVGlobalData;
import beam.charging.management.ChargingQueueImpl;
import beam.charging.vehicle.AgentChargingState;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.parking.lib.DebugLib;
import beam.transEnergySim.agents.VehicleAgent;
import beam.transEnergySim.chargingInfrastructure.management.ChargingNetworkOperator;
import beam.transEnergySim.chargingInfrastructure.management.ChargingSitePolicy;
import beam.transEnergySim.chargingInfrastructure.stationary.*;
import beam.transEnergySim.vehicles.api.VehicleWithBattery;

import com.google.common.collect.LinkedHashMultimap;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;

public class ChargingSiteImpl implements ChargingSite {
	private static final Logger log = Logger.getLogger(ChargingSiteImpl.class);

	private Coord coord;
	private LinkedList<ChargingPoint> chargingPoints;
	private Id<ChargingSite> chargingSiteId;
	private ChargingSitePolicy chargingSitePolicy;
	private String siteType;
	private ChargingSiteSpatialGroup chargingSiteSpatialGroup;
	private LinkedHashMultimap<ChargingPlugType, ChargingPlug> accessiblePlugsByType = LinkedHashMultimap.create();
	private LinkedHashSet<ChargingPlugType> accessiblePlugTypes = new LinkedHashSet<ChargingPlugType>(), allPlugTypes = new LinkedHashSet<ChargingPlugType>();
	private LinkedHashMultimap<ChargingPlugType, ChargingPlug> availiblePlugsByType = LinkedHashMultimap.create();
	private ChargingNetworkOperator chargingNetworkOperator;
	private HashSet<Link> nearbyLinks = new HashSet<>();
	private Link nearestLink;
	public ChargingQueueImpl fastChargingQueue;
	private boolean isResidential = false;

	public ChargingSiteImpl(Id<ChargingSite> chargingSiteId, Coord coord, ChargingSitePolicy policy, ChargingNetworkOperator chargingNetworkOperator, boolean isResidential) {
		this.chargingSiteId = chargingSiteId;
		this.coord = coord;
		this.chargingPoints = new LinkedList<>();
		this.chargingSitePolicy = policy;
		this.chargingNetworkOperator = chargingNetworkOperator;
		this.chargingSiteSpatialGroup = new ChargingSiteSpatialGroupImpl("HOME"); //TODO: "Person file" should include associated spatial group!!
		this.siteType = "Residential";
		this.isResidential = isResidential;
	}
	public ChargingSiteImpl(Id<ChargingSite> chargingSiteId, Coord coord, ChargingSitePolicy policy, ChargingNetworkOperator chargingNetworkOperator, ChargingSiteSpatialGroup chargingSiteSpatialGroup, boolean isResidential) {
		this.chargingSiteId = chargingSiteId;
		this.coord = coord;
		this.chargingPoints = new LinkedList<>();
		this.chargingSitePolicy = policy;
		this.chargingNetworkOperator = chargingNetworkOperator;
		this.chargingSiteSpatialGroup = chargingSiteSpatialGroup;
		this.isResidential = isResidential;
	}
	public ChargingSiteImpl(Id<ChargingSite> chargingSiteId, Coord coord, ChargingSitePolicy policy, ChargingNetworkOperator chargingNetworkOperator, ChargingSiteSpatialGroup chargingSiteSpatialGroup, String siteType, boolean isResidential) {
		this.chargingSiteId = chargingSiteId;
		this.coord = coord;
		this.chargingPoints = new LinkedList<>();
		this.chargingSitePolicy = policy;
		this.chargingNetworkOperator = chargingNetworkOperator;
		this.chargingSiteSpatialGroup = chargingSiteSpatialGroup;
		this.siteType = siteType;
		this.isResidential = isResidential;
	}
	public ChargingSiteImpl(Id<ChargingSite> chargingSiteId, Coord coord, ChargingSitePolicy policy, ChargingNetworkOperator chargingNetworkOperator) {
		this(chargingSiteId, coord, policy, chargingNetworkOperator, false);
	}
	public ChargingSiteImpl(Id<ChargingSite> chargingSiteId, Coord coord, ChargingSitePolicy policy, ChargingNetworkOperator chargingNetworkOperator, ChargingSiteSpatialGroup chargingSiteSpatialGroup) {
		this(chargingSiteId, coord, policy, chargingNetworkOperator, chargingSiteSpatialGroup,false);
	}
	public ChargingSiteImpl(Id<ChargingSite> chargingSiteId, Coord coord, ChargingSitePolicy policy, ChargingNetworkOperator chargingNetworkOperator, ChargingSiteSpatialGroup chargingSiteSpatialGroup, String siteType) {
		this(chargingSiteId, coord, policy, chargingNetworkOperator, chargingSiteSpatialGroup, siteType,false);
	}

	@Override
	public Collection<ChargingPlug> getAccessibleChargingPlugsOfChargingPlugType(ChargingPlugType desiredType) {
		return this.accessiblePlugsByType.get(desiredType);
	}

	@Override
	public Collection<ChargingPlug> getAllChargingPlugs() {
		LinkedList<ChargingPlug> chargingPlugs = new LinkedList<>();

		for (ChargingPoint chargingPoint : chargingPoints) {
			chargingPlugs.addAll(chargingPoint.getAllChargingPlugs());
		}

		return chargingPlugs;
	}

	@Override
	public Collection<ChargingPlug> getAllAccessibleChargingPlugs() {
		return this.accessiblePlugsByType.values();
	}

	@Override
	public Collection<ChargingPoint> getAllChargingPoints() {
		// TODO: make immutable?
		return chargingPoints;
	}

	@Override
	public Coord getCoord() {
		return coord;
	}

	@Override
	public boolean isStationOpen(double time, double duration) {
		// TODO: override, if different behaviour needed
		return true;
	}

	@Override
	public ChargingNetworkOperator getChargingNetworkOperator() {
		return this.chargingNetworkOperator;
	}

	@Override
	public void addChargingPoint(ChargingPoint chargingPoint) {
		chargingPoints.add(chargingPoint);
	}

	@Override
	public void addChargingPlug(ChargingPlug plug) {
		accessiblePlugsByType.put(plug.getChargingPlugType(),plug);
		accessiblePlugTypes.add(plug.getChargingPlugType());
		allPlugTypes.add(plug.getChargingPlugType());
		availiblePlugsByType.put(plug.getChargingPlugType(),plug);
	}
	
	@Override
	public Id<ChargingSite> getId() {
		return chargingSiteId;
	}
	
	public double getParkingCost(double time, double duration) {
		return chargingSitePolicy.getParkingCost(time, duration);
	}

	public ChargingSitePolicy getChargingSitePolicy(){
		return this.chargingSitePolicy;
	}

	@Override
	public ChargingSiteSpatialGroup getChargingSiteSpatialGroup(){
		return this.chargingSiteSpatialGroup;
	}

	/**
	 * Return charging site type, e.g., Shopping, work, etc.
	 * @return
	 */
	@Override
	public String getSiteType(){
		return this.siteType;
	}

	/**
	 * Return spatial group name, e.g., Napa, Alameda, etc.
	 * @return
	 */
	@Override
	public String getSpatialGroupName(){
		return this.chargingSiteSpatialGroup.getName();
	}

	@Override
	public void registerPlugAvailable(ChargingPlug plug) {
		this.availiblePlugsByType.get(plug.getChargingPlugType()).add(plug);
	}
	@Override
	public void registerPlugUnavailable(ChargingPlug plug) {
		this.availiblePlugsByType.get(plug.getChargingPlugType()).remove(plug);
	}
	@Override
	public void registerPlugAccessible(ChargingPlug plug) {
		this.accessiblePlugsByType.get(plug.getChargingPlugType()).add(plug);
		if(this.accessiblePlugsByType.get(plug.getChargingPlugType()).size()==1){
			this.accessiblePlugTypes.add(plug.getChargingPlugType());
			if(this.accessiblePlugTypes.size()==1){
				EVGlobalData.data.chargingInfrastructureManager.registerSiteAccessible(this);
			}
		}
	}
	@Override
	public void registerPlugInaccessible(ChargingPlug plug) {
		this.accessiblePlugsByType.get(plug.getChargingPlugType()).remove(plug);
		if(this.accessiblePlugsByType.get(plug.getChargingPlugType()).size()==0){
			this.accessiblePlugTypes.remove(plug.getChargingPlugType());
			if(this.accessiblePlugTypes.size()==0){
				EVGlobalData.data.chargingInfrastructureManager.registerSiteInaccessible(this);
			}
		}
	}
	@Override
	public Collection<ChargingPlugType> getAllAccessibleChargingPlugTypes() {
		return this.accessiblePlugTypes;
	}
	@Override
	public Collection<ChargingPlugType> getAllChargingPlugTypes() {
		return this.allPlugTypes;
	}

	@Override
	public double getChargingCost(double time, double duration, ChargingPlugType plugType, VehicleWithBattery vehicle) {
		return this.chargingSitePolicy.getChargingCost(time, duration, plugType, vehicle);
	}

	@Override
	public double estimateChargingSessionDuration(ChargingPlugType plugType, VehicleWithBattery vehicle) {
		return this.chargingNetworkOperator.estimateChargingSessionDuration(this.chargingSitePolicy,plugType,vehicle);
	}

	@Override
	public void addNearbyLink(Link link) {
		this.nearbyLinks.add(link);
	}
	
	@Override
	public Collection<Link> getNearbyLinks(){
		return this.nearbyLinks;
	}

	@Override
	public Link getNearestLink() {
		return this.nearestLink;
	}

	@Override
	public void setNearestLink(Link link) {
		this.nearestLink = link;
	}

	@Override
	public void createFastChargingQueue(int maxQueueLength) {
		this.fastChargingQueue = new ChargingQueueImpl(maxQueueLength * 3);
		EVGlobalData.data.fastChargingQueue = this.fastChargingQueue;
	}
	
	/*
	 * A "Charging Event" includes the PRE CHARGING period when a vehicle waits in a queue to access the charger, 
	 * which is followed by the CHARGING SESSION itself, then the POST CHARGING period when the vehicle remains parked waiting for departure
	 */
	@Override
	public void handleBeginChargeEvent(ChargingPlug plug, VehicleAgent agent) {
		if(plug.getChargingPlugType().getNominalLevel() >= 3){
			if(!this.fastChargingQueue.addVehicleToPhysicalSite()){
				DebugLib.stopSystemAndReportInconsistency("Plug chosen but found to be not accessible when attempting to add to the fast charging queue");
			}
			if(this.fastChargingQueue.isPhysicalSiteFull()){
				LinkedList<ChargingPlug> plugsToMakeInaccessible = new LinkedList<>();
				for(ChargingPlug eachPlug : getAllAccessibleChargingPlugs()){
					if(eachPlug.getChargingPlugType().getNominalLevel()>=3)plugsToMakeInaccessible.add(eachPlug);
				}
				for(ChargingPlug eachPlug : plugsToMakeInaccessible){
					eachPlug.registerPlugInaccessible();
				}
			}
			if(this.availiblePlugsByType.get(plug.getChargingPlugType()).isEmpty()){
				this.fastChargingQueue.addVehicleToChargingQueue(plug.getChargingPlugType(), agent);
			}else{
				ChargingPlug plugToUse = this.availiblePlugsByType.get(plug.getChargingPlugType()).iterator().next();
				((PlugInVehicleAgent)agent).setSelectedChargingPlug(plugToUse);
				// Begin the charging session immediately, otherwise the queue will initiate the session when the queue has emptied
				EVGlobalData.data.chargingInfrastructureManager.handleBeginChargingSession(plugToUse, (PlugInVehicleAgent)agent);
			}
		}else{
			plug.getChargingPoint().handleBeginChargeEvent(plug, agent);
		}
	}

	@Override
	public void handleBeginChargingSession(ChargingPlug plug, VehicleAgent agent) {
		plug.handleBeginChargingSession(agent);
	}

	@Override
	public void handleEndChargingSession(ChargingPlug plug, VehicleAgent agent) {
		if(plug.getChargingPlugType().getNominalLevel() >= 3){
			if(this.fastChargingQueue.getNumInChargingQueue(plug.getChargingPlugType()) > 0){
				((PlugInVehicleAgent)agent.getVehicle().getVehicleAgent()).setChargingState(AgentChargingState.POST_CHARGE_UNPLUGGED);
				plug.unplugVehicle(agent.getVehicle());
				PlugInVehicleAgent nextAgent = (PlugInVehicleAgent)this.fastChargingQueue.dequeueVehicleFromChargingQueue(plug.getChargingPlugType());
				nextAgent.setSelectedChargingPlug(plug);
				registerPlugUnavailable(plug); // don't let someone come and nab in the interim
				double timeToDequeuNext = EVGlobalData.data.TIME_TO_ENGAGE_NEXT_FAST_CHARGING_SESSION;
//				log.info(EVGlobalData.data.now + " Site "+this.toString()+" scheduling beginChargingSession for "+ (EVGlobalData.data.now + timeToDequeuNext));
				EVGlobalData.data.scheduler.addCallBackMethod(EVGlobalData.data.now + timeToDequeuNext, nextAgent, "beginChargingSession",0.0, this);
			}
		}else{
			plug.getChargingPoint().handleEndChargingSession(plug,agent);
		}
	}
	@Override
	public void registerVehicleDeparture(VehicleAgent agent){
		if(this.fastChargingQueue.isPhysicalSiteFull()){
			for(ChargingPlug eachPlug : getAllChargingPlugs()){
				if(eachPlug.getChargingPlugType().getNominalLevel()>=3)eachPlug.registerPlugAccessible();
			}
		}
		this.fastChargingQueue.removeVehicleFromPhysicalSite(agent);
	}

	@Override
	public void removeVehicleFromQueue(ChargingPlug plug, VehicleAgent vehicle) {
		this.fastChargingQueue.removeVehicleFromChargingQueue(vehicle);
	}
	public String toString(){
		return "Site " + this.getId().toString() + ", " + this.accessiblePlugsByType.values().size() + " plugs accessible, " + 
				(this.fastChargingQueue==null ? "" : this.fastChargingQueue.getNumAtPhysicalSite() + "/" + this.fastChargingQueue.maxSizeOfPhysicalSite() + " in fast Q");
	}

	@Override
	public boolean isResidentialCharger() {
		return this.isResidential;
	}
	@Override
	public int getNumAvailablePlugsOfType(ChargingPlugType plugType) {
		int numPlugs = 0;
		for(ChargingPlug plug : this.availiblePlugsByType.get(plugType)){
			if(plug.isAvailable())numPlugs++;
		}
		return numPlugs;
	}
	@Override
	public boolean isFastChargingQueueAccessible() {
		return !this.fastChargingQueue.isPhysicalSiteFull();
	}
	@Override
	public void resetAll() {
		for(ChargingPoint point : this.chargingPoints){
			point.resetAll();
			for(ChargingPlug plug : point.getAllChargingPlugs()){
				plug.resetAll();
				this.accessiblePlugsByType.put(plug.getChargingPlugType(), plug);
				this.availiblePlugsByType.put(plug.getChargingPlugType(), plug);
			}
		}
		for(ChargingPlugType plugType : this.allPlugTypes){
			this.accessiblePlugTypes.add(plugType);
		}
		if(this.fastChargingQueue != null)this.fastChargingQueue.resetAll();
	}

	@Override
	public int getNumInChargingQueue(ChargingPlug plug){
		return this.fastChargingQueue.getNumInChargingQueue(plug.getChargingPlugType());
	}
}
