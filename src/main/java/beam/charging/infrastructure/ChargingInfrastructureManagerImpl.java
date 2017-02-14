package beam.charging.infrastructure;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;

import beam.charging.spatialGroups.ChargingSiteSpatialGroupImpl;
import beam.transEnergySim.chargingInfrastructure.management.ChargingSiteSpatialGroup;
import org.apache.log4j.Logger;
import org.jdom.JDOMException;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.utils.collections.QuadTree;
import org.matsim.core.utils.io.tabularFileParser.TabularFileHandler;
import org.matsim.core.utils.io.tabularFileParser.TabularFileParser;
import org.matsim.core.utils.io.tabularFileParser.TabularFileParserConfig;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;

import beam.EVGlobalData;
import beam.charging.management.ChargingNetworkOperatorDumbCharging;
import beam.charging.management.ChargingNetworkOperatorSmartCharging;
import beam.charging.management.ChargingNetworkOperatorV2G;
import beam.charging.policies.ChargingSitePolicyChargePoint;
import beam.charging.policies.ChargingSitePolicyEVGo;
import beam.charging.policies.ChargingSitePolicyHome;
import beam.charging.policies.ChargingSitePolicyOther;
import beam.charging.policies.ChargingSitePolicyTesla;
import beam.charging.policies.ChargingSitePolicyBlink;
import beam.charging.vehicle.AgentChargingState;
import beam.charging.vehicle.PlugInVehicleAgent;
import beam.events.BeginChargingSessionEvent;
import beam.events.EndChargingSessionEvent;
import beam.events.PreChargeEvent;
import beam.parking.lib.obj.network.EnclosingRectangle;
import beam.parking.lib.obj.network.QuadTreeInitializer;
import beam.sim.traveltime.RouteInformationElement;
import beam.transEnergySim.chargingInfrastructure.management.ChargingNetworkOperator;
import beam.transEnergySim.chargingInfrastructure.management.ChargingSitePolicy;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingLevel;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlug;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugType;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPoint;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingSite;
import beam.transEnergySim.vehicles.api.Vehicle;

// TODO: move interface up (split interface, impl)
public class ChargingInfrastructureManagerImpl {
	private static final Logger log = Logger.getLogger(ChargingInfrastructureManagerImpl.class);

	LinkedHashMap<ChargingPlugType, QuadTree<ChargingSite>> accessibleChargingSiteTreeByPlugType;
	LinkedHashMap<ChargingPlugType, SetMultimap<Id<Link>, ChargingSite>> accessibleChargingSitesByLinkIDByPlugType;
	LinkedHashMap<String, ChargingSitePolicy> chargingSitePolicyMap = new LinkedHashMap<String, ChargingSitePolicy>();
	LinkedHashMap<String, ChargingSiteSpatialGroup> chargingSiteSpatialGroupMap = new LinkedHashMap<String, ChargingSiteSpatialGroup>();
	LinkedHashMap<String, ChargingNetworkOperator> chargingNetworkOperatorMap = new LinkedHashMap<String, ChargingNetworkOperator>();
	LinkedHashMap<String, ChargingPlugType> chargingPlugTypeByIdMap = new LinkedHashMap<String, ChargingPlugType>();
	LinkedHashMap<String, ChargingPlugType> chargingPlugTypeByNameMap = new LinkedHashMap<String, ChargingPlugType>();
	private LinkedHashMap<String, ChargingSite> chargingSiteMap = new LinkedHashMap<String, ChargingSite>();
	LinkedHashMap<String, ChargingLevel> chargingLevelMap = new LinkedHashMap<String, ChargingLevel>();
	HashSet<String> comparesWithObservedCountsChargePointIds = new HashSet<>();

