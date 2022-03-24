
.. _model-inputs:

Model Inputs
============

Configuration file
------------------
The BEAM configuration file controls where BEAM will source input data and the value of parameters. To see an example of the latest version of this file:

https://github.com/LBNL-UCB-STI/beam/blob/master/test/input/beamville/beam.conf

As of Fall 2018, BEAM is still under rapid development. So the configuration file will continue to evolve. Particularly, it should be expected that new parameters will be created to contol new model features and old configuration options may be modified, simplied, or eliminated.

Furthermore, the BEAM configuration file contains a hybrid between parameters from MATSim (see namespace `matsim` in the config file). Not all of the matsim parameters are used by BEAM. Only the specific MATSim parameters described in this document are relevant. Modifying the other parameters will have no impact. In future releases of BEAM, the irrelevant parameters will be removed.

In order to see example configuration options for a particular release of BEAM replace `master` in the above URL with the version number, e.g. for Version v0.6.2 go to this link:

https://github.com/LBNL-UCB-STI/beam/blob/v0.6.2/test/input/beamville/beam.conf

BEAM follows the `MATSim convention`_ for most of the inputs required to run a simulation, though specifying the road network and transit system is based on the `R5 requirements`_. Refer to these external documntation for details on the following inputs.

.. _MATSim convention: https://matsim.org/docs
.. _R5 requirements: https://github.com/conveyal/r5

* The person population and corresponding attributes files (e.g. `population.xml` and `populationAttributes.xml`)
* The household population and corresponding attributes files (e.g. `households.xml` and `householdAttributes.xml`)
* A directory containing network and transit data used by R5 (e.g. `r5/`)
* The open street map network (e.g. `r5/beamville.osm`)
* GTFS archives, one for each transit agency (e.g. `r5/bus.zip`)

Config Options

The following is a list of the most commonly used configuration options in approximate order of apearance in the beamville example config file (order need not be preserved so it is ok to rearrange options). A complete listing will be added to this documentation soon

General parameters
^^^^^^^^^^^^^^^^^^
::

   beam.agentsim.simulationName = "beamville"
   beam.agentsim.numAgents = 100
   beam.agentsim.thresholdForWalkingInMeters = 1000
   beam.agentsim.thresholdForMakingParkingChoiceInMeters = 100
   beam.agentsim.schedulerParallelismWindow = 30
   beam.agentsim.timeBinSize = 3600
  
* simulationName: Used as a prefix when creating an output directory to store simulation results.
* numAgents: This will limit the number of PersonAgents created in the simulation agents will be . Note that the number of agents is also limited by the total number of "person" elements in the population file specified by `matsim.modules.plans.inputPlansFile`. In other words, if there are 100 people in the plans and numAgents is set to 50, then 50 PersonAgents will be created. If numAgents is >=100, then 100 PersonAgents will be created. Sampling to a smaller number of agents is accomplished by sampling full households until the desired number of PersonAgents is reached. This keeps the household structure intact.
* thresholdForWalkingInMeters: Used to determine whether a PersonAgent needs to route a walking path through the network to get to a parked vehicle. If the vehicle is closer than thresholdForWalkingInMeters in Euclidean distance, then the walking trip is assumed to be instantaneous. Note, for performance reasons, we do not recommend values for this threshold less than 100m.
* thresholdForMakingParkingChoiceInMeters: Similar to thresholdForWalkingInMeters, this threshold determines the point in a driving leg when the PersonAgent initiates the parking choice processes. So for 1000m, the agent will drive until she is <=1km from the destination and then seek a parking space.
* schedulerParallelismWindow: This controls the discrete event scheduling window used by BEAM to achieve within-day parallelism. The units of this parameter are in seconds and the larger the window, the better the performance of the simulation, but the less chronologically accurate the results will be.
* timeBinSize: For most auto-generated output graphs and tables, this parameter will control the resolution of time-varying outputs.

