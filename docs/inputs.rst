
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
   beam.agentsim.agents.modalBehaviors.defaultValueOfTime = 8.0
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.transfer = -1.4
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.car_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.walk_transit_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.drive_transit_intercept = 2.0
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hail_transit_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hail_intercept = -1.0
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.walk_intercept = -3.0
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.bike_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.transit_crowding = 0.0
   beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.transit_crowding_percentile = 90
   beam.agentsim.agents.modalBehaviors.lccm.paramFile = ${beam.inputDirectory}"/lccm-long.csv"
   #Toll params
   beam.agentsim.toll.file=${beam.inputDirectory}"/toll-prices.csv"
   

* modeChoiceClass: Selects the choice algorithm to be used by agents to select mode when faced with a choice. Default of ModeChoiceMultinomialLogit is recommended but other algorithms include ModeChoiceMultinomialLogit ModeChoiceTransitIfAvailable ModeChoiceDriveIfAvailable ModeChoiceRideHailIfAvailable ModeChoiceUniformRandom ModeChoiceLCCM.
* defaultValueOfTime: This value of time is used by the ModeChoiceMultinomialLogit choice algorithm unless the value of time is specified in the populationAttributes file.
* params.transfer: Constant utility (where 1 util = 1 dollar) of making transfers during a transit trip.
* params.car_intercept: Constant utility (where 1 util = 1 dollar) of driving.
* params.walk_transit_intercept: Constant utility (where 1 util = 1 dollar) of walking to transit.
* params.drive_transit_intercept: Constant utility (where 1 util = 1 dollar) of driving to transit.
* params.ride_hail_transit_intercept: Constant utility (where 1 util = 1 dollar) of taking ride hail to/from transit.
* params.ride_hail_intercept: Constant utility (where 1 util = 1 dollar) of taking ride hail.
* params.walk_intercept: Constant utility (where 1 util = 1 dollar) of walking.
* params.bike_intercept: Constant utility (where 1 util = 1 dollar) of biking.
* params.transit_crowding: Multiplier utility of avoiding "crowded" transit vehicle. Should be negative.
* params.transit_crowding_percentile: Which percentile to use to get the occupancyLevel (number of passengers / vehicle capacity). The route may have different occupancy levels during the legs/vehicle stops.
* lccm.paramFile: if modeChoiceClass is set to `ModeChoiceLCCM` this must point to a valid file with LCCM parameters. Otherwise, this parameter is ignored.
* toll.file: File path to a file with static road tolls. Note, this input will change in future BEAM release where time-varying tolls will possible.

Vehicles and Population
^^^^^^^^^^^^^^^^^^^^^^^
::

   #BeamVehicles Params
   beam.agentsim.agents.vehicles.beamFuelTypesFile = ${beam.inputDirectory}"/beamFuelTypes.csv"
   beam.agentsim.agents.vehicles.beamVehicleTypesFile = ${beam.inputDirectory}"/vehicleTypes.csv"
   beam.agentsim.agents.vehicles.beamVehiclesFile = ${beam.inputDirectory}"/vehicles.csv"

* useBikes: simple way to disable biking, set to true if vehicles file does not contain any data on biking.
* beamFuelTypesFile: configure fuel fuel pricing.
* beamVehicleTypesFile: configure vehicle properties including seating capacity, length, fuel type, fuel economy, and refueling parameters.
* beamVehiclesFile: replacement to legacy MATSim vehicles.xml file. This must contain an Id and vehicle type for every vehicle id contained in households.xml.

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


Warm Mode
^^^^^^^^^
::

   ##################################################################
   # Warm Mode
   ##################################################################
   beam.warmStart.enabled = false
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
   beam.agentsim.agents.rideHail.numDriversAsFractionOfPopulation=0.1
   beam.agentsim.agents.rideHail.defaultCostPerMile=1.25
   beam.agentsim.agents.rideHail.defaultCostPerMinute=0.75
   beam.agentsim.agents.rideHail.vehicleTypeId="BEV"
   beam.agentsim.agents.rideHail.refuelThresholdInMeters=5000.0
   beam.agentsim.agents.rideHail.refuelLocationType="AtRequestLocation"
   # SurgePricing parameters
   beam.agentsim.agents.rideHail.surgePricing.surgeLevelAdaptionStep=0.1
   beam.agentsim.agents.rideHail.surgePricing.minimumSurgeLevel=0.1

   # priceAdjustmentStrategy(KEEP_PRICE_LEVEL_FIXED_AT_ONE | CONTINUES_DEMAND_SUPPLY_MATCHING)
   beam.agentsim.agents.rideHail.surgePricing.priceAdjustmentStrategy="KEEP_PRICE_LEVEL_FIXED_AT_ONE"

   beam.agentsim.agents.rideHail.rideHailManager.radiusInMeters=5000

   # initialLocation(HOME | UNIFORM_RANDOM | ALL_AT_CENTER | ALL_IN_CORNER)
   beam.agentsim.agents.rideHail.initialLocation.name="HOME"
   beam.agentsim.agents.rideHail.initialLocation.home.radiusInMeters=10000

   # allocationManager(DEFAULT_MANAGER | REPOSITIONING_LOW_WAITING_TIMES | EV_MANAGER)
   beam.agentsim.agents.rideHail.allocationManager.name="EV_MANAGER"
   beam.agentsim.agents.rideHail.allocationManager.timeoutInSeconds=300
   beam.agentsim.agents.rideHail.allocationManager.randomRepositioning.repositioningShare=0.2

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

   beam.agentsim.agents.rideHail.iterationStats.timeBinSizeInSec=3600

* numDriversAsFractionOfPopulation: Defines the # of ride hailing drivers to create, this ration is multiplied by the parameter beam.agentsim.numAgents to determine the actual number of drivers to create. Drivers begin the simulation located at or near the homes of existing agents, uniformly distributed.
* defaultCostPerMile: One component of the 2 part price of ride hail calculation.
* defaultCostPerMinute: One component of the 2 part price of ride hail calculation.
* vehicleTypeId: What vehicle type is used for ride hail vehicles. This is primarily relevant for when allocationManager is `EV_MANAGER`.
* refuelThresholdInMeters: One the fuel level (state of charge for EVs) of the vehicle falls below the level corresponding to this parameter, the `EV_MANAGER` will dispatch the vehicle to refuel. Note, do not make this value greate than 80% of the total vehicle range to avoid complications associated with EV fast charging.
* refuelLocationType: One of `AtRequestLocation` or `AtTAZCenter` which controls whether the vehicle is assumed to charge at the it's present location (`AtRequestLocation`) or whether it will drive to a nearby charging depot (`AtTAZCenter`).
* allocationManager.name: Controls whether fleet management is simple (DEFAULT_MANAGER for no repositioning, no refueling), includes repositioing (REPOSITIONING_LOW_WAITING_TIMES) or includes both repositioning and refueling (EV_MANAGER)
* allocationManager.timeoutInSeconds: How frequently does the manager make fleet repositioning decisions.
* beam.agentsim.agents.rideHail.allocationManager.repositionLowWaitingTimes: All of these parameters control the details of repositioning, more documentation will be posted for these soon.

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