	public ChargingInfrastructureManagerImpl() {
		TabularFileParser fileParser = new TabularFileParser();
		TabularFileParserConfig fileParserConfig = new TabularFileParserConfig();

		/*
		 * /* PARSE CHARGING SITE POLICIES
		 */
		fileParserConfig.setFileName(EVGlobalData.data.CHARGING_SITE_POLICIES_FILEPATH);
		fileParserConfig.setDelimiterRegex(",");
		TabularFileHandler handler = new TabularFileHandler() {
			public LinkedHashMap<String, Integer> headerMap;

			@Override
			public void startRow(String[] row) {
				if (headerMap == null) {
					headerMap = new LinkedHashMap<String, Integer>();
					for (int i = 0; i < row.length; i++) {
						String colName = row[i].toLowerCase();
						if (colName.startsWith("\"")) {
							colName = colName.substring(1, colName.length() - 1);
						}
						headerMap.put(colName, i);
					}
				} else {
					String newId = row[headerMap.get("id")];
					ChargingSitePolicy newPolicy = null;
					try {
						if (row[headerMap.get("classname")].trim().equals("ChargingSitePolicyChargePoint")) {
							newPolicy = new ChargingSitePolicyChargePoint(row[headerMap.get("extradataasxml")]);
						} else if (row[headerMap.get("classname")].trim().equals("ChargingSitePolicyBlink")) {
							newPolicy = new ChargingSitePolicyBlink(row[headerMap.get("extradataasxml")]);
						} else if (row[headerMap.get("classname")].trim().equals("ChargingSitePolicyEVGo")) {
							newPolicy = new ChargingSitePolicyEVGo(row[headerMap.get("extradataasxml")]);
						} else if (row[headerMap.get("classname")].trim().equals("ChargingSitePolicyHome")) {
							newPolicy = new ChargingSitePolicyHome(row[headerMap.get("extradataasxml")]);
						} else if (row[headerMap.get("classname")].trim().equals("ChargingSitePolicyTesla")) {
							newPolicy = new ChargingSitePolicyTesla(row[headerMap.get("extradataasxml")]);
						} else if (row[headerMap.get("classname")].trim().equals("ChargingSitePolicyOther")) {
							newPolicy = new ChargingSitePolicyOther(row[headerMap.get("extradataasxml")]);
						} else {
							throw new RuntimeException("Cannot find class that inherits ChargingSitePolicy named " + row[headerMap.get("className")]);
						}
					} catch (JDOMException e) {
						e.printStackTrace();
					} catch (IOException e) {
						e.printStackTrace();
					}
					chargingSitePolicyMap.put(newId, newPolicy);
				}
			}
		};
		fileParser.parse(fileParserConfig, handler);

		/*
		 * PARSE CHARGING NETWORK OPERATORS
		 */
		fileParserConfig.setFileName(EVGlobalData.data.CHARGING_NETWORK_OPERATORS_FILEPATH);
		fileParserConfig.setDelimiterRegex(",");
		handler = new TabularFileHandler() {
			public LinkedHashMap<String, Integer> headerMap;

			@Override
			public void startRow(String[] row) {
				if (headerMap == null) {
					headerMap = new LinkedHashMap<String, Integer>();
					for (int i = 0; i < row.length; i++) {
						headerMap.put(row[i].toLowerCase(), i);
					}
				} else {
					String newId = row[headerMap.get("id")];
					ChargingNetworkOperator newOperator = null;
					if (row[headerMap.get("classname")].trim().equals("ChargingNetworkOperatorDumbCharging")) {
						newOperator = new ChargingNetworkOperatorDumbCharging(row[headerMap.get("extradataasxml")]);
					} else if (row[headerMap.get("classname")].trim().equals("ChargingNetworkOperatorSmartCharging")) {
						newOperator = new ChargingNetworkOperatorSmartCharging(row[headerMap.get("extradataasxml")]);
					} else if (row[headerMap.get("classname")].trim().equals("ChargingNetworkOperatorV2G")) {
						newOperator = new ChargingNetworkOperatorV2G(row[headerMap.get("extradataasxml")]);
					} else {
						throw new RuntimeException(
								"Cannot find class that inherits ChargingNetworkOperator named " + row[headerMap.get("className")]);
					}
					chargingNetworkOperatorMap.put(newId, newOperator);
				}
			}
		};
		fileParser.parse(fileParserConfig, handler);

		/*
		 * /* PARSE CHARGING PLUG TYPES
		 */
		fileParserConfig.setFileName(EVGlobalData.data.CHARGING_PLUG_TYPES_FILEPATH);
		fileParserConfig.setDelimiterRegex(",");
		handler = new TabularFileHandler() {
			public LinkedHashMap<String, Integer> headerMap;

			@Override
			public void startRow(String[] row) {
				if (headerMap == null) {
					headerMap = new LinkedHashMap<String, Integer>();
					for (int i = 0; i < row.length; i++) {
						String colName = row[i].toLowerCase();
						if (colName.startsWith("\"")) {
							colName = colName.substring(1, colName.length() - 1);
						}
						headerMap.put(colName, i);
					}
				} else {
					chargingPlugTypeByIdMap.put(row[headerMap.get("id")].trim(),
							new ChargingPlugTypeImpl(Id.create(row[headerMap.get("id")].trim(), ChargingPlugType.class),
									row[headerMap.get("plugtypename")].trim().toLowerCase(),
									Double.parseDouble(row[headerMap.get("chargingpowerinkw")].trim()),
									Double.parseDouble(row[headerMap.get("dischargingpowerinkw")].trim()),
									Boolean.parseBoolean(row[headerMap.get("v1gcapable")].trim()),
									Boolean.parseBoolean(row[headerMap.get("v2gcapable")].trim())));
					chargingPlugTypeByNameMap.put(row[headerMap.get("plugtypename")].trim().toLowerCase(),
							chargingPlugTypeByIdMap.get(row[headerMap.get("id")].trim()));
				}
			}
		};

		/*
		 * /* LOAD CHARGING SITES
		 */
		fileParser.parse(fileParserConfig, handler);
		fileParserConfig.setFileName(EVGlobalData.data.CHARGING_SITES_FILEPATH);
		fileParserConfig.setDelimiterRegex(",");
		handler = new TabularFileHandler() {
			public LinkedHashMap<String, Integer> headerMap;

			@Override
			public void startRow(String[] row) {
				if (headerMap == null) {
					headerMap = new LinkedHashMap<String, Integer>();
					for (int i = 0; i < row.length; i++) {
						String colName = row[i].toLowerCase();
						if (colName.startsWith("\"")) {
							colName = colName.substring(1, colName.length() - 1);
						}
						headerMap.put(colName, i);
					}
				} else {
					//  Coordinate of the charging site
					Coord theCoord = new Coord(Double.parseDouble(row[headerMap.get("longitude")].trim()),
							Double.parseDouble(row[headerMap.get("latitude")].trim()));

					// Charging site spatial group -- this can be separated from this loop once we have a separate file for charging site spatial groups
					if(!chargingSiteSpatialGroupMap.containsKey(row[headerMap.get("spatialgroup")].trim())){
						ChargingSiteSpatialGroup newSpatialGroup = new ChargingSiteSpatialGroupImpl(row[headerMap.get("spatialgroup")].trim());
						chargingSiteSpatialGroupMap.put(row[headerMap.get("spatialgroup")].trim(),newSpatialGroup);
						log.info("spatial group:" + row[headerMap.get("spatialgroup")].trim());
					}

					// Initialize new charging site
					ChargingSite newSite = new ChargingSiteImpl(Id.create(row[headerMap.get("id")].trim(), ChargingSite.class),
							EVGlobalData.data.transformFromWGS84.transform(theCoord),
							chargingSitePolicyMap.get(row[headerMap.get("policyid")].trim()),
							chargingNetworkOperatorMap.get(row[headerMap.get("networkoperatorid")].trim()),
							chargingSiteSpatialGroupMap.get(row[headerMap.get("spatialgroup")].trim()),
							row[headerMap.get("sitetype")].trim()
							);
					chargingSiteMap.put(row[headerMap.get("id")].trim(), newSite);
				}
			}
		};
		fileParser.parse(fileParserConfig, handler);

		/*
		 * /* LOAD CHARGING POINTS / PLUGS
		 */
		fileParserConfig.setFileName(EVGlobalData.data.CHARGING_POINTS_FILEPATH);
		fileParserConfig.setDelimiterRegex(",");
		handler = new TabularFileHandler() {
			public LinkedHashMap<String, Integer> headerMap;
			int plugCount = 0;

			@Override
			public void startRow(String[] row) {
				if (headerMap == null) {
					headerMap = new LinkedHashMap<String, Integer>();
					for (int i = 0; i < row.length; i++) {
						String colName = row[i].toLowerCase();
						if (colName.startsWith("\"")) {
							colName = colName.substring(1, colName.length() - 1);
						}
						headerMap.put(colName, i);
					}
				} else {
					ChargingSite theSite = chargingSiteMap.get(row[headerMap.get("siteid")].trim());
					if (theSite == null)
						throw new RuntimeException("No Charging Site found with ID = " + row[headerMap.get("siteid")].trim());
					String chargePointIdString = row[headerMap.get("id")].trim();
					Id<ChargingPoint> pointId = Id.create(chargePointIdString, ChargingPoint.class);
					ChargingPointImpl newPoint = new ChargingPointImpl(pointId, theSite,
							Integer.parseInt(row[headerMap.get("numparkingspacesperpoint")]));
					for (int i = 0; i < Integer.parseInt(row[headerMap.get("numplugs")].trim()); i++) {
						ChargingPlugImpl newPlug = new ChargingPlugImpl(Id.create(plugCount++, ChargingPlug.class), newPoint,
								chargingPlugTypeByIdMap.get(row[headerMap.get("plugtypeid")].trim()));
					}

					if (headerMap.containsKey("comparewithobservedcounts")){
						int compareWithObservedCounts = Integer.parseInt(row[headerMap.get("comparewithobservedcounts")]);

						if (compareWithObservedCounts == 1) {
							comparesWithObservedCountsChargePointIds.add(chargePointIdString);
						}
					}

				}
			}
		};
		fileParser.parse(fileParserConfig, handler);

		/*
		 * CREATE CHARGING QUEUES AT SITE FOR FAST CHARGERS AND AT POINT FOR
		 * SLOW CHARGERS
		 */
		for (ChargingSite site : chargingSiteMap.values()) {
			int numFast = 0;
			for (ChargingPlugType plugType : chargingPlugTypeByIdMap.values()) {
				if (plugType.getNominalLevel() >= 3) {
					numFast += site.getAccessibleChargingPlugsOfChargingPlugType(plugType).size();
				}
			}
			// Set the number of fast charger as the maximum charging queue at the charging site
			site.createFastChargingQueue(numFast);
			for (ChargingPoint point : site.getAllChargingPoints()) {
				point.createSlowChargingQueue(point.getNumberOfAvailableParkingSpots());
			}
		}

		/*
		 * CREATE SPATIAL INDICES FOR ACCESSING INFRASTRUCTURE
		 * getAllAccessibleAndCompatibleChargingSitesInAreagetAllAccessibleAndCompatibleChargingSitesInArea
		 */
		EnclosingRectangle rect = new EnclosingRectangle();

		for (ChargingSite chargingSite : chargingSiteMap.values()) {
			rect.registerCoord(chargingSite.getCoord());
			chargingSite.setNearestLink(EVGlobalData.data.linkQuadTree.getNearest(chargingSite.getCoord().getX(), chargingSite.getCoord().getY()));
		}

		accessibleChargingSiteTreeByPlugType = new LinkedHashMap<>();
		accessibleChargingSitesByLinkIDByPlugType = new LinkedHashMap<>();
		for (ChargingPlugType plugType : chargingPlugTypeByIdMap.values()) {
			QuadTree<ChargingSite> newTree = (new QuadTreeInitializer<ChargingSite>()).getQuadTree(rect);
			for (ChargingSite chargingSite : chargingSiteMap.values()) {
				if (chargingSite.getAllChargingPlugTypes().contains(plugType)) {
					newTree.put(chargingSite.getCoord().getX(), chargingSite.getCoord().getY(), chargingSite);
				}
			}
			accessibleChargingSiteTreeByPlugType.put(plugType, newTree);

			SetMultimap<Id<Link>, ChargingSite> newMap = HashMultimap.create();
			for (Link link : EVGlobalData.data.controler.getScenario().getNetwork().getLinks().values()) {
				Collection<ChargingSite> sitesNearLink = newTree.getElliptical(link.getFromNode().getCoord().getX(),
						link.getFromNode().getCoord().getY(), link.getToNode().getCoord().getX(), link.getToNode().getCoord().getY(),
						link.getLength() + EVGlobalData.data.EN_ROUTE_SEARCH_DISTANCE);
				newMap.putAll(link.getId(), sitesNearLink);
				for (ChargingSite site : sitesNearLink) {
					site.addNearbyLink(link);
				}
			}
			accessibleChargingSitesByLinkIDByPlugType.put(plugType, newMap);
		}
	}