Mode choice parameters
^^^^^^^^^^^^^^^^^^^^^^
::

   beam.agentsim.agents.modalBehaviors.modeChoiceClass = "ModeChoiceMultinomialLogit"
   beam.agentsim.agents.modalBehaviors.maximumNumberOfReplanningAttempts = 3
   beam.agentsim.agents.modalBehaviors.defaultValueOfTime = 8.0
   beam.agentsim.agents.modalBehaviors.minimumValueOfTime = 7.25
   beam.agentsim.agents.modalBehaviors.modeVotMultiplier.transit = 1.0
   beam.agentsim.agents.modalBehaviors.modeVotMultiplier.bike = 1.0
   beam.agentsim.agents.modalBehaviors.modeVotMultiplier.walk = 1.0
   beam.agentsim.agents.modalBehaviors.modeVotMultiplier.rideHail = 1.0
   beam.agentsim.agents.modalBehaviors.modeVotMultiplier.rideHailPooled = 1.0
   beam.agentsim.agents.modalBehaviors.modeVotMultiplier.rideHailTransit = 1.0
   beam.agentsim.agents.modalBehaviors.modeVotMultiplier.waiting = 1.0
   beam.agentsim.agents.modalBehaviors.modeVotMultiplier.CAV = 1.0
   beam.agentsim.agents.modalBehaviors.modeVotMultiplier.drive = 1.0
   beam.agentsim.agents.modalBehaviors.overrideAutomationLevel = 1
   beam.agentsim.agents.modalBehaviors.overrideAutomationForVOTT = false
   beam.agentsim.agents.modalBehaviors.poolingMultiplier.Level5 = 1.0
   beam.agentsim.agents.modalBehaviors.poolingMultiplier.Level4 = 1.0
   beam.agentsim.agents.modalBehaviors.poolingMultiplier.Level3 = 1.0
   beam.agentsim.agents.modalBehaviors.poolingMultiplier.LevelLE2 = 1.0
   beam.agentsim.agents.modalBehaviors.highTimeSensitivity.highCongestion.highwayFactor.Level5 = 1.0
   beam.agentsim.agents.modalBehaviors.highTimeSensitivity.highCongestion.nonHighwayFactor.Level5 = 1.0
   beam.agentsim.agents.modalBehaviors.highTimeSensitivity.lowCongestion.highwayFactor.Level5 = 1.0
   beam.agentsim.agents.modalBehaviors.highTimeSensitivity.lowCongestion.nonHighwayFactor.Level5 = 1.0
   beam.agentsim.agents.modalBehaviors.lowTimeSensitivity.highCongestion.highwayFactor.Level5 = 1.0
   beam.agentsim.agents.modalBehaviors.lowTimeSensitivity.highCongestion.nonHighwayFactor.Level5 = 1.0
   beam.agentsim.agents.modalBehaviors.lowTimeSensitivity.lowCongestion.highwayFactor.Level5 = 1.0
   beam.agentsim.agents.modalBehaviors.lowTimeSensitivity.lowCongestion.nonHighwayFactor.Level5 = 1.0
   beam.agentsim.agents.modalBehaviors.highTimeSensitivity.highCongestion.highwayFactor.Level4 = 1.0
   beam.agentsim.agents.modalBehaviors.highTimeSensitivity.highCongestion.nonHighwayFactor.Level4 = 1.0
   beam.agentsim.agents.modalBehaviors.highTimeSensitivity.lowCongestion.highwayFactor.Level4 = 1.0
   beam.agentsim.agents.modalBehaviors.highTimeSensitivity.lowCongestion.nonHighwayFactor.Level4 = 1.0
   beam.agentsim.agents.modalBehaviors.lowTimeSensitivity.highCongestion.highwayFactor.Level4 = 1.0
   beam.agentsim.agents.modalBehaviors.lowTimeSensitivity.highCongestion.nonHighwayFactor.Level4 = 1.0
   beam.agentsim.agents.modalBehaviors.lowTimeSensitivity.lowCongestion.highwayFactor.Level4 = 1.0
   beam.agentsim.agents.modalBehaviors.lowTimeSensitivity.lowCongestion.nonHighwayFactor.Level4 = 1.0
   beam.agentsim.agents.modalBehaviors.highTimeSensitivity.highCongestion.highwayFactor.Level3 = 1.0
   beam.agentsim.agents.modalBehaviors.highTimeSensitivity.highCongestion.nonHighwayFactor.Level3 = 1.0
   beam.agentsim.agents.modalBehaviors.highTimeSensitivity.lowCongestion.highwayFactor.Level3 = 1.0
   beam.agentsim.agents.modalBehaviors.highTimeSensitivity.lowCongestion.nonHighwayFactor.Level3 = 1.0
   beam.agentsim.agents.modalBehaviors.lowTimeSensitivity.highCongestion.highwayFactor.Level3 = 1.0
   beam.agentsim.agents.modalBehaviors.lowTimeSensitivity.highCongestion.nonHighwayFactor.Level3 = 1.0
   beam.agentsim.agents.modalBehaviors.lowTimeSensitivity.lowCongestion.highwayFactor.Level3 = 1.0
   beam.agentsim.agents.modalBehaviors.lowTimeSensitivity.lowCongestion.nonHighwayFactor.Level3 = 1.0
   beam.agentsim.agents.modalBehaviors.highTimeSensitivity.highCongestion.highwayFactor.LevelLE2 = 1.0
   beam.agentsim.agents.modalBehaviors.highTimeSensitivity.highCongestion.nonHighwayFactor.LevelLE2 = 1.0
   beam.agentsim.agents.modalBehaviors.highTimeSensitivity.lowCongestion.highwayFactor.LevelLE2 = 1.0
   beam.agentsim.agents.modalBehaviors.highTimeSensitivity.lowCongestion.nonHighwayFactor.LevelLE2 = 1.0
   beam.agentsim.agents.modalBehaviors.lowTimeSensitivity.highCongestion.highwayFactor.LevelLE2 = 1.0
   beam.agentsim.agents.modalBehaviors.lowTimeSensitivity.highCongestion.nonHighwayFactor.LevelLE2 = 1.0
   beam.agentsim.agents.modalBehaviors.lowTimeSensitivity.lowCongestion.highwayFactor.LevelLE2 = 1.0
   beam.agentsim.agents.modalBehaviors.lowTimeSensitivity.lowCongestion.nonHighwayFactor.LevelLE2 = 1.0
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.transfer = -1.4
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.car_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.cav_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.walk_transit_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.drive_transit_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hail_transit_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hail_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hail_pooled_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.walk_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.bike_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.bike_transit_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.transit_crowding = 0.0
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.transit_crowding_percentile = 90
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.utility_scale_factor = 1.0
   beam.agentsim.agents.modalBehaviors.lccm.paramFile = ${beam.inputDirectory}"/lccm-long.csv"
   #Toll params
   beam.agentsim.toll.file=${beam.inputDirectory}"/toll-prices.csv"
   

* modeChoiceClass: Selects the choice algorithm to be used by agents to select mode when faced with a choice. Default of ModeChoiceMultinomialLogit is recommended but other algorithms include ModeChoiceMultinomialLogit ModeChoiceTransitIfAvailable ModeChoiceDriveIfAvailable ModeChoiceRideHailIfAvailable ModeChoiceUniformRandom ModeChoiceLCCM.
* maximumNumberOfReplanningAttempts: Replanning happens if a Person cannot have some resource required to continue trip in the chosen mode. If the number of replanning exceeded this value WALK mode is chosen.
* defaultValueOfTime: This value of time is used by the ModeChoiceMultinomialLogit choice algorithm unless the value of time is specified in the populationAttributes file.
* minimumValueOfTime: value of time cannot be lower than this value
* modeVotMultiplier: allow to modify value of time for a particular trip mode
* modeVotMultiplier.waiting: not used now
* overrideAutomationLevel: the value to be used to override the vehicle automation level when calculating generalized time
of ride-hail legs
* overrideAutomationForVOTT: enabled overriding of automation level (see overrideAutomationLevel)
* poolingMultiplier: this multiplier is used when calculating generalized time for pooled ride-hail trip for a particular
vehicle automation level
* highTimeSensitivity.highCongestion.highwayFactor.Level5 when a person go by car (not ride-hail) these params allow to set generalized time multiplier for a particular link for different situations: work trip/other trips, high/low traffic, highway or not, vehicle automation level
* params.transfer: Constant utility (where 1 util = 1 dollar) of making transfers during a transit trip.
* params.car_intercept: Constant utility (where 1 util = 1 dollar) of driving.
* params.cav_intercept: Constant utility (where 1 util = 1 dollar) of using CAV.
* params.walk_transit_intercept: Constant utility (where 1 util = 1 dollar) of walking to transit.
* params.drive_transit_intercept: Constant utility (where 1 util = 1 dollar) of driving to transit.
* params.ride_hail_transit_intercept: Constant utility (where 1 util = 1 dollar) of taking ride hail to/from transit.
* params.ride_hail_intercept: Constant utility (where 1 util = 1 dollar) of taking ride hail.
* params.ride_hail_pooled_intercept: Constant utility (where 1 util = 1 dollar) of taking pooled ride hail.
* params.walk_intercept: Constant utility (where 1 util = 1 dollar) of walking.
* params.bike_intercept: Constant utility (where 1 util = 1 dollar) of biking.
* params.bike_transit_intercept: Constant utility (where 1 util = 1 dollar) of biking to transit.
* params.transit_crowding: Multiplier utility of avoiding "crowded" transit vehicle. Should be negative.
* params.transit_crowding_percentile: Which percentile to use to get the occupancyLevel (number of passengers / vehicle capacity). The route may have different occupancy levels during the legs/vehicle stops.
* utility_scale_factor: amount by which utilites are scaled before evaluating probabilities. Smaller numbers leads to less determinism
* lccm.paramFile: if modeChoiceClass is set to `ModeChoiceLCCM` this must point to a valid file with LCCM parameters. Otherwise, this parameter is ignored.
* toll.file: File path to a file with static road tolls. Note, this input will change in future BEAM release where time-varying tolls will possible.

Vehicles and Population
^^^^^^^^^^^^^^^^^^^^^^^
::

   #BeamVehicles Params
   beam.agentsim.agents.vehicles.fuelTypesFilePath = ${beam.inputDirectory}"/beamFuelTypes.csv"
   beam.agentsim.agents.vehicles.vehicleTypesFilePath = ${beam.inputDirectory}"/vehicleTypes.csv"
   beam.agentsim.agents.vehicles.vehiclesFilePath = ${beam.inputDirectory}"/vehicles.csv"

* useBikes: simple way to disable biking, set to true if vehicles file does not contain any data on biking.
* fuelTypesFilePath: configure fuel fuel pricing.
* vehicleTypesFilePath: configure vehicle properties including seating capacity, length, fuel type, fuel economy, and refueling parameters.
* vehiclesFilePath: replacement to legacy MATSim vehicles.xml file. This must contain an Id and vehicle type for every vehicle id contained in households.xml.

TAZs, Scaling, and Physsim Tuning
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
::

   #TAZ params
   beam.agentsim.taz.file=${beam.inputDirectory}"/taz-centers.csv"
   beam.agentsim.taz.parking = ${beam.inputDirectory}"/parking/taz-parking-default.csv"
   # Parking Manager name (DEFAULT | PARALLEL)
   beam.agentsim.taz.parkingManager.name = "DEFAULT"
   beam.agentsim.taz.parkingManager.parallel.numberOfClusters = 8
   # Scaling and Tuning Params
   beam.agentsim.tuning.transitCapacity = 0.1
   beam.agentsim.tuning.transitPrice = 1.0
   beam.agentsim.tuning.tollPrice = 1.0
   beam.agentsim.tuning.rideHailPrice = 1.0
   # PhysSim name (JDEQSim | BPRSim | PARBPRSim | CCHRoutingAssignment)
   beam.physsim.name = "JDEQSim
   # PhysSim Scaling Params
   beam.physsim.flowCapacityFactor = 0.0001
   beam.physsim.storageCapacityFactor = 0.0001
   beam.physsim.writeMATSimNetwork = false
   beam.physsim.ptSampleSize = 1.0
   beam.physsim.jdeqsim.agentSimPhysSimInterfaceDebugger.enabled = false
   beam.physsim.skipPhysSim = false
   # Travel time function for (PAR)PBR sim (BPR | FREE_FLOW)
   beam.physsim.bprsim.travelTimeFunction = "BPR"
   beam.physsim.bprsim.minFlowToUseBPRFunction = 10
   beam.physsim.bprsim.inFlowAggregationTimeWindowInSeconds = 900
   beam.physsim.parbprsim.numberOfClusters = 8
   beam.physsim.parbprsim.syncInterval = 60