	LinkedHashMap<Id, Vehicle> vehicleOwnerAssignment;

	public Collection<ChargingSite> getAllAccessibleChargingSitesInArea(Coord coord, double distance) {
		return getAllAccessibleAndCompatibleChargingSitesInArea(coord, distance, null);
	}

	public Collection<ChargingSite> getAllAccessibleAndCompatibleChargingSitesInArea(Coord coord, double distance, PlugInVehicleAgent agent) {
		Collection<ChargingSite> sites = new LinkedHashSet<ChargingSite>();
		if (agent == null) {
			for (ChargingPlugType plugType : chargingPlugTypeByIdMap.values()) {
				sites.addAll(accessibleChargingSiteTreeByPlugType.get(plugType).getDisk(coord.getX(), coord.getY(), distance));
			}
		} else {
			for (ChargingPlugType plugType : agent.getVehicleWithBattery().getCompatiblePlugTypes()) {
				sites.addAll(accessibleChargingSiteTreeByPlugType.get(plugType).getDisk(coord.getX(), coord.getY(), distance));
			}
			if (agent.getHomeSite() != null && getDistanceBetweenPoints(agent.getHomeSite().getCoord(), coord) <= distance) {
				sites.add(agent.getHomeSite());
			}
		}
		return sites;
	}