* agentsim.taz.file: path to a file specifying the centroid of each TAZ. For performance BEAM approximates TAZ boundaries based on a nearest-centroid approach. The area of each centroid (in m^2) is also necessary to approximate average travel distances within each TAZ (used in parking choice process).
* taz.parking: path to a file specifying the parking and charging infrastructure. If any TAZ contained in the taz file is not specified in the parking file, then ulimited free parking is assumed.
* beam.agentsim.taz.parkingManager.name: the name of the parking manager. PARALLEL parking manager splits the TAZes into a number of clusters. This allows the users to speed up the searching for parking stalls. But as a tradeoff, it has degraded quality. Usually, 8-16 clusters can provide satisfactory quality on big numbers of TAZes.
* beam.agentsim.taz.parkingManager.parallel.numberOfClusters: the number of clusters for PARALLEL parking manager.
* tuning.transitCapacity: Scale the number of seats per transit vehicle... actual seats are rounded to nearest whole number. Applies uniformly to all transit vehilces.
* tuning.transitPrice: Scale the price of riding on transit. Applies uniformly to all transit trips.
* tuning.tollPrice: Scale the price to cross tolls.
* tuning.rideHailPrice: Scale the price of ride hailing. Applies uniformly to all trips and is independent of defaultCostPerMile and defaultCostPerMinute described above. I.e. price = (costPerMile + costPerMinute)*rideHailPrice
* physsim.name: Name of the physsim. BPR physsim calculates the travel time of a vehicle for a particular link basing on the inFlow value for that link (number of vehicle entered that link within last n minutes. This value is upscaled to one hour value.). PARBPR splits the network into clusters and simulates vehicle movement for each cluster in parallel.
* physsim.flowCapacityFactor: Flow capacity parameter used by JDEQSim for traffic flow simulation.
* physsim.storageCapacityFactor: Storage capacity parameter used by JDEQSim for traffic flow simulation.
* physsim.writeMATSimNetwork: A copy of the network used by JDEQSim will be written to outputs folder (typically only needed for debugging).
* physsim.ptSampleSize: A scaling factor used to reduce the seating capacity of all transit vehicles. This is typically used in the context of running a partial sample of the population, it is advisable to reduce the capacity of the transit vehicles, but not necessarily proportionately. This should be calibrated.
* agentSimPhysSimInterfaceDebugger.enabled: Enables special debugging output.
* skipPhysSim: Turns off the JDEQSim traffic flow simulation. If set to true, then network congestion will not change from one iteration to the next. Typically this is only used for debugging issues that are unrelated to the physsim.
* physsim.bprsim.travelTimeFunction: Travel time function (BPR of free flow). For BPR function see https://en.wikipedia.org/wiki/Route_assignment. Free flow implies that the vehicles go on the free speed on that link.
* physsim.bprsim.minFlowToUseBPRFunction: If the inFlow is below this value then BPR function is not used. Free flow is used in this case.
* physsim.bprsim.inFlowAggregationTimeWindowInSeconds: The length of inFlow aggregation in seconds.
* physsim.parbprsim.numberOfClusters: the number of clusters for PARBPR physsim.
* physsim.parbprsim.syncInterval: The sync interval in seconds for PARBPRsim. When the sim time reaches this interval in a particular cluster then it waits for the other clusters at that time point.


Routing Configuration
^^^^^^^^^^^^^^^^^^^^^
::

# values: R5, staticGH, quasiDynamicGH, nativeCCH (Linux Only)
beam.routing.carRouter="R5"
beam.routing {
  #Base local date in ISO 8061 YYYY-MM-DDTHH:MM:SS+HH:MM
  baseDate = "2016-10-17T00:00:00-07:00"
  transitOnStreetNetwork = true # PathTraversalEvents for transit vehicles
  r5 {
    directory = ${beam.inputDirectory}"/r5"
    # Departure window in min
    departureWindow = "double | 15.0"
    numberOfSamples = "int | 1"
    osmFile = ${beam.routing.r5.directory}"/beamville.osm.pbf"
    osmMapdbFile = ${beam.routing.r5.directory}"/osm.mapdb"
    mNetBuilder.fromCRS = "EPSG:4326"   # WGS84
    mNetBuilder.toCRS = "EPSG:26910"    # UTM10N
    travelTimeNoiseFraction = 0.0
    maxDistanceLimitByModeInMeters {
      bike = 40000
    }
    bikeLaneScaleFactor = 1.0
    bikeLaneLinkIdsFilePath = ""
  }
  startingIterationForTravelTimesMSA = 0
  overrideNetworkTravelTimesUsingSkims = false

  # Set a lower bound on travel times that can possibly be used to override the network-based
  # travel time in the route.This is used to prevent unrealistically fast trips or negative
  # duration trips.
  minimumPossibleSkimBasedTravelTimeInS= 60
  skimTravelTimesScalingFactor =  0.0
  writeRoutingStatistic = false
}

Parameters within beam.routing namespace
* carRouter: type of car router.  The values are R5, staticGH, quasiDynamicGH, nativeCCH (Linux Only) where staticGH is GraphHopper router (when link travel times don't depend on time of the day), quasiDynamicGH is GraphHopper router (link
travel times depend on time of the day), nativeCCH is router that uses native CCH library.
* baseDate: the date which routes are requested on (transit depends on it)
* transitOnStreetNetwork: if set to true transit PathTraversalEvents includes the route links
* r5.directory: the directory that contains R5 data which includes pbf file, GTFS files.
* r5.departureWindow: the departure window for transit requests
* r5.numberOfSamples: Number of Monte Carlo draws to take for frequency searches when doing routing
* r5.osmMapdbFile: osm map db file that is stored to this location
* r5.mNetBuilder.fromCRS: convert network coordinates from this CRS
* r5.mNetBuilder.toCRS: convert network coordinates to this CRS
* r5.travelTimeNoiseFraction: if it's greater than zero some noise to link travel times will be added
* r5.maxDistanceLimitByModeInMeters: one can limit max distance to be used for a particular mode
* r5.bikeLaneScaleFactor: this parameter is intended to make the links with bike lanes to be more preferable when the
    router calculates a route for bikes. The less this scaleFactor the more preferable these links get
* r5.bikeLaneLinkIdsFilePath: the ids of links that have bike lanes
* startingIterationForTravelTimesMSA: ???
* overrideNetworkTravelTimesUsingSkims: travel time is got from skims
* minimumPossibleSkimBasedTravelTimeInS: minimum skim based travel time
* skimTravelTimesScalingFactor: used to scale skim based travel time
* writeRoutingStatistic: if set to true writes origin-destination pairs that a route wasn't found between