	public static boolean avoidPlug(PlugInVehicleAgent agent, ChargingPlugType plugType) {
		// TODO: all code calling this method should be updated in phase 2 (the
		// purpose of this check is to avoid using fast charging if soc > 0.8)
		return isSocAbove80Pct(agent) && isFastCharger(plugType);
	}

	public static boolean isSocAbove80Pct(PlugInVehicleAgent agent) {
		return agent.getSoC() > 0.8 * agent.getBatteryCapacity();
	}

	public static boolean isFastCharger(ChargingPlugType plugType) {
		return plugType.getNominalLevel() == 3;
	}

	public Collection<ChargingSite> getAllAccessibleChargingSitesAlongRoute(LinkedList<RouteInformationElement> route, double currentSearchRadius) {
		return getAllAccessibleAndCompatibleChargingSitesAlongRoute(route, currentSearchRadius, null);
	}

	public Collection<ChargingSite> getAllAccessibleAndCompatibleChargingSitesAlongRoute(LinkedList<RouteInformationElement> route,
			double currentSearchRadius, PlugInVehicleAgent agent) {
		Collection<ChargingSite> sites = new LinkedHashSet<ChargingSite>();
		if (agent == null) {
			for (RouteInformationElement elem : route) {
				for (ChargingPlugType plugType : chargingPlugTypeByIdMap.values()) {
					sites.addAll(this.accessibleChargingSitesByLinkIDByPlugType.get(plugType).get(elem.getLinkId()));
				}
			}
		} else {
			for (RouteInformationElement elem : route) {
				for (ChargingPlugType plugType : agent.getVehicleWithBattery().getCompatiblePlugTypes()) {
					sites.addAll(this.accessibleChargingSitesByLinkIDByPlugType.get(plugType).get(elem.getLinkId()));
				}
				if (agent.getHomeSite() != null && getDistanceBetweenPoints(agent.getHomeSite().getCoord(),
						EVGlobalData.data.controler.getScenario().getNetwork().getLinks().get(elem.getLinkId()).getCoord()) <= currentSearchRadius) {
					sites.add(agent.getHomeSite());
				}
			}
		}
		return sites;
	}

	private double getDistanceBetweenPoints(Coord coordA, Coord coordB) {
		return Math.pow(Math.pow(coordA.getX() - coordB.getX(), 2.0) + Math.pow(coordA.getY() - coordB.getY(), 2.0), 0.5);
	}

	// TODO: move this method down to simulator itself or up, so that events can
	// use it as well -> find suitable place for this!
	public Vehicle getVehicle(Id personId) {
		return vehicleOwnerAssignment.get(personId);
	}

	public Collection<ChargingPlugType> getChargingPlugTypes() {
		return chargingPlugTypeByIdMap.values();
	}

	/*
	 * Returns true if a charging session has been initiated and false otherwise
	 */
	public void handleBeginChargeEvent(ChargingPlug plug, PlugInVehicleAgent agent) {
		agent.setChargingState(AgentChargingState.PRE_CHARGE);
		EVGlobalData.data.eventLogger.processEvent(new PreChargeEvent(EVGlobalData.data.now, agent, plug));
		plug.getChargingSite().handleBeginChargeEvent(plug, agent);
	}

	public void handleBeginChargingSession(ChargingPlug plug, PlugInVehicleAgent agent) {
		agent.setChargingState(AgentChargingState.CHARGING);
		plug.getChargingSite().handleBeginChargingSession(plug, agent);
		EVGlobalData.data.eventLogger.processEvent(new BeginChargingSessionEvent(EVGlobalData.data.now, agent, plug,plug.getActualChargingPowerInWatt()));
	}