Warm Mode
^^^^^^^^^
::

   ##################################################################
   # Warm Mode
   ##################################################################
   # valid options: disabled, full, linkStatsOnly (only link stats is loaded (all the other data is got from the input directory))
   beam.warmStart.type = "disabled"
   #PATH TYPE OPTIONS: PARENT_RUN, ABSOLUTE_PATH
   #PARENT_RUN: can be a director or zip archive of the output directory (e.g. like what get's stored on S3). We should also be able to specify a URL to an S3 output.
   #ABSOLUTE_PATH: a directory that contains required warm stats files (e.g. linkstats and eventually a plans).
   beam.warmStart.pathType = "PARENT_RUN"
   beam.warmStart.path = "https://s3.us-east-2.amazonaws.com/beam-outputs/run149-base__2018-06-27_20-28-26_2a2e2bd3.zip"

* warmStart.enabled: Allows you to point to the output of a previous BEAM run and the network travel times and final plan set from that run will be loaded and used to start a new BEAM run. 
* beam.warmStart.pathType: See above for descriptions.
* beam.warmStart.path: path to the outputs to load. Can we a path on the local computer or a URL in which case outputs will be downloaded.

Ride hail management
^^^^^^^^^^^^^^^^^^^^
::

   ##################################################################
   # RideHail
   ##################################################################
   # Ride Hailing General Params
   beam.agentsim.agents.rideHail.name = "GlobalRHM"
   beam.agentsim.agents.rideHail.initialization.initType = "PROCEDURAL" # Other possible values - FILE
   beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypePrefix = "RH"
   beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId = "Car"
   beam.agentsim.agents.rideHail.initialization.procedural.fractionOfInitialVehicleFleet = "double | 0.1"
   beam.agentsim.agents.rideHail.initialization.procedural.initialLocation.name = "HOME"
   beam.agentsim.agents.rideHail.initialization.procedural.initialLocation.home.radiusInMeters = 10000
   beam.agentsim.agents.rideHail.initialization.filePath = ""
   beam.agentsim.agents.rideHail.initialization.parking.filePath = ""

   beam.agentsim.agents.rideHail.defaultCostPerMile=1.25
   beam.agentsim.agents.rideHail.defaultCostPerMinute=0.75
   beam.agentsim.agents.rideHail.defaultBaseCost = 1.8
   beam.agentsim.agents.rideHail.pooledBaseCost = 1.89
   beam.agentsim.agents.rideHail.pooledCostPerMile = 1.11
   beam.agentsim.agents.rideHail.pooledCostPerMinute = 0.07

   beam.agentsim.agents.rideHail.rideHailManager.radiusInMeters=5000

   # initialLocation(HOME | UNIFORM_RANDOM | ALL_AT_CENTER | ALL_IN_CORNER)
   beam.agentsim.agents.rideHail.initialLocation.name="HOME"
   beam.agentsim.agents.rideHail.initialLocation.home.radiusInMeters=10000

   # allocationManager(DEFAULT_MANAGER | REPOSITIONING_LOW_WAITING_TIMES | EV_MANAGER)
   beam.agentsim.agents.rideHail.allocationManager.name = "DEFAULT_MANAGER"
   beam.agentsim.agents.rideHail.allocationManager.maxWaitingTimeInSec = 900
   beam.agentsim.agents.rideHail.allocationManager.maxExcessRideTime = 0.5 # up to +50%
   beam.agentsim.agents.rideHail.allocationManager.requestBufferTimeoutInSeconds = 0
   # ASYNC_GREEDY_VEHICLE_CENTRIC_MATCHING, ALONSO_MORA_MATCHING_WITH_ASYNC_GREEDY_ASSIGNMENT, ALONSO_MORA_MATCHING_WITH_MIP_ASSIGNMENT
   beam.agentsim.agents.rideHail.allocationManager.matchingAlgorithm = "ALONSO_MORA_MATCHING_WITH_ASYNC_GREEDY_ASSIGNMENT"
   # ALONSO MORA
   beam.agentsim.agents.rideHail.allocationManager.alonsoMora.maxRequestsPerVehicle = 5
   # Reposition
   beam.agentsim.agents.rideHail.allocationManager.pooledRideHailIntervalAsMultipleOfSoloRideHail = 1

   beam.agentsim.agents.rideHail.linkFleetStateAcrossIterations = false

   beam.agentsim.agents.rideHail.repositioningManager.name = "DEFAULT_REPOSITIONING_MANAGER"
   beam.agentsim.agents.rideHail.repositioningManager.timeout = 0
   # Larger value increase probability of the ride-hail vehicle to reposition
   beam.agentsim.agents.rideHail.repositioningManager.demandFollowingRepositioningManager.sensitivityOfRepositioningToDemand = 1
   beam.agentsim.agents.rideHail.repositioningManager.demandFollowingRepositioningManager.sensitivityOfRepositioningToDemandForCAVs = 1
   beam.agentsim.agents.rideHail.repositioningManager.demandFollowingRepositioningManager.numberOfClustersForDemand = 30
   beam.agentsim.agents.rideHail.repositioningManager.demandFollowingRepositioningManager.fractionOfClosestClustersToConsider = 0.2
   beam.agentsim.agents.rideHail.repositioningManager.demandFollowingRepositioningManager.horizon = 1200
   # inverse Square Distance Repositioning Factor
   beam.agentsim.agents.rideHail.repositioningManager.inverseSquareDistanceRepositioningFactor.sensitivityOfRepositioningToDemand = 0.4
   beam.agentsim.agents.rideHail.repositioningManager.inverseSquareDistanceRepositioningFactor.sensitivityOfRepositioningToDistance = 0.9
   beam.agentsim.agents.rideHail.repositioningManager.inverseSquareDistanceRepositioningFactor.predictionHorizon = 3600
   # reposition Low Waiting Times
   beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.repositionCircleRadiusInMeters = 3000
   beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.minimumNumberOfIdlingVehiclesThresholdForRepositioning = 1
   beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.repositionCircleRadisInMeters=3000.0
   beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.minimumNumberOfIdlingVehiclesThreshholdForRepositioning=1
   beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.percentageOfVehiclesToReposition=1.0
   beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.timeWindowSizeInSecForDecidingAboutRepositioning=1200
   beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.allowIncreasingRadiusIfDemandInRadiusLow=true
   beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.minDemandPercentageInRadius=0.1
   # repositioningMethod(TOP_SCORES | KMEANS)
   beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.repositioningMethod="TOP_SCORES"
   beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.keepMaxTopNScores=5
   beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.minScoreThresholdForRepositioning=0.00001
   beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.distanceWeight=0.01
   beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.waitingTimeWeight=4.0
   beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.demandWeight=4.0
   beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes.produceDebugImages=true

   beam.agentsim.agents.rideHail.cav.valueOfTime = 1.00
   # when range below refuelRequiredThresholdInMeters, EV Ride Hail CAVs will charge
   # when range above noRefuelThresholdInMeters, EV Ride Hail CAVs will not charge
   # (between these params probability of charging is linear interpolation from 0% to 100%)
   beam.agentsim.agents.rideHail.human.refuelRequiredThresholdInMeters = 32180.0 # 20 miles
   beam.agentsim.agents.rideHail.human.noRefuelThresholdInMeters = 128720.0 # 80 miles
   beam.agentsim.agents.rideHail.cav.refuelRequiredThresholdInMeters = 16090.0 # 10 miles
   beam.agentsim.agents.rideHail.cav.noRefuelThresholdInMeters = 96540.0 # 60 miles
   beam.agentsim.agents.rideHail.rangeBufferForDispatchInMeters = 10000 # do not dispatch vehicles below this range to ensure enough available to get to charger

   # priceAdjustmentStrategy(KEEP_PRICE_LEVEL_FIXED_AT_ONE | CONTINUES_DEMAND_SUPPLY_MATCHING)
   beam.agentsim.agents.rideHail.surgePricing.priceAdjustmentStrategy="KEEP_PRICE_LEVEL_FIXED_AT_ONE"
   # SurgePricing parameters
   beam.agentsim.agents.rideHail.surgePricing.surgeLevelAdaptionStep=0.1
   beam.agentsim.agents.rideHail.surgePricing.minimumSurgeLevel=0.1
   beam.agentsim.agents.rideHail.surgePricing.priceAdjustmentStrategy = "KEEP_PRICE_LEVEL_FIXED_AT_ONE"
   beam.agentsim.agents.rideHail.surgePricing.numberOfCategories = 6

   beam.agentsim.agents.rideHail.charging.vehicleChargingManager.defaultVehicleChargingManager.mulitnomialLogit.params.drivingTimeMultiplier = -0.01666667 // one minute of driving is one util
   beam.agentsim.agents.rideHail.charging.vehicleChargingManager.defaultVehicleChargingManager.mulitnomialLogit.params.queueingTimeMultiplier = -0.01666667 // one minute of queueing is one util
   beam.agentsim.agents.rideHail.charging.vehicleChargingManager.defaultVehicleChargingManager.mulitnomialLogit.params.chargingTimeMultiplier = -0.01666667 // one minute of charging is one util
   beam.agentsim.agents.rideHail.charging.vehicleChargingManager.defaultVehicleChargingManager.mulitnomialLogit.params.insufficientRangeMultiplier = -60.0 // indicator variable so straight 60 minute penalty if out of range

   beam.agentsim.agents.rideHail.iterationStats.timeBinSizeInSec = 3600.0