	public void handleEndChargingSession(ChargingPlug plug, PlugInVehicleAgent agent) {
		agent.setChargingState(AgentChargingState.POST_CHARGE_PLUGGED);

		// Process event
		EVGlobalData.data.eventLogger.processEvent(new EndChargingSessionEvent(EVGlobalData.data.now, agent, plug, plug.getActualChargingPowerInWatt()));

		// Determine if the vehicle should leave or stay at the end of the charging session
		plug.getChargingSite().handleEndChargingSession(plug, agent);
			// this function checks if it is a slow/fast charger and if there is a queue.
			// If there is, a vehicle is unplugged and a next vehicle is plugged-in
			// This function, however, does not unplug EV at a fast charger if there is no queue.

		// We need to plug out the EV after a fast charging no matter what.
		if(plug.getChargingPlugType().getNominalLevel() >= 3){ // if it's a fast charger
			plug.unplugVehicle(agent.getVehicleWithBattery());
		}else if(agent.isInLastActivity()){ // if it's a slow charger && it's a last activity,
			double unplugTime = agent.getCurrentActivity().getEndTime() < 0 ?
					EVGlobalData.data.now : agent.getCurrentActivity().getEndTime();
			EVGlobalData.data.scheduler.addCallBackMethod(unplugTime, plug, "unplugVehicle", 0.0,agent);
		}else if(agent.shouldDepartAfterChargingSession()) // if it's a slow charger && if the vehicle should leave after charging
			agent.handleDeparture();

//		if (agent.isInLastActivity()) {
//			double unplugTime = agent.getCurrentActivity().getEndTime() < 0 ? EVGlobalData.data.now : agent.getCurrentActivity().getEndTime();
//			EVGlobalData.data.scheduler.addCallBackMethod(unplugTime, plug, "unplugVehicle", 0.0,agent);
//		}else{
//			plug.unplugVehicle(agent.getVehicleWithBattery());
//			if (agent.shouldDepartAfterChargingSession()) agent.handleDeparture();
//		}
	}