* name: RH vehicles prefer parking on parking zones with reservedFor parameter equals to this value
* initialization.initType: type of ridehail fleet initialization
* initialization.procedural.vehicleTypePrefix: the vehicle type prefix that indicates ridehail vehicles
* initialization.procedural.vehicleTypeId: default ridehail vehicle type
* initialization.procedural.fractionOfInitialVehicleFleet: Defines the # of ride hailing agents to create, this ration is multiplied by the parameter total number of household vehicles to determine the actual number of drivers to create. Agents begin the simulation located at or near the homes of existing agents, uniformly distributed.
* initialization.procedural.initialLocation.name: the way to set the initial location for ride-hail vehicles (HOME, RANDOM_ACTIVITY, UNIFORM_RANDOM, ALL_AT_CENTER, ALL_IN_CORNER)
* initialization.procedural.initialLocation.home.radiusInMeters: radius within which the initial location is taken
* initialization.filePath: this file is loaded when initialization.initType is "FILE"
* initialization.parking.filePath: parking zones defined for ridehail fleet; it may be empty.
* defaultCostPerMile: cost per mile for ride hail price calculation for solo riders.
* defaultCostPerMinute: cost per minute for ride hail price calculation for solo riders.
* defaultBaseCost: base RH cost for solo riders
* pooledBaseCost: base RH cost for pooled riders
* pooledCostPerMile: cost per mile for ride hail price calculation for pooled riders.
* pooledCostPerMinute: cost per minute for ride hail price calculation for pooled riders.
* surgePricing.priceAdjustmentStrategy: defines different price adjustment strategies
* surgePricing.surgeLevelAdaptionStep:
* surgePricing.minimumSurgeLevel:
* surgePricing.numberOfCategories:
* radiusInMeters: used during vehicle allocation: considered vehicles that are not further from the request location
  than this value
* allocationManager.name: RideHail resource allocation manager: DEFAULT_MANAGER, POOLING, POOLING_ALONSO_MORA
* allocationManager.maxWaitingTimeInSec: max waiting time for a person during RH allocation
* allocationManager.maxExcessRideTime: max excess ride time fraction
* allocationManager.requestBufferTimeoutInSeconds: ride hail requests are buffered within this time before go to allocation manager
* allocationManager.matchingAlgorithm: matching algorithm
* allocationManager.alonsoMora.maxRequestsPerVehicle: the maximum number of requests that can be considered for a single vehicle
* allocationManager.pooledRideHailIntervalAsMultipleOfSoloRideHail:
* linkFleetStateAcrossIterations: if it is set to true then in the next iteration ride-hail fleet state of charge is initialized with the value from the end of previous iteration
* repositioningManager.name: repositioning manager name (DEFAULT_REPOSITIONING_MANAGER, DEMAND_FOLLOWING_REPOSITIONING_MANAGER, INVERSE_SQUARE_DISTANCE_REPOSITIONING_FACTOR, REPOSITIONING_LOW_WAITING_TIMES, THE_SAME_LOCATION_REPOSITIONING_MANAGER, ALWAYS_BE_REPOSITIONING_MANAGER)
* repositioningManager.timeout: time interval of repositioning
* repositioningManager.demandFollowingRepositioningManager.sensitivityOfRepositioningToDemand: should be in [0, 1]; larger value increase probability of the ride-hail vehicle to reposition
* repositioningManager.demandFollowingRepositioningManager.sensitivityOfRepositioningToDemandForCAVs: the same as sensitivityOfRepositioningToDemand but for CAVs
* repositioningManager.demandFollowingRepositioningManager.numberOfClustersForDemand: number of clusters that activity locations is divided to
* repositioningManager.demandFollowingRepositioningManager.fractionOfClosestClustersToConsider: when finding where to reposition this fraction of closest clusters is considered
* repositioningManager.demandFollowingRepositioningManager.horizon: the time bin size
* repositioningManager.inverseSquareDistanceRepositioningFactor.sensitivityOfRepositioningToDemand: larger value increase probability of the ride-hail vehicle to reposition
* repositioningManager.inverseSquareDistanceRepositioningFactor.sensitivityOfRepositioningToDistance: distance is multiplied by this value
* repositioningManager.inverseSquareDistanceRepositioningFactor.predictionHorizon:
* allocationManager.repositionLowWaitingTimes.repositionCircleRadiusInMeters:
* allocationManager.repositionLowWaitingTimes.minimumNumberOfIdlingVehiclesThresholdForRepositioning:
* allocationManager.repositionLowWaitingTimes.repositionCircleRadisInMeters:
* allocationManager.repositionLowWaitingTimes.minimumNumberOfIdlingVehiclesThreshholdForRepositioning:
* allocationManager.repositionLowWaitingTimes.percentageOfVehiclesToReposition:
* allocationManager.repositionLowWaitingTimes.timeWindowSizeInSecForDecidingAboutRepositioning:
* allocationManager.repositionLowWaitingTimes.allowIncreasingRadiusIfDemandInRadiusLow:
* allocationManager.repositionLowWaitingTimes.minDemandPercentageInRadius:
* allocationManager.repositionLowWaitingTimes.repositioningMethod:
* allocationManager.repositionLowWaitingTimes.keepMaxTopNScores:
* allocationManager.repositionLowWaitingTimes.minScoreThresholdForRepositioning:
* allocationManager.repositionLowWaitingTimes.distanceWeight:
* allocationManager.repositionLowWaitingTimes.waitingTimeWeight:
* allocationManager.repositionLowWaitingTimes.demandWeight:
* allocationManager.repositionLowWaitingTimes.produceDebugImages:
* cav.valueOfTime: is used when searching a parking stall for CAVs
* human.refuelRequiredThresholdInMeters: when range below this value, ride-hail vehicle driven by a human will charge
* human.noRefuelThresholdInMeters: when range above noRefuelThresholdInMeters, ride-hail vehicle driven by a human will not charge
* cav.refuelRequiredThresholdInMeters: when range below this value, EV ride-hail CAVs will charge
* cav.noRefuelThresholdInMeters: when range above noRefuelThresholdInMeters, EV ride-hail CAVs will not charge
* rangeBufferForDispatchInMeters: do not dispatch vehicles below this range to ensure enough available to get to charger
* charging.vehicleChargingManager.defaultVehicleChargingManager.mulitnomialLogit.params.drivingTimeMultiplier: one minute of driving is one util
* charging.vehicleChargingManager.defaultVehicleChargingManager.mulitnomialLogit.params.queueingTimeMultiplier: one minute of queueing is one util
* charging.vehicleChargingManager.defaultVehicleChargingManager.mulitnomialLogit.params.chargingTimeMultiplier: one minute of charging is one util
* charging.vehicleChargingManager.defaultVehicleChargingManager.mulitnomialLogit.params.insufficientRangeMultiplier: indicator variable so straight 60 minute penalty if out of range

* iterationStats.timeBinSizeInSec: time bin size of ride-hail statistic

Secondary activities generation
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
::

    beam.agentsim.agents.tripBehaviors.mulitnomialLogit.generate_secondary_activities = true
    beam.agentsim.agents.tripBehaviors.mulitnomialLogit.intercept_file_path = ${beam.inputDirectory}"/activity-intercepts.csv"
    beam.agentsim.agents.tripBehaviors.mulitnomialLogit.activity_file_path = ${beam.inputDirectory}"/activity-params.csv"
    beam.agentsim.agents.tripBehaviors.mulitnomialLogit.additional_trip_utility = 0.0
    beam.agentsim.agents.tripBehaviors.mulitnomialLogit.max_destination_distance_meters = 16000
    beam.agentsim.agents.tripBehaviors.mulitnomialLogit.max_destination_choice_set_size = 6
    beam.agentsim.agents.tripBehaviors.mulitnomialLogit.destination_nest_scale_factor = 1.0
    beam.agentsim.agents.tripBehaviors.mulitnomialLogit.mode_nest_scale_factor = 1.0
    beam.agentsim.agents.tripBehaviors.mulitnomialLogit.trip_nest_scale_factor = 1.0

* generate_secondary_activities: allow/disallow generation of secondary activities.
* intercept_file_path: input file giving the relative likelihoods of starting different activities at different times of the day.