	public void registerVehicleDeparture(ChargingPlug plug, PlugInVehicleAgent agent) {
		if (plug == null) {
			log.warn("registerVehicleDeparture called but plug is null for agent " + agent.getPersonId());
			return;
		}
		if (plug.getChargingPlugType().getNominalLevel() < 3) {
			plug.getChargingPoint().registerVehicleDeparture(agent);
		} else{
			plug.getChargingSite().registerVehicleDeparture(agent);
		}
	}

	public void interruptChargingEvent(ChargingPlug plug, PlugInVehicleAgent agent) {
		if (plug == null) {
			// TODO this was to avoid a rare nullPointer, but the logical flaw
			// causing it should be debugged instead
			log.warn("interruptChargingEvent called but plug is null for agent " + agent.getPersonId());
			return;
		}
		if (agent.getChargingState() == AgentChargingState.PRE_CHARGE) {
			if (plug.getChargingPlugType().getNominalLevel() < 3) {
				plug.getChargingPoint().removeVehicleFromQueue(plug, agent);
			} else {
				plug.getChargingSite().removeVehicleFromQueue(plug, agent);
			}
		} else if (agent.getChargingState() == AgentChargingState.CHARGING) {
			plug.handleChargingSessionInterruption();
			handleEndChargingSession(plug, agent);
		}
	}

	public void registerSiteInaccessible(ChargingSite site) {
		for (ChargingPlugType plugType : site.getAllChargingPlugTypes()) {
			this.accessibleChargingSiteTreeByPlugType.get(plugType).remove(site.getCoord().getX(), site.getCoord().getY(), site);
			for (Link link : site.getNearbyLinks()) {
				this.accessibleChargingSitesByLinkIDByPlugType.get(plugType).remove(link, site);
			}
		}
	}

	public void registerSiteAccessible(ChargingSite site) {
		if (!site.isResidentialCharger()) {
			for (ChargingPlugType plugType : site.getAllChargingPlugTypes()) {
				this.accessibleChargingSiteTreeByPlugType.get(plugType).put(site.getCoord().getX(), site.getCoord().getY(), site);
				for (Link link : site.getNearbyLinks()) {
					this.accessibleChargingSitesByLinkIDByPlugType.get(plugType).put(link.getId(), site);
				}
			}
		}
	}

	public Collection<ChargingSite> getAllChargingSites() {
		return this.chargingSiteMap.values();
	}

	public ChargingPlugType getChargingPlugTypeByName(String plugTypeName) {
		return this.chargingPlugTypeByNameMap.get(plugTypeName);
	}

	public ChargingPlugType getChargingPlugTypeById(String plugTypeId) {
		return this.chargingPlugTypeByIdMap.get(plugTypeId);
	}

	public ChargingSitePolicy getChargingSitePolicyById(String policyId) {
		return this.chargingSitePolicyMap.get(policyId);
	}

	public ChargingNetworkOperator getChargingNetworkOperatorById(String operatorId) {
		return this.chargingNetworkOperatorMap.get(operatorId);
	}

	public ChargingSite getChargingSite(String chargingSiteId) {
		return chargingSiteMap.get(chargingSiteId);
	}

	public void addChargingSite(String chargingSiteId, ChargingSite site) {
		this.chargingSiteMap.put(chargingSiteId, site);
	}

	public void resetAll() {
		for(ChargingSite site : chargingSiteMap.values()){
			site.resetAll();
		}
		
	}

}