*
    activity_file_path: input file giving parameters for the different activity types, including mean duration (duration is drawn from an
    exponential distribution with that mean) and value of time multiplier. The value of time multiplier modifies how willing agents are to incur travel time
    and cost in order to accomplish that activity. For example, a value of 0.5 means that they get 50% more value out of participating in that activity
    than they would being at home or work. So, if it's a 30 minute activity, they would on average be willing to spend 15 minutes round trip to participate in it.
    If the value is 2, they get 200% more value, so on average they would be willing to spend 60 minutes round trip commuting to participate in this activity.
    You can adjust the VOT values up or down to get more or less of a given activity.

* additional_trip_utility: this is an intercept value you can add to make all secondary activities more or less likely.

*
    max_destination_distance_meters: this sets a maximum distance in looking for places to participate in secondary activities.
    Increasing it increases the maximum and mean trip distance for secondary activities.

*
    max_destination_choice_set_size: this determines how many options for secondary activity locations an agent chooses between.
    Increasing this number decreases the mean distance traveled to secondary activities and slightly increases the number of trips
    that are made (because the agents are more likely to find a suitable location for a secondary activity nearby)

*
    destination_nest_scale_factor, mode_nest_scale_factor, trip_nest_scale_factor: these three values should all be between zero and one
    and determine the amount of noise in each level of the nested choice process. Increasing destination_nest_scale_factor means
    that people are more likely to choose a less optimal destination, mode_nest_scale_factor means people are more likely
    to value destinations accessible by multiple modes, and trip_nest_scale_factor means that people are more likely
    to take secondary trips even if the costs are greater than the benefits.

Agents and Activities
^^^^^^^^^^^^^^^^^^^^^^^
::

    beam.agentsim.agents.activities.activityTypeToFixedDurationMap = ["<activity type> -> <duration>"]

*
    beam.agentsim.agents.activities.activityTypeToFixedDurationMap - by default is empty. For specified activities the duration will be fixed.
    The durations of the rest activities will be calculated based on activity end time.


Output
^^^^^^^^^
::

    # this will write out plans and throw and exception at the beginning of simulation
    beam.output.writePlansAndStopSimulation = "boolean | false"

*
    beam.output.writePlansAndStopSimulation - if set to true will write plans into 'generatedPlans.csv.gz'
    and stop simulation with exception at the beginning of agentSim iteration.
    The functionality was created to generate full population plans with secondary activities for full unscaled input.

Defining what data BEAM writes out
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

There's the list of parameters responsible for writing out data produced by BEAM.

::

    beam.router.skim.writeSkimsInterval = 0
    beam.router.skim.writeAggregatedSkimsInterval = 0
    beam.router.skim.origin-destination-skimmer.writeAllModeSkimsForPeakNonPeakPeriodsInterval = 0
    beam.router.skim.origin-destination-skimmer.writeFullSkimsInterval = 0
    beam.debug.writeModeChoiceAlternatives = false
    beam.debug.writeRealizedModeChoiceFile = false
    beam.outputs.writeGraphs = true
    beam.outputs.writePlansInterval = 0
    beam.outputs.writeEventsInterval = 1
    beam.outputs.writeAnalysis = true
    beam.outputs.writeR5RoutesInterval = 0
    beam.physsim.writeEventsInterval = 0
    beam.physsim.events.fileOutputFormats = "csv" # valid options: xml(.gz) , csv(.gz), none - DEFAULT: csv.gz
    beam.physsim.events.eventsToWrite = "ActivityEndEvent,ActivityStartEvent,LinkEnterEvent,LinkLeaveEvent,PersonArrivalEvent,PersonDepartureEvent,VehicleEntersTrafficEvent,VehicleLeavesTrafficEvent"
    beam.physsim.writePlansInterval = 0
    beam.physsim.writeRouteHistoryInterval = 10
    beam.physsim.linkStatsWriteInterval = 0
    beam.outputs.generalizedLinkStatsInterval = 0

All integer values that end with 'Interval' mean writing data files at iteration which number % value = 0. In case value = 0
writing is disabled.

* beam.router.skim.writeSkimsInterval: enable writing all skim data for a particular iteration to corresponding files
* beam.router.skim.writeAggregatedSkimsInterval: enable writing all aggregated skim data (for all iterations) to corresponding files
* beam.router.skim.origin-destination-skimmer.writeAllModeSkimsForPeakNonPeakPeriodsInterval: enable writing ODSkims for peak and non-peak time periods to #.skimsODExcerpt.csv.gz
* beam.router.skim.origin-destination-skimmer.writeFullSkimsInterval: enable writing ODSkims for all TAZes presented in the scenario to #.skimsODFull.csv.gz
* beam.outputs.writeGraphs: enable writing activity locations to #.activityLocations.png
* beam.outputs.writePlansInterval: enable writing plans of persons at the iteration to #.plans.csv.gz
* beam.outputs.writeEventsInterval: enable writing AgentSim events to #.events.csv.gz
* beam.outputs.writeAnalysis: enable analysis with python script analyze_events.py and writing different data files
* beam.outputs.writeR5RoutesInterval: enable writing routing requests/responses to files #.routingRequest.parquet, #.routingResponse.parquet, #.embodyWithCurrentTravelTime.parquet
* beam.physsim.writeEventsInterval: enable writing physsim events to #.physSimEvents.csv.gz
* beam.physsim.events.fileOutputFormats: file format for physsim event file; valid options: xml(.gz) , csv(.gz), none - DEFAULT: csv.gz
* beam.physsim.events.eventsToWrite: types of physsim events to write
* beam.physsim.writePlansInterval: enable writing of physsim plans to #.physsimPlans.xml.gz
* beam.physsim.writeRouteHistoryInterval: enable writing route history to #.routeHistory.csv.gz. It contains timeBin,originLinkId,destLinkId,route (link ids)
* beam.physsim.linkStatsWriteInterval: enable writing link statistic to #.linkstats_unmodified.csv.gz"
* beam.outputs.generalizedLinkStatsInterval: enable writing generalized link statistic (with generalized time and cost) to #.generalizedLinkStats.csv.gz