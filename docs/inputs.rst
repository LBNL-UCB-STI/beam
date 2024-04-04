
.. _model-inputs:

Model Inputs
============

Configuration file
------------------
The BEAM configuration file controls where BEAM will source input data and the value of parameters. To see an example of the latest version of this file:

https://github.com/LBNL-UCB-STI/beam/blob/master/test/input/beamville/beam.conf

The BEAM configuration file contains a hybrid between parameters from MATSim (see namespace `matsim` in the config file). Not all of the matsim parameters are used by BEAM. Only the specific MATSim parameters described in this document are relevant. Modifying the other parameters will have no impact. In future releases of BEAM, the irrelevant parameters will be removed.

In order to see example configuration options for a particular release of BEAM replace `master` in the above URL with the version number, e.g. for Version v0.6.2 go to this link:

https://github.com/LBNL-UCB-STI/beam/blob/v0.6.2/test/input/beamville/beam.conf

BEAM follows the `MATSim convention`_ for most of the inputs required to run a simulation, though specifying the road network and transit system is based on the `R5 requirements`_. Refer to these external documentation for details on the following inputs.

.. _MATSim convention: https://matsim.org/docs
.. _R5 requirements: https://github.com/conveyal/r5

* The person population and corresponding attributes files (e.g. `population.xml` and `populationAttributes.xml`)
* The household population and corresponding attributes files (e.g. `households.xml` and `householdAttributes.xml`)
* A directory containing network and transit data used by R5 (e.g. `r5/`)
* The open street map network (e.g. `r5/beamville.osm`)
* GTFS archives, one for each transit agency (e.g. `r5/bus.zip`)

Config Options

The following is a list of the most commonly used configuration options in approximate order of appearance in the beamville example config file (order need not be preserved so it is ok to rearrange options). A complete listing will be added to this documentation soon.

General parameters
^^^^^^^^^^^^^^^^^^
::

   beam.inputDirectory = "/test/input/beamville"
   beam.agentsim.simulationName = "beamville"
   beam.agentsim.agentSampleSizeAsFractionOfPopulation = 1.0
   beam.agentsim.randomSeedForPopulationSampling = "int?"
   beam.agentsim.fractionOfPlansWithSingleActivity = 0.0
   beam.agentsim.thresholdForWalkingInMeters = 1000
   beam.agentsim.thresholdForMakingParkingChoiceInMeters = 100
   beam.agentsim.schedulerParallelismWindow = 30
   beam.agentsim.timeBinSize = 3600
   beam.agentsim.lastIteration = 0
   beam.agentsim.endTime = "30:00:00"
   beam.agentsim.scheduleMonitorTask.initialDelay = 1
   beam.agentsim.scheduleMonitorTask.interval = 30
   beam.agentsim.snapLocationAndRemoveInvalidInputs = false
   beam.outputs.baseOutputDirectory = "output"
   beam.outputs.addTimestampToOutputDirectory = true
   beam.logger.keepConsoleAppenderOn = true

* inputDirectory: Base scenario input directory.
* simulationName: Used as a prefix when creating an output directory to store simulation results.
* agentSampleSizeAsFractionOfPopulation: fraction of agents that is kept after loading the scenario.
* randomSeedForPopulationSampling: if defined then this seed is used to sample the population.
* fractionOfPlansWithSingleActivity: if greater than zero then this fraction of population is not going to Work activities. This parameter can be used to simulate lockdowns.
* thresholdForWalkingInMeters: Used to determine whether a PersonAgent needs to route a walking path through the network to get to a parked vehicle. If the vehicle is closer than thresholdForWalkingInMeters in Euclidean distance, then the walking trip is assumed to be instantaneous. Note, for performance reasons, we do not recommend values for this threshold less than 100m.
* thresholdForMakingParkingChoiceInMeters: Similar to thresholdForWalkingInMeters, this threshold determines the point in a driving leg when the PersonAgent initiates the parking choice processes. So for 1000m, the agent will drive until she is <=1km from the destination and then seek a parking space.
* schedulerParallelismWindow: This controls the discrete event scheduling window used by BEAM to achieve within-day parallelism. The units of this parameter are in seconds and the larger the window, the better the performance of the simulation, but the less chronologically accurate the results will be.
* timeBinSize: For most auto-generated output graphs and tables, this parameter will control the resolution of time-varying outputs.
* lastIteration: Number of the last iteration (zero based).
* endTime: String indicating the end of simulation time ("hh:mm:ss").
* scheduleMonitorTask.initialDelay: Initial delay for schedule monitor task (seconds).
* scheduleMonitorTask.interval: Interval between schedule monitor task (seconds). This task includes stuck agent detection and logging out the scheduler metric.
* snapLocationAndRemoveInvalidInputs: If it's true Beam validate the scenario, snap activity locations to the nearest link, remove wrong locations.
* outputs.baseOutputDirectory: The outputDirectory is the base directory where outputs will be written. The beam.agentsim.simulationName param will be used as the name of a sub-directory beneath the baseOutputDirectory for simulation results.
* outputs.addTimestampToOutputDirectory: if true a timestamp will be added to the output directory, e.g. "beamville_2017-12-18_16-48-57"
* logger.keepConsoleAppenderOn: if false then beam doesn't log

Scenario parameters
^^^^^^^^^^^^^^^^^^^
::

    beam.exchange.scenario.source

* source - Scenario source: Beam, UrbanSim, UrbanSim_v2, Generic

Beam scenario
~~~~~~~~~~~~~
::

    beam.exchange.scenario.fileFormat = "xml"
    beam.agentsim.agents.plans.inputPlansFilePath = ${beam.inputDirectory}"/population.xml.gz"
    beam.input.simulationPrefix = ${beam.agentsim.simulationName}
    beam.input.lastBaseOutputDir = ${beam.outputs.baseOutputDirectory}
    beam.agentsim.agents.plans.inputPersonAttributesFilePath = ${beam.inputDirectory}"/populationAttributes.xml.gz"
    beam.agentsim.agents.plans.merge.fraction = 0.0
    beam.agentsim.agents.households.inputFilePath = ${beam.inputDirectory}"/households.xml.gz"
    beam.agentsim.agents.households.inputHouseholdAttributesFilePath = ${beam.inputDirectory}"/householdAttributes.xml.gz"
    beam.agentsim.agents.population.useVehicleSampling = false
    beam.agentsim.agents.population.industryRemovalProbabilty.enabled = false
    beam.agentsim.agents.population.industryRemovalProbabilty.inputFilePath = ""
    beam.agentsim.agents.population.industryRemovalProbabilty.removalStrategy = "RemovePersonFromScenario"
    beam.spatial.localCRS = "epsg:32631"
    beam.spatial.boundingBoxBuffer = 5000

* exchange.scenario.fileFormat - input file format for scenario loader can be "xml", "csv" or "parquet"
* input.lastBaseOutputDir - for sequential beam runs (some data will be loaded from the latest found run in this directory)
* input.simulationPrefix - this prefix is used to find the last run output directory within beam.input.lastBaseOutputDir directory.
* plans.inputPlansFilePath - person plans file.
* plans.inputPersonAttributesFilePath - person attributes file.
* merge.fraction - fraction of input plans (taken from the beam.input.lastBaseOutputDir) to be merged into the latest output plans.
* households.inputFilePath - file containing household data.
* households.inputHouseholdAttributesFilePath - household attributes file.
* population.useVehicleSampling - do not read vehicles from `vehiclesFilePath`. Vehicles are going to be created according to the corresponding configuration.
* population.industryRemovalProbabilty.enabled - enables modifying persons that has work activities in their plans.
* population.industryRemovalProbabilty.inputFilePath - a csv file with a header "industry,removal_probability" where industry is the person industry,removal_probability is the probability of removal this person or their plans depending on the strategy.
* population.industryRemovalProbabilty.removalStrategy - the strategy to be used for industry population modification. Options: RemovePersonFromScenario, KeepPersonButRemoveAllActivities.
* spatial.localCRS - What crs to use for distance calculations, must be in units of meters.
* spatial.boundingBoxBuffer - Meters of buffer around network for defining extend of spatial indices.

Urbansim scenario
~~~~~~~~~~~~~~~~~
::

    beam.exchange.scenario.folder = ""
    beam.exchange.scenario.modeMap = []
    beam.exchange.scenario.convertWgs2Utm = false
    beam.exchange.scenario.urbansim.activitySimEnabled = false
    beam.exchange.scenario.urbansim.scenarioLoadingTimeoutSeconds = 3000

    beam.urbansim.fractionOfModesToClear {
      allModes = 0.0
      car = 0.0
      bike = 0.0
      walk = 0.0
      walk_transit = 0.0
      drive_transit = 0.0
    }

* exchange.scenario.folder - path to an UrbanSim data folder.
* exchange.scenario.modeMap - contains mapping of UrbanSim modes to Beam modes. I.e. "DRIVEALONEFREE -> car".
* exchange.scenario.convertWgs2Utm - defines if the scenario contains coordinates in WGS.
* beam.exchange.scenario.urbansim.activitySimEnabled - enables generating chosen/realized mode graph for commutes.
* beam.exchange.scenario.urbansim.scenarioLoadingTimeoutSeconds - urbansim scenario loading timeout.
* beam.exchange.scenario.urbansim.fractionOfModesToClear - clears that fraction of the defined modes in people plans.

Freight parameters
~~~~~~~~~~~~~~~~~~
::

    beam.agentsim.agents.freight {
      enabled = false
      plansFilePath = ${beam.inputDirectory}"/freight/payload-plans.csv"
      toursFilePath = ${beam.inputDirectory}"/freight/freight-tours.csv"
      carriersFilePath = ${beam.inputDirectory}"/freight/freight-carriers.csv"
      reader = "Generic"
      isWgs = false
      generateFixedActivitiesDurations = false
      name = "Freight"
      nonHGVLinkWeightMultiplier = 2.0
      tourSampleSizeAsFractionOfTotal = 1.0
      carrierParkingFilePath = ""
      vehicleTypesFilePath = ""
      replanning {
        disableAfterIteration = -1
        departureTime = 28800
        strategy = "singleTour"
      }
    }

* enabled - enables freight part of the scenario.
* plansFilePath - file containing payload plans.
* toursFilePath - file containing freight tours.
* carriersFilePath - file containing freight carriers data.
* reader - only "Generic" value is supported.
* isWgs - defines whether location coordinates are in WGS or UTM system.
* generateFixedActivitiesDurations - allows to assign a fixed duration to freight services (loading, unloading). In this case if a freight vehicle is late to the service location then it would stay there that assigned fixed duration.
* name - freight vehicle manager name. It also can be put as `reservedFor` value of parking zones.
* nonHGVLinkWeightMultiplier - a multiplier for travel cost for truck traveling by non-HGV (heavy goods vehicle) links.
* tourSampleSizeAsFractionOfTotal - Sampled fraction of total tours. Value should be within [0,1].
* carrierParkingFilePath - an optional parking file for freight vehicles.
* vehicleTypesFilePath - an optional freight vehicle types file.
* replanning.disableAfterIteration - freight replanning is disabled after the iteration of this number.
* replanning.departureTime - defined in seconds since midnight and used only if strategy is "wholeFleet". The vehicle departure times are distributed in time interval ±1 hour.
* strategy - possible options: singleTour (when single freight tours are rearranged. A vehicle is assigned on the same services defined in the tour), wholeFleet (when all the fleet vehicles are rearranged. Each vehicle can be assigned to any service)


Mode choice parameters
^^^^^^^^^^^^^^^^^^^^^^
::

   beam.agentsim.agents.modalBehaviors.modeChoiceClass = "ModeChoiceMultinomialLogit"
   beam.agentsim.agents.modalBehaviors.maximumNumberOfReplanningAttempts = 3
   beam.agentsim.agents.modalBehaviors.defaultValueOfTime = 8.0
   beam.agentsim.agents.modalBehaviors.minimumValueOfTime = 7.25
   beam.agentsim.agents.modalBehaviors.transitVehicleTypeVOTMultipliers = ["BUS-DEFAULT:1.0","RAIL-DEFAULT:1.0","FERRY-DEFAULT:1.0","SUBWAY-DEFAULT:1.0","CABLE_CAR-DEFAULT:1.0","TRAM-DEFAULT:1.0"]
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
   beam.agentsim.agents.modalBehaviors.bikeMultiplier.commute.ageGT50 = 1.0
   beam.agentsim.agents.modalBehaviors.bikeMultiplier.noncommute.ageGT50 = 1.0
   beam.agentsim.agents.modalBehaviors.bikeMultiplier.commute.ageLE50 = 1.0
   beam.agentsim.agents.modalBehaviors.bikeMultiplier.noncommute.ageLE50 = 1.0
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
   beam.agentsim.agents.modalBehaviors.multinomialLogit.params.transfer = -1.4
   beam.agentsim.agents.modalBehaviors.multinomialLogit.params.transit_crowding = 0.0
   beam.agentsim.agents.modalBehaviors.multinomialLogit.params.transit_crowding_percentile = 90
   beam.agentsim.agents.modalBehaviors.multinomialLogit.params.transit_crowding_VOT_multiplier = 0.0
   beam.agentsim.agents.modalBehaviors.multinomialLogit.params.transit_crowding_VOT_threshold = 0.5
   beam.agentsim.agents.modalBehaviors.multinomialLogit.params.car_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.multinomialLogit.params.cav_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.multinomialLogit.params.walk_transit_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.multinomialLogit.params.drive_transit_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.multinomialLogit.params.ride_hail_transit_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.multinomialLogit.params.ride_hail_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.multinomialLogit.params.ride_hail_pooled_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.multinomialLogit.params.walk_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.multinomialLogit.params.bike_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.multinomialLogit.params.bike_transit_intercept = 0.0
   beam.agentsim.agents.modalBehaviors.multinomialLogit.params.ride_hail_subscription = 0.0
   beam.agentsim.agents.modalBehaviors.multinomialLogit.utility_scale_factor = 1.0
   beam.agentsim.agents.modalBehaviors.lccm.filePath = ${beam.inputDirectory}"/lccm-long.csv"
   #Toll params
   beam.agentsim.toll.filePath=${beam.inputDirectory}"/toll-prices.csv"
   

* modeChoiceClass: Selects the choice algorithm to be used by agents to select mode when faced with a choice. Default of ModeChoiceMultinomialLogit is recommended but other algorithms include ModeChoiceMultinomialLogit ModeChoiceTransitIfAvailable ModeChoiceDriveIfAvailable ModeChoiceRideHailIfAvailable ModeChoiceUniformRandom ModeChoiceLCCM.
* maximumNumberOfReplanningAttempts: Replanning happens if a Person cannot have some resource required to continue trip in the chosen mode. If the number of replanning exceeded this value WALK mode is chosen.
* defaultValueOfTime: This value of time is used by the ModeChoiceMultinomialLogit choice algorithm unless the value of time is specified in the populationAttributes file.
* minimumValueOfTime: value of time cannot be lower than this value
* transitVehicleTypeVOTMultipliers: The types have to be in sync with file pointed in the parameter `beam.agentsim.agents.vehicles.vehicleTypesFilePath`: ["BUS-DEFAULT:1.0","RAIL-DEFAULT:1.0","FERRY-DEFAULT:1.0","SUBWAY-DEFAULT:1.0","CABLE_CAR-DEFAULT:1.0","TRAM-DEFAULT:1.0"]
* modeVotMultiplier: allow to modify value of time for a particular trip mode
* modeVotMultiplier.waiting: not used now
* overrideAutomationLevel: the value to be used to override the vehicle automation level when calculating generalized time of ride-hail legs
* overrideAutomationForVOTT: enabled overriding of automation level (see overrideAutomationLevel)
* poolingMultiplier: this multiplier is used when calculating generalized time for pooled ride-hail trip for a particular vehicle automation level
* bikeMultiplier: Value of time multiplier for bike situations: commute or non-commute trip, age of the rider.
* highTimeSensitivity/lowTimeSensitivity when a person go by car (not ride-hail) these params allow to set generalized time multiplier for a particular link for different situations: work trip/other trips, high/low traffic, highway or not, vehicle automation level
* params.transfer: Constant utility (where 1 util = 1 dollar) of making transfers during a transit trip.
* params.transit_crowding: Multiplier utility of avoiding "crowded" transit vehicle. Should be negative.
* params.transit_crowding_percentile: Which percentile to use to get the occupancyLevel (number of passengers / vehicle capacity). The route may have different occupancy levels during the legs/vehicle stops.
* params.transit_crowding_VOT_multiplier: This value is used to multiply crowding when calculated value of time in a crowded vehicle .
* params.transit_crowding_VOT_threshold: How full should be a vehicle to turn on crowding calculation. 0 - empty, 1 - full.
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
* params.ride_hail_subscription: Ride-hail subscription value. It is used when calculated the best trip proposal among multiple ride-hail fleets.
* utility_scale_factor: amount by which utilities are scaled before evaluating probabilities. Smaller numbers leads to less determinism
* lccm.filePath: if modeChoiceClass is set to `ModeChoiceLCCM` this must point to a valid file with LCCM parameters. Otherwise, this parameter is ignored.
* toll.filePath: File path to a file with static road tolls. Note, this input will change in future BEAM release where time-varying tolls will possible.

Trip Behavior
^^^^^^^^^^^^^
::

    beam.agentsim.agents.tripBehaviors.carUsage.minDistanceToTrainStop = 0.0
    beam.agentsim.agents.rideHailTransit.modesToConsider = "MASS"

* tripBehaviors.carUsage.minDistanceToTrainStop: Persons cannot entering/leaving cars (including ride-hail) within a circle of this radius around any train stops.
* rideHailTransit.modesToConsider: Ride-hail transit trips happens only if transit is of one of the modes defined here (comma separated). It aso allows values `ALL` which means all possible transit modes and `MASS` which means any of FERRY, TRANSIT, RAIL, SUBWAY or TRAM.

Choosing Parking
^^^^^^^^^^^^^^^^
::

    beam.agentsim.agents.parking.multinomialLogit.params.rangeAnxietyMultiplier = -0.5
    beam.agentsim.agents.parking.multinomialLogit.params.distanceMultiplier = -0.086
    beam.agentsim.agents.parking.multinomialLogit.params.parkingPriceMultiplier = -0.005
    beam.agentsim.agents.parking.multinomialLogit.params.homeActivityPrefersResidentialParkingMultiplier = 1.0
    beam.agentsim.agents.parking.multinomialLogit.params.enrouteDetourMultiplier = -0.05
    beam.agentsim.agents.parking.rangeAnxietyBuffer = 20000.0
    beam.agentsim.agents.parking.minSearchRadius = 250.00
    beam.agentsim.agents.parking.maxSearchRadius = 8046.72
    beam.agentsim.agents.parking.searchMaxDistanceRelativeToEllipseFoci = 4.0
    beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds = 60.0
    beam.agentsim.agents.parking.estimatedMeanEnRouteChargingDurationInSeconds = 1800.0
    beam.agentsim.agents.parking.fractionOfSameTypeZones = 0.5
    beam.agentsim.agents.parking.minNumberOfSameTypeZones = 10
    beam.agentsim.agents.parking.forceParkingType = false
    beam.agentsim.agents.vehicles.destination.refuelRequiredThresholdInMeters = 482803 # 300 miles
    beam.agentsim.agents.vehicles.destination.noRefuelThresholdInMeters = 482803 # 300 miles
    beam.agentsim.agents.vehicles.destination.home.refuelRequiredThresholdInMeters = 482803 # 300 miles
    beam.agentsim.agents.vehicles.destination.home.noRefuelThresholdInMeters = 482803 # 300 miles
    beam.agentsim.agents.vehicles.destination.work.refuelRequiredThresholdInMeters = 482803 # 300 miles
    beam.agentsim.agents.vehicles.destination.work.noRefuelThresholdInMeters = 482803 # 300 miles
    beam.agentsim.agents.vehicles.destination.secondary.refuelRequiredThresholdInMeters = 482803 # 300 miles
    beam.agentsim.agents.vehicles.destination.secondary.noRefuelThresholdInMeters = 482803 # 300 miles


* multinomialLogit.params.rangeAnxietyMultiplier - utility multiplier of range anxiety factor.
* multinomialLogit.params.distanceMultiplier - utility multiplier of walking distance cost.
* multinomialLogit.params.parkingPriceMultiplier - utility multiplier of parking cost.
* multinomialLogit.params.homeActivityPrefersResidentialParkingMultiplier - utility multiplier of matching Home activity and Residential parking.
* multinomialLogit.params.enrouteDetourMultiplier - utility multiplier of traveling to enroute charging location cost.
* rangeAnxietyBuffer - if our remaining range exceeds our remaining tour plus this many meters, then we feel no anxiety; default 20k.
* minSearchRadius - radius of a circle around requested parking location that the parking search starts off.
* maxSearchRadius - max parking search radius.
* searchMaxDistanceRelativeToEllipseFoci - max distance to both foci of an ellipse (used in en-route parking).
* estimatedMeanEnRouteChargingDurationInSeconds - mean en-route charging duration in seconds.
* fractionOfSameTypeZones - fraction of the zones of certain type to be considered among all the same type zones within the current radius.
* minNumberOfSameTypeZones - min number of the zones of certain type to be considered among all the same type zones within certain radius.
* forceParkingType - if enabled forces the corresponding parking type for certain activities.
* vehicles.destination - these params defines if a charging is needed at the destination for electric vehicles. If the range is inside (refuelRequiredThresholdInMeters, noRefuelThresholdInMeters) then charging requirement is determined by probability. `destination.home` is Home activity; `destination.work` is Work activity; `destination.secondary` is Wherever activity; `destination` is any other activity.

Vehicles
^^^^^^^^
::

   #BeamVehicles Params
   beam.agentsim.agents.vehicles.fuelTypesFilePath = ${beam.inputDirectory}"/beamFuelTypes.csv"
   beam.agentsim.agents.vehicles.vehicleTypesFilePath = ${beam.inputDirectory}"/vehicleTypes.csv"
   beam.agentsim.agents.vehicles.vehiclesFilePath = ${beam.inputDirectory}"/vehicles.csv"
   beam.agentsim.agents.vehicles.fractionOfInitialVehicleFleet = 1.0
   beam.agentsim.agents.vehicles.downsamplingMethod = "SECONDARY_VEHICLES_FIRST"
   beam.agentsim.agents.vehicles.vehicleAdjustmentMethod = "UNIFORM"
   beam.agentsim.agents.vehicles.fractionOfPeopleWithBicycle = 1.0
   beam.agentsim.agents.vehicles.linkToGradePercentFilePath = ""
   beam.agentsim.agents.vehicles.meanPrivateVehicleStartingSOC = 1.0
   beam.agentsim.agents.vehicles.linkSocAcrossIterations = false
   beam.agentsim.agents.vehicles.meanRidehailVehicleStartingSOC = 1.0
   beam.agentsim.agents.vehicles.transitVehicleTypesByRouteFile = ""
   beam.agentsim.agents.vehicles.generateEmergencyHouseholdVehicleWhenPlansRequireIt = false
   beam.agentsim.agents.vehicles.replanOnTheFlyWhenHouseholdVehiclesAreNotAvailable = false
   beam.agentsim.agents.vehicles.enroute.refuelRequiredThresholdOffsetInMeters = 0.0 # 0 miles
   beam.agentsim.agents.vehicles.enroute.noRefuelThresholdOffsetInMeters = 32186.9 # 20 miles
   beam.agentsim.agents.vehicles.enroute.noRefuelAtRemainingDistanceThresholdInMeters = 500 # 500 meters
   beam.agentsim.agents.vehicles.enroute.remainingDistanceWrtBatteryCapacityThreshold = 2 # this represents +/- the number of times an agent will enroute when ranger is x times lower than the remaining distance

* useBikes: simple way to disable biking, set to true if vehicles file does not contain any data on biking.
* fuelTypesFilePath: configure fuel fuel pricing.
* vehicleTypesFilePath: configure vehicle properties including seating capacity, length, fuel type, fuel economy, and refueling parameters.
* vehiclesFilePath: replacement to legacy MATSim vehicles.xml file. This must contain an Id and vehicle type for every vehicle id contained in households.xml.
* fractionOfInitialVehicleFleet: in urbansim scenario the number of private vehicles is downsampled to this fraction.
* downsamplingMethod: in urbansim scenario the method to be used to downsample private vehicles. Possible values: SECONDARY_VEHICLES_FIRST, RANDOM.
* vehicleAdjustmentMethod: determines which vehicle type to use when an emergency vehicle (when no vehicle left) is created for a particular household. Possible values: UNIFORM, INCOME_BASED, SINGLE_TYPE.
* fractionOfPeopleWithBicycle: fraction of people with a bicycle.
* linkToGradePercentFilePath: file containing link grades data.
* meanPrivateVehicleStartingSOC: private electric vehicles state of charge is set around this value.
* linkSocAcrossIterations: set the initial state of charge of the private vehicles the same as the SoC at the end of the previos iteration.
* meanRidehailVehicleStartingSOC: ride-hail electric vehicles state of charge is set around this value.
* transitVehicleTypesByRouteFile: file containing mapping transit agencies/routes to transit vehicle types.
* generateEmergencyHouseholdVehicleWhenPlansRequireIt: if true then a private vehicle is generated if a person plan requires to use a car.
* replanOnTheFlyWhenHouseholdVehiclesAreNotAvailable: if true then replanning happens immediately if a car or bike is not available for a persons whose plan requires to use a car or a bike.
* enroute.refuelRequiredThresholdOffsetInMeters, noRefuelThresholdOffsetInMeters: If the range is inside (refuelRequiredThresholdInMeters, noRefuelThresholdInMeters) then en-route refueling requirement is determined by probability.
* enroute.noRefuelAtRemainingDistanceThresholdInMeters: If the distance to the destination is less than this threshold then no en-route refueling happens.
* enroute.remainingDistanceWrtBatteryCapacityThreshold: If the distance relative to the vehicle total range is greater then this threshold no en-route refueling happens.

Shared vehicle fleets
~~~~~~~~~~~~~~~~~~~~~
::

    beam.agentsim.agents.vehicles.dummySharedCar.vehicleTypeId = "sharedVehicle-sharedCar"
    beam.agentsim.agents.vehicles.dummySharedBike.vehicleTypeId = "sharedVehicle-sharedBike"
    beam.agentsim.agents.vehicles.sharedFleets = [
      {
        name = "my-fixed-non-reserving-fleet"
        managerType = "fixed-non-reserving"
        parkingFilePath = ""
        fixed-non-reserving {
          vehicleTypeId = "sharedVehicle-sharedCar",
          maxWalkingDistance = 500
        }
        inexhaustible-reserving {
          vehicleTypeId = "sharedVehicle-sharedCar"
        }
        fixed-non-reserving-fleet-by-taz {
          vehicleTypeId = "sharedVehicle-sharedCar",
          vehiclesSharePerTAZFromCSV = "",
          maxWalkingDistance = 500,
          fleetSize = 10
        }
        reposition {
          name = "my-reposition-algorithm"
          repositionTimeBin = 3600,
          statTimeBin = 300,
          min-availability-undersupply-algorithm {
            matchLimit = 99999
          }
        }
      }
    ]

* beam.agentsim.agents.vehicles.dummySharedCar.vehicleTypeId: dummy (for household emergency vehicles and routing requests) shared car type id (must be in the vehicle types).
* beam.agentsim.agents.vehicles.dummySharedBike.vehicleTypeId dummy (for household emergency vehicles and routing requests) shared bike type id (must be in the vehicle types).
* beam.agentsim.agents.vehicles.sharedFleets: contains an array of shared fleet configuration structures.
* name: Shared fleet name.
* managerType: Fleet manager type (fixed-non-reserving, inexhaustible-reserving, fixed-non-reserving-fleet-by-taz). Type name has a corresponding config setting.
* parkingFilePath: path to a beam parking file that contains parking zone info for the fleet.
* fixed-non-reserving.vehicleTypeId: type id of the vehicles that are in this fleet.
* fixed-non-reserving.maxWalkingDistance: When a person requests for a vehicle this is the max walking distance to the provided vehicle.
* inexhaustible-reserving.vehicleTypeId: type id of the vehicles that are in this fleet.
* fixed-non-reserving-fleet-by-taz.vehicleTypeId: type id of the vehicles that are in this fleet.
* fixed-non-reserving-fleet-by-taz.vehiclesSharePerTAZFromCSV: path to a CSV file that has the following columns: "taz", "x", "y", "fleetShare". It contains shared vehicle fraction in a TAZ of in a coordinate.
* fixed-non-reserving-fleet-by-taz.maxWalkingDistance: When a person requests for a vehicle this is the max walking distance to the provided vehicle.
* fixed-non-reserving-fleet-by-taz.fleetSize: Size of the fleet.
* reposition.name: Repositioning is used only with 'fixed-non-reserving-fleet-by-taz' manager. Name of the algorithm of shared vehicle repositioning: min-availability-undersupply-algorithm, min-availability-observed-algorithm.
* repositioning.repositionTimeBin: repositioning time bin interval.
* repositioning.statTimeBin: statistic time bin: time interval that is used to get the demand data from the previous iterations.
* repositioning.min-availability-observed-algorithm.matchLimit: limit of over-supplied and under-supplied TAZs in min-availability-observed-algorithm.

Population
^^^^^^^^^^
::

   beam.agentsim.populationAdjustment = "DEFAULT_ADJUSTMENT"
   beam.agentsim.agents.bodyType = "BODY-TYPE-DEFAULT"

* populationAdjustment: Population (population plan) changes. Possible values: DEFAULT_ADJUSTMENT (no changes), PERCENTAGE_ADJUSTMENT (assign CAR mode to a half of population), DIFFUSION_POTENTIAL_ADJUSTMENT, EXCLUDE_TRANSIT (remove all transit modes), HALF_TRANSIT (assign transit modes to a half of population) | CAR_RIDE_HAIL_ONLY (Leave only CAR or RIDE_HAIL modes).
* agents.bodyType: The person's body "vehicle" type (this vehicle type must be in the vehicle types file).

TAZs, Scaling, and Physsim Tuning
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
::

   #TAZ params
   beam.agentsim.taz.filePath = ${beam.inputDirectory}"/taz-centers.csv"
   beam.agentsim.taz.tazIdFieldName = "tazId"
   beam.agentsim.taz.parkingFilePath = ${beam.inputDirectory}"/parking/taz-parking-default.csv"
   beam.agentsim.taz.parkingStallCountScalingFactor = 1.0
   beam.agentsim.taz.parkingCostScalingFactor = 1.0
   # Parking Manager name (DEFAULT | PARALLEL)
   beam.agentsim.taz.parkingManager.method = "DEFAULT"
   beam.agentsim.taz.parkingManager.parallel.numberOfClusters = 8
   beam.agentsim.taz.parkingManager.displayPerformanceTimings = false
   beam.agentsim.h3taz.lowerBoundResolution = 6
   beam.agentsim.h3taz.upperBoundResolution = 9
   # Scaling and Tuning Params
   beam.agentsim.tuning.fuelCapacityInJoules = 86400000
   beam.agentsim.tuning.transitCapacity = 0.1
   beam.agentsim.tuning.transitPrice = 1.0
   beam.agentsim.tuning.tollPrice = 1.0
   beam.agentsim.tuning.rideHailPrice = 1.0
   # PhysSim name (JDEQSim | BPRSim | PARBPRSim | CCHRoutingAssignment)
   beam.physsim.name = "JDEQSim
   beam.physsim.eventManager.type = "Auto"
   beam.physsim.eventManager.numberOfThreads = 1

   beam.physsim.pickUpDropOffAnalysis.enabled = false
   beam.physsim.pickUpDropOffAnalysis.secondsFromPickUpPropOffToAffectTravelTime = 600
   beam.physsim.pickUpDropOffAnalysis.additionalTravelTimeMultiplier = 1.0

   # JDEQSim

   beam.physsim.jdeqsim.agentSimPhysSimInterfaceDebugger.enabled = false
   beam.physsim.jdeqsim.cacc.enabled = false
   beam.physsim.jdeqsim.cacc.minRoadCapacity = 2000
   beam.physsim.jdeqsim.cacc.minSpeedMetersPerSec = 20
   beam.physsim.jdeqsim.cacc.speedAdjustmentFactor = 1.0
   beam.physsim.jdeqsim.cacc.capacityPlansWriteInterval = 0

   beam.physsim.cchRoutingAssignment.congestionFactor = 1.0
   beam.physsim.overwriteLinkParamPath = ""
   # PhysSim Scaling Params
   beam.physsim.flowCapacityFactor = 0.0001
   beam.physsim.storageCapacityFactor = 0.0001
   beam.physsim.writeMATSimNetwork = false
   beam.physsim.speedScalingFactor = 1.0
   beam.physsim.maxLinkLengthToApplySpeedScalingFactor = 50.0
   beam.physsim.linkStatsBinSize = 3600
   beam.physsim.ptSampleSize = 1.0
   beam.physsim.eventsForFullVersionOfVia = true
   beam.physsim.eventsSampling = 1.0
   beam.physsim.minCarSpeedInMetersPerSecond = 0.5
   beam.physsim.inputNetworkFilePath = ${beam.routing.r5.directory}"/physsim-network.xml"
   beam.physsim.skipPhysSim = false
   # Travel time function for (PAR)PBR sim (BPR | FREE_FLOW)
   beam.physsim.bprsim.travelTimeFunction = "BPR"
   beam.physsim.bprsim.minFlowToUseBPRFunction = 10
   beam.physsim.bprsim.inFlowAggregationTimeWindowInSeconds = 900
   beam.physsim.parbprsim.numberOfClusters = 8
   beam.physsim.parbprsim.syncInterval = 60
   beam.physsim.network.overwriteRoadTypeProperties {
    enabled = false
    motorway {
      speed = "double?"
      capacity = "int?"
      lanes = "int?"
      alpha = "double?"
      beta = "double?"
    }
    motorwayLink {
      speed = "double?"
      capacity = "int?"
      lanes = "int?"
      alpha = "double?"
      beta = "double?"
    }
    primary {
      speed = "double?"
      capacity = "int?"
      lanes = "int?"
      alpha = "double?"
      beta = "double?"
    }
    primaryLink {
      speed = "double?"
      capacity = "int?"
      lanes = "int?"
      alpha = "double?"
      beta = "double?"
    }
    trunk {
      speed = "double?"
      capacity = "int?"
      lanes = "int?"
      alpha = "double?"
      beta = "double?"
    }
    trunkLink {
      speed = "double?"
      capacity = "int?"
      lanes = "int?"
      alpha = "double?"
      beta = "double?"
    }
    secondary {
      speed = "double?"
      capacity = "int?"
      lanes = "int?"
      alpha = "double?"
      beta = "double?"
    }
    secondaryLink {
      speed = "double?"
      capacity = "int?"
      lanes = "int?"
      alpha = "double?"
      beta = "double?"
    }
    tertiary {
      speed = "double?"
      capacity = "int?"
      lanes = "int?"
      alpha = "double?"
      beta = "double?"
    }
    tertiaryLink {
      speed = "double?"
      capacity = "int?"
      lanes = "int?"
      alpha = "double?"
      beta = "double?"
    }
    minor {
      speed = "double?"
      capacity = "int?"
      lanes = "int?"
      alpha = "double?"
      beta = "double?"
    }
    residential {
      speed = "double?"
      capacity = "int?"
      lanes = "int?"
      alpha = "double?"
      beta = "double?"
    }
    livingStreet {
      speed = "double?"
      capacity = "int?"
      lanes = "int?"
      alpha = "double?"
      beta = "double?"
    }
    unclassified {
      speed = "double?"
      capacity = "int?"
      lanes = "int?"
      alpha = "double?"
      beta = "double?"
    }
    default {
      alpha = 1.0
      beta = 2.0
    }
   }
   beam.physsim.network.maxSpeedInference.enabled = false
   beam.physsim.network.maxSpeedInference.type = "MEAN"
   beam.physsim.duplicatePTE.fractionOfEventsToDuplicate = 0.0
   beam.physsim.duplicatePTE.departureTimeShiftMin = -600
   beam.physsim.duplicatePTE.departureTimeShiftMax = 600
   beam.physsim.network.removeIslands = true



* agentsim.taz.filePath: path to a file specifying the centroid of each TAZ. For performance BEAM approximates TAZ boundaries based on a nearest-centroid approach. The area of each centroid (in m^2) is also necessary to approximate average travel distances within each TAZ (used in parking choice process).
* agentsim.taz.tazIdFieldName: in case TAZ are read from a shape file this parameter defines the taz id attribute of TAZ shapes.
* taz.parkingFilePath: path to a file specifying the parking and charging infrastructure. If any TAZ contained in the taz file is not specified in the parking file, then unlimited free parking is assumed.
* taz.parkingStallCountScalingFactor: number of stalls defined for each parking zone multiplied on this factor.
* taz.parkingCostScalingFactor: parking cost is multiplied on this factor.
* agentsim.taz.parkingManager.method: the name of the parking method. PARALLEL parking manager splits the TAZes into a number of clusters. This allows the users to speed up the searching for parking stalls. But as a tradeoff, it has degraded quality. Usually, 8-16 clusters can provide satisfactory quality on big numbers of TAZes.
* agentsim.taz.parkingManager.parallel.numberOfClusters: the number of clusters for PARALLEL parking manager.
* agentsim.taz.parkingManager.displayPerformanceTimings: if true then performance information for parking manger is logged out.
* agentsim.h3taz.lowerBoundResolution: lower bound of H3 resolution.
* agentsim.h3taz.upperBoundResolution: upper bound of H3 resolution.
* agentsim.tuning.fuelCapacityInJoules: not used.
* tuning.transitCapacity: Scale the number of seats per transit vehicle... actual seats are rounded to nearest whole number. Applies uniformly to all transit vehicles.
* tuning.transitPrice: Scale the price of riding on transit. Applies uniformly to all transit trips.
* tuning.tollPrice: Scale the price to cross tolls.
* tuning.rideHailPrice: Scale the price of ride hailing. Applies uniformly to all trips and is independent of defaultCostPerMile and defaultCostPerMinute described above. I.e. price = (costPerMile + costPerMinute)*rideHailPrice
* physsim.name: Name of the physsim. BPR physsim calculates the travel time of a vehicle for a particular link basing on the inFlow value for that link (number of vehicle entered that link within last n minutes. This value is upscaled to one hour value.). PARBPR splits the network into clusters and simulates vehicle movement for each cluster in parallel.
* physsim.eventManager.type: physsim event manager type. Options: Auto, Sequential, Parallel. You want to choose the one with the best performance. But usually Auto is good enough.
* physsim.eventManager.numberOfThreads: number of threads for parallel event manager. 1 thread usually shows the best performance (async event handling).
* physsim.pickUpDropOffAnalysis.enabled: enables increasing the link travel time basing on the number of pickup and drop-off events happening on the link.
* physsim.pickUpDropOffAnalysis.secondsFromPickUpPropOffToAffectTravelTime: the maximum time interval within which the pickup/drop-off events affecting the link travel time.
* physsim.pickUpDropOffAnalysis.additionalTravelTimeMultiplier: a multiplier that increases travel time depending on the number of pickup/drop-off events.
* python.agentSimPhysSimInterfaceDebugger.enabled: Enables special debugging output.
* physsim.jdeqsim.cacc.enabled: enables modelling impact of Cooperative Adaptive Cruise Control.
* physsim.jdeqsim.cacc.minRoadCapacity: a CACC link must have at least this capacity.
* physsim.jdeqsim.cacc.minSpeedMetersPerSec: a CACC link must have at least this free speed.
* physsim.jdeqsim.cacc.speedAdjustmentFactor: a free speed multiplier for each link
* physsim.jdeqsim.cacc.capacityPlansWriteInterval: on which iterations to write CACC capacity stats.
* physsim.cchRoutingAssignment.congestionFactor: Used to calculate ods number multiplier with following formula: 1 / agentSampleSizeAsFractionOfPopulation * congestionFactor.
* beam.physsim.overwriteLinkParamPath: a csv file path that can be used to overwrite link parameters: capacity, free_speed, length, lanes, alpha, beta.
* physsim.flowCapacityFactor: Flow capacity parameter used by JDEQSim for traffic flow simulation.
* physsim.storageCapacityFactor: Storage capacity parameter used by JDEQSim for traffic flow simulation.
* physsim.writeMATSimNetwork: A copy of the network used by JDEQSim will be written to outputs folder (typically only needed for debugging).
* physsim.speedScalingFactor: Link free speed scaling factor.
* physsim.maxLinkLengthToApplySpeedScalingFactor: Link must be lower or equal to this value to have speedScalingFactor be applied.
* physsim.linkStatsBinSize: Size of time bin for link statistic.
* physsim.ptSampleSize: A scaling factor used to reduce the seating capacity of all transit vehicles. This is typically used in the context of running a partial sample of the population, it is advisable to reduce the capacity of the transit vehicles, but not necessarily proportionately. This should be calibrated.
* physsim.eventsForFullVersionOfVia: enables saving additional events that are support of the full version of Simunto Via visualization software.
* physsim.eventsSampling: fraction of physsim events to be written out.
* physsim.minCarSpeedInMetersPerSecond: this minimal car speed is used in GraphHopper router and also for printing debut output for cases when the actual car speed is below this value.
* physsim.inputNetworkFilePath = ${beam.routing.r5.directory}"/physsim-network.xml"
* skipPhysSim: Turns off the JDEQSim traffic flow simulation. If set to true, then network congestion will not change from one iteration to the next. Typically this is only used for debugging issues that are unrelated to the physsim.
* physsim.bprsim.travelTimeFunction: Travel time function (BPR of free flow). For BPR function see https://en.wikipedia.org/wiki/Route_assignment. Free flow implies that the vehicles go on the free speed on that link.
* physsim.bprsim.minFlowToUseBPRFunction: If the inFlow is below this value then BPR function is not used. Free flow is used in this case.
* physsim.bprsim.inFlowAggregationTimeWindowInSeconds: The length of inFlow aggregation in seconds.
* physsim.parbprsim.numberOfClusters: the number of clusters for PARBPR physsim.
* physsim.parbprsim.syncInterval: The sync interval in seconds for PARBPRsim. When the sim time reaches this interval in a particular cluster then it waits for the other clusters at that time point.
* physsim.overwriteRoadTypeProperties: It allows to override attributes for certain types of links.
* physsim.maxSpeedInference.enabled: enables max speed inference by road type from Open Street Map data.
* physsim.maxSpeedInference.type: Possible types of inference: MEAN, MEDIAN.
* physsim.duplicatePTE.fractionOfEventsToDuplicate: fraction of PathTraversal events to be duplicated. It allows to increase physSim population without increasing agentSim population. The idea behind that is the following - bigger physSim population allows to use higher values of flowCapacityFactor thus reducing the rounding error for links capacity. This should allow better speed calibration without too high agentSim population.
* physsim.duplicatePTE.departureTimeShiftMin: min departure time shift in seconds.
* physsim.duplicatePTE.departureTimeShiftMax: max departure time shift in seconds.
* physsim.network.removeIslands: Removes not connected areas from the network. For a small test OSM map (10-20 nodes) it might be possible that R5 TransportNetwork would incorrectly consider all nodes to be an island and will remove it. Set this to `false` to override that.

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
        directory2 = "String? |"
        # Departure window in min
        departureWindow = "double | 15.0"
        numberOfSamples = "int | 1"
        osmMapdbFile = ${beam.routing.r5.directory}"/osm.mapdb"
        mNetBuilder.fromCRS = "EPSG:4326"   # WGS84
        mNetBuilder.toCRS = "EPSG:26910"    # UTM10N
        travelTimeNoiseFraction = 0.0
        maxDistanceLimitByModeInMeters {
          bike = 40000
        }
        bikeLaneScaleFactor = 1.0
        bikeLaneLinkIdsFilePath = ""
        linkRadiusMeters = 10000.0
        transitAlternativeList = "OPTIMAL"
        suboptimalMinutes = 0
        accessBufferTimeSeconds {
          bike = 60
          bike_rent = 180
          walk = 0
          car = 300
          ride_hail = 0
        }
      }
      gh.useAlternativeRoutes = false
      startingIterationForTravelTimesMSA = 0
      overrideNetworkTravelTimesUsingSkims = false

      # Set a lower bound on travel times that can possibly be used to override the network-based
      # travel time in the route.This is used to prevent unrealistically fast trips or negative
      # duration trips.
      minimumPossibleSkimBasedTravelTimeInS= 60
      skimTravelTimesScalingFactor =  0.0
      writeRoutingStatistic = false
    }
    beam.agentsim.agents.ptFare.filePath = ""
    beam.agentsim.agents.rideHail.freeSpeedLinkWeightMultiplier = 2.0
    beam.agentsim.scenarios.frequencyAdjustmentFile = ""

Parameters within beam.routing namespace

* carRouter: type of car router.  The values are R5, staticGH, quasiDynamicGH, nativeCCH (Linux Only) where staticGH is GraphHopper router (when link travel times don't depend on time of the day), quasiDynamicGH is GraphHopper router (link travel times depend on time of the day), nativeCCH is router that uses native CCH library.
* baseDate: the date which routes are requested on (transit depends on it)
* transitOnStreetNetwork: if set to true transit PathTraversalEvents includes the route links
* r5.directory: the directory that contains R5 data which includes pbf file, GTFS files. If the directory contains multiple pbf files then a random file is loaded.
* r5.directory2: An optional directory that contains R5 data for the second router. It must contain the same pbf file and a subset of the GTFS files that are in the r5.directory (the first r5 directory). I.e. one can leave only the train GTFS file in the directory2. In this case train routes will be provided twice as much. But the first r5 directory must also contains the same train file or the second router will provide routes based on a different network which may lead to errors.
* r5.departureWindow: the departure window for transit requests
* r5.numberOfSamples: Number of Monte Carlo draws to take for frequency searches when doing routing
* r5.osmMapdbFile: osm map db file that is stored to this location
* r5.mNetBuilder.fromCRS: convert network coordinates from this CRS
* r5.mNetBuilder.toCRS: convert network coordinates to this CRS
* r5.travelTimeNoiseFraction: if it's greater than zero some noise to link travel times will be added
* r5.maxDistanceLimitByModeInMeters: one can limit max distance to be used for a particular mode
* r5.bikeLaneScaleFactor: this parameter is intended to make the links with bike lanes to be more preferable when the router calculates a route for bikes. The less this scaleFactor the more preferable these links get
* r5.bikeLaneLinkIdsFilePath: the ids of links that have bike lanes
* r5.linkRadiusMeters: The radius of a circle in meters within which to search for nearby streets
* r5.transitAlternativeList: Determines the way R5 chooses to keep alternative routes listed. OPTIMAL - keeps a route only if there is no other route with the same access and egress modes that is both cheaper and faster; SUBOPTIMAL - keeps all possible routes that are a configurable amount of time slower than the fastest observed route.
* r5.suboptimalMinutes: Used only for transitAlternativeList = "SUBOPTIMAL", configures the amount of time other possible routes can be slower than the fastest one and be kept in the alternative routes list. If the route has the same access mode as the fastest, this parameter determines how many minutes
* r5.route can be slower to be kept; if the route has a different access mode to the fastest, the actual amount of minutes used to decide if it will be kept is 5 times this parameter.
* r5.accessBufferTimeSeconds: How long does it take you to park your vehicle at the station
* gh.useAlternativeRoutes: enables using alternative route algorithm in GH router.
* startingIterationForTravelTimesMSA: Starting from this iteration link travel times of Metropolitan Statistical Area is used.
* overrideNetworkTravelTimesUsingSkims: travel time is got from skims
* minimumPossibleSkimBasedTravelTimeInS: minimum skim based travel time
* skimTravelTimesScalingFactor: used to scale skim based travel time
* writeRoutingStatistic: if set to true writes origin-destination pairs that a route wasn't found between
* agentsim.agents.ptFare.filePath: A file containing public transit fares depending on passenger age.
* agentsim.agents.agents.rideHail.freeSpeedLinkWeightMultiplier: travel time cost multiplier for vehicles with restricted speed when this restricted speed is lower than the free speed on the link.
* agentsim.scenarios.frequencyAdjustmentFile: path to a file with transit trip frequency adjustment.


Charging Network Manager
^^^^^^^^^^^^^^^^^^^^^^^^
::

    beam.agentsim.chargingNetworkManager {
      timeStepInSeconds = 300
      overnightChargingEnabled = false
      chargingPointCountScalingFactor = 1.0
      chargingPointCostScalingFactor = 1.0
      chargingPointFilePath = ""
      scaleUp {
        enabled = false
        expansionFactor = 1.0
        activitiesLocationFilePath = ""
      }
      sitePowerManagerController {
        connect = false
        expectFeedback = true
        numberOfFederates = 1
        brokerAddress = "tcp://127.0.0.1"
        coreType = "zmq"
        timeDeltaProperty = 1.0
        intLogLevel = 1
        beamFederatePrefix = "BEAM_FED"
        beamFederatePublication = "CHARGING_VEHICLES"
        spmFederatePrefix = "SPM_FED"
        spmFederateSubscription = "CHARGING_COMMANDS"
        bufferSize = 10000000
      }
      powerManagerController {
        connect = false
        feedbackEnabled = true
        brokerAddress = "tcp://127.0.0.1"
        coreType = "zmq"
        timeDeltaProperty = 1.0
        intLogLevel = 1
        beamFederateName = "BEAM_FED"
        beamFederatePublication = "LOAD_DEMAND"
        pmcFederateName = "GRID_FED"
        pmcFederateSubscription = "POWER_LIMITS"
        bufferSize = 10000000
      }
    }


Parameters within beam.routing namespace

* timeStepInSeconds: time interval of dispatching energy.
* overnightChargingEnabled: Overnight charging is still a work in progress and might produce unexpected results.
* chargingPointCountScalingFactor: scaling factor for number of charging points (if chargingPointFilePath is defined).
* chargingPointCostScalingFactor: scaling factor for cost of charging points (if chargingPointFilePath is defined).
* chargingPointFilePath: file where charging infrastructure is loaded from.
* scaleUp.enabled: enables scaling up number of charging requests.
* scaleUp.expansionFactor: factor of scaling.
* activitiesLocationFilePath: a csv file with header person_id,ActivityType,x,y,household_id,TAZ that contains all the activities of the current scenario.
* sitePowerManagerController: enables co-simulation of Site Power Controller via Helics library.
* powerManagerController: enables co-simulation of Power Manager Controller via Helics library.

Warm Mode
^^^^^^^^^
::

   ##################################################################
   # Warm Mode
   ##################################################################
   # valid options: disabled, full, linkStatsOnly (only link stats is loaded (all the other data is got from the input directory))
   beam.warmStart.type = "disabled"
   #PATH TYPE OPTIONS: PARENT_RUN, ABSOLUTE_PATH
   #PARENT_RUN: can be a director or zip archive of the output directory (e.g. like what gets stored on S3). We should also be able to specify a URL to an S3 output.
   #ABSOLUTE_PATH: a directory that contains required warm stats files (e.g. linkstats and eventually a plans).
   beam.warmStart.pathType = "PARENT_RUN"
   beam.warmStart.path = "https://s3.us-east-2.amazonaws.com/beam-outputs/run149-base__2018-06-27_20-28-26_2a2e2bd3.zip"
   beam.warmStart.prepareData = false
   beam.warmStart.samplePopulationIntegerFlag = 0
   beam.warmStart.skimsFilePaths = []

* warmStart.enabled: Allows you to point to the output of a previous BEAM run and the network travel times and final plan set from that run will be loaded and used to start a new BEAM run. 
* beam.warmStart.pathType: See above for descriptions.
* beam.warmStart.path: path to the outputs to load. Can we a path on the local computer or a URL in which case outputs will be downloaded.
* beam.warmStart.prepareData: Creates warmstart_data.zip that can be used for warmstart in the next beam runs.
* beam.warmStart.samplePopulationIntegerFlag: If set to 1 then sampling of population happens even in the case of warm start.
* beam.warmStart.skimsFilePaths: For internal use.

Ride hail management
^^^^^^^^^^^^^^^^^^^^
::

  ##################################################################
  # RideHail
  ##################################################################
  # Ride Hailing General Params
  beam.agentsim.agents.rideHail.managers = [{
     name = "GlobalRHM"
     supportedModes = "ride_hail, ride_hail_pooled"
     initialization.initType = "PROCEDURAL" # Other possible values - FILE
     initialization.procedural.vehicleTypePrefix = "RH"
     initialization.procedural.vehicleTypeId = "Car"
     initialization.procedural.fractionOfInitialVehicleFleet = 0.1
     initialization.procedural.initialLocation.name = "HOME"
     initialization.procedural.initialLocation.home.radiusInMeters = 10000
     initialization.procedural.vehicleAdjustmentMethod = ""
     initialization.filePath = ""
     initialization.parking.filePath = ""

     stopFilePath = string
     maximumWalkDistanceToStopInM = 800.0
     defaultCostPerMile=1.25
     defaultCostPerMinute=0.75
     defaultBaseCost = 1.8
     pooledBaseCost = 1.89
     pooledCostPerMile = 1.11
     pooledCostPerMinute = 0.07
     
     rideHailManager.radiusInMeters=5000
     
     # initialLocation(HOME | UNIFORM_RANDOM | ALL_AT_CENTER | ALL_IN_CORNER)
     initialLocation.name="HOME"
     initialLocation.home.radiusInMeters=10000
     
     # allocationManager(DEFAULT_MANAGER | REPOSITIONING_LOW_WAITING_TIMES | EV_MANAGER)
     allocationManager.name = "DEFAULT_MANAGER"
     allocationManager.maxWaitingTimeInSec = 900
     allocationManager.maxExcessRideTime = 0.5 # up to +50%
     allocationManager.requestBufferTimeoutInSeconds = 0
     # ASYNC_GREEDY_VEHICLE_CENTRIC_MATCHING, ALONSO_MORA_MATCHING_WITH_ASYNC_GREEDY_ASSIGNMENT, ALONSO_MORA_MATCHING_WITH_MIP_ASSIGNMENT
     allocationManager.matchingAlgorithm = "ALONSO_MORA_MATCHING_WITH_ASYNC_GREEDY_ASSIGNMENT"
     # ALONSO MORA
     allocationManager.alonsoMora.maxRequestsPerVehicle = 5
     # Reposition
     allocationManager.pooledRideHailIntervalAsMultipleOfSoloRideHail = 1
     
     repositioningManager.name = "DEFAULT_REPOSITIONING_MANAGER"
     repositioningManager.timeout = 0
     # Larger value increase probability of the ride-hail vehicle to reposition
     repositioningManager.demandFollowingRepositioningManager.sensitivityOfRepositioningToDemand = 1
     repositioningManager.demandFollowingRepositioningManager.sensitivityOfRepositioningToDemandForCAVs = 1
     repositioningManager.demandFollowingRepositioningManager.numberOfClustersForDemand = 30
     repositioningManager.demandFollowingRepositioningManager.fractionOfClosestClustersToConsider = 0.2
     repositioningManager.demandFollowingRepositioningManager.horizon = 1200
     # inverse Square Distance Repositioning Factor
     repositioningManager.inverseSquareDistanceRepositioningFactor.sensitivityOfRepositioningToDemand = 0.4
     repositioningManager.inverseSquareDistanceRepositioningFactor.sensitivityOfRepositioningToDistance = 0.9
     repositioningManager.inverseSquareDistanceRepositioningFactor.predictionHorizon = 3600
     # reposition Low Waiting Times
     repositioningManager.repositionLowWaitingTimes.repositionCircleRadiusInMeters = 3000
     repositioningManager.repositionLowWaitingTimes.minimumNumberOfIdlingVehiclesThresholdForRepositioning = 1
     repositioningManager.repositionLowWaitingTimes.repositionCircleRadisInMeters=3000.0
     repositioningManager.repositionLowWaitingTimes.minimumNumberOfIdlingVehiclesThreshholdForRepositioning=1
     repositioningManager.repositionLowWaitingTimes.percentageOfVehiclesToReposition=1.0
     repositioningManager.repositionLowWaitingTimes.timeWindowSizeInSecForDecidingAboutRepositioning=1200
     repositioningManager.repositionLowWaitingTimes.allowIncreasingRadiusIfDemandInRadiusLow=true
     repositioningManager.repositionLowWaitingTimes.minDemandPercentageInRadius=0.1
     # repositioningMethod(TOP_SCORES | KMEANS)
     repositioningManager.repositionLowWaitingTimes.repositioningMethod="TOP_SCORES"
     repositioningManager.repositionLowWaitingTimes.keepMaxTopNScores=5
     repositioningManager.repositionLowWaitingTimes.minScoreThresholdForRepositioning=0.00001
     repositioningManager.repositionLowWaitingTimes.distanceWeight=0.01
     repositioningManager.repositionLowWaitingTimes.waitingTimeWeight=4.0
     repositioningManager.repositionLowWaitingTimes.demandWeight=4.0
     repositioningManager.repositionLowWaitingTimes.produceDebugImages=true
  }]

   beam.agentsim.agents.rideHail.cav.valueOfTime = 1.00
   # when range below refuelRequiredThresholdInMeters, EV Ride Hail CAVs will charge
   # when range above noRefuelThresholdInMeters, EV Ride Hail CAVs will not charge
   # (between these params probability of charging is linear interpolation from 0% to 100%)
   beam.agentsim.agents.rideHail.human.refuelRequiredThresholdInMeters = 32180.0 # 20 miles
   beam.agentsim.agents.rideHail.human.noRefuelThresholdInMeters = 128720.0 # 80 miles
   beam.agentsim.agents.rideHail.cav.refuelRequiredThresholdInMeters = 16090.0 # 10 miles
   beam.agentsim.agents.rideHail.cav.noRefuelThresholdInMeters = 96540.0 # 60 miles
   beam.agentsim.agents.rideHail.rangeBufferForDispatchInMeters = 10000 # do not dispatch vehicles below this range to ensure enough available to get to charger
   beam.agentsim.agents.rideHail.charging.multinomialLogit.params.drivingTimeMultiplier = -0.01666667
   beam.agentsim.agents.rideHail.charging.multinomialLogit.params.queueingTimeMultiplier = -0.01666667
   beam.agentsim.agents.rideHail.charging.multinomialLogit.params.chargingTimeMultiplier = -0.01666667
   beam.agentsim.agents.rideHail.charging.multinomialLogit.params.insufficientRangeMultiplier = -60.0

   beam.agentsim.agents.rideHail.linkFleetStateAcrossIterations = false

   beam.agentsim.agents.rideHail.surgePricing.priceAdjustmentStrategy="KEEP_PRICE_LEVEL_FIXED_AT_ONE"
   beam.agentsim.agents.rideHail.surgePricing.surgeLevelAdaptionStep=0.1
   beam.agentsim.agents.rideHail.surgePricing.minimumSurgeLevel=0.1
   beam.agentsim.agents.rideHail.surgePricing.numberOfCategories = 6

   beam.agentsim.agents.rideHail.iterationStats.timeBinSizeInSec = 3600.0
   beam.agentsim.agents.rideHail.bestResponseType = "MIN_COST"

One can add multiple different RH fleets into the array **beam.agentsim.agents.rideHail.managers** above.

* name: RH manager name. It should be different for each RH config. RH vehicles prefer parking on parking zones with reservedFor parameter equals to this value. A person can be subscribed to a limited set of RH fleets. For Beam scenario one need to put a corresponding attribute (ridehail-service-subscription) to populationAttributes.xml. For Urbansim scenario one need to put attribute (ridehail_service_subscription) to person.csv file. Value of this attribute should contain a comma separated list of RH manager names. If this attribute is not set then the person subscribes to all the RH fleets.
* supportedModes: the list of modes this RH manager supports
* initialization.initType: type of ridehail fleet initialization
* initialization.procedural.vehicleTypePrefix: the vehicle type prefix that indicates ridehail vehicles
* initialization.procedural.vehicleTypeId: default ridehail vehicle type
* initialization.procedural.fractionOfInitialVehicleFleet: Defines the # of ride hailing agents to create, this ration is multiplied by the parameter total number of household vehicles to determine the actual number of drivers to create. Agents begin the simulation located at or near the homes of existing agents, uniformly distributed.
* initialization.procedural.initialLocation.name: the way to set the initial location for ride-hail vehicles (HOME, RANDOM_ACTIVITY, UNIFORM_RANDOM, ALL_AT_CENTER, ALL_IN_CORNER)
* initialization.procedural.initialLocation.home.radiusInMeters: radius within which the initial location is taken
* initialization.procedural.vehicleAdjustmentMethod: determines which vehicle type to use for an initialized ride-hail vehicle. Possible values: UNIFORM, INCOME_BASED, SINGLE_TYPE.
* initialization.filePath: this file is loaded when initialization.initType is "FILE"
* initialization.parking.filePath: parking zones defined for ridehail fleet; it may be empty.
* stopFilePath: an optional file that contains ride-hail stop coordinates. If this file is set then the ride-hail vehicles
  can pick up/drop off passengers only on the given stops. For a small fraction of passengers a wrong ordered sequence
  of events can be produced (i.e. a person can reach the pickup stop after they enter the ride-hail vehicle).
* maximumWalkDistanceToStopInM: it defines the maximum walking distance to/from ride-hail stops.
* defaultCostPerMile: cost per mile for ride hail price calculation for solo riders.
* defaultCostPerMinute: cost per minute for ride hail price calculation for solo riders.
* defaultBaseCost: base RH cost for solo riders
* pooledBaseCost: base RH cost for pooled riders
* pooledCostPerMile: cost per mile for ride hail price calculation for pooled riders.
* pooledCostPerMinute: cost per minute for ride hail price calculation for pooled riders.
* radiusInMeters: used during vehicle allocation: considered vehicles that are not further from the request location
  than this value
* allocationManager.name: RideHail resource allocation manager: DEFAULT_MANAGER, POOLING, POOLING_ALONSO_MORA
* allocationManager.maxWaitingTimeInSec: max waiting time for a person during RH allocation
* allocationManager.maxExcessRideTime: max excess ride time fraction
* allocationManager.requestBufferTimeoutInSeconds: ride hail requests are buffered within this time before go to allocation manager
* allocationManager.matchingAlgorithm: matching algorithm
* allocationManager.alonsoMora.maxRequestsPerVehicle: the maximum number of requests that can be considered for a single vehicle
* allocationManager.pooledRideHailIntervalAsMultipleOfSoloRideHail:
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
* repositioningManager.repositionLowWaitingTimes.repositionCircleRadiusInMeters:
* repositioningManager.repositionLowWaitingTimes.minimumNumberOfIdlingVehiclesThresholdForRepositioning:
* repositioningManager.repositionLowWaitingTimes.repositionCircleRadisInMeters:
* repositioningManager.repositionLowWaitingTimes.minimumNumberOfIdlingVehiclesThreshholdForRepositioning:
* repositioningManager.repositionLowWaitingTimes.percentageOfVehiclesToReposition:
* repositioningManager.repositionLowWaitingTimes.timeWindowSizeInSecForDecidingAboutRepositioning:
* repositioningManager.repositionLowWaitingTimes.allowIncreasingRadiusIfDemandInRadiusLow:
* repositioningManager.repositionLowWaitingTimes.minDemandPercentageInRadius:
* repositioningManager.repositionLowWaitingTimes.repositioningMethod:
* repositioningManager.repositionLowWaitingTimes.keepMaxTopNScores:
* repositioningManager.repositionLowWaitingTimes.minScoreThresholdForRepositioning:
* repositioningManager.repositionLowWaitingTimes.distanceWeight:
* repositioningManager.repositionLowWaitingTimes.waitingTimeWeight:
* repositioningManager.repositionLowWaitingTimes.demandWeight:
* repositioningManager.repositionLowWaitingTimes.produceDebugImages:


* surgePricing.priceAdjustmentStrategy: defines different price adjustment strategies
* surgePricing.surgeLevelAdaptionStep:
* surgePricing.minimumSurgeLevel:
* surgePricing.numberOfCategories:
* linkFleetStateAcrossIterations: if it is set to true then in the next iteration ride-hail fleet state of charge is initialized with the value from the end of previous iteration
* surgePricing.priceAdjustmentStrategy: defines different price adjustment strategies. Possible options: `KEEP_PRICE_LEVEL_FIXED_AT_ONE` keeps price level at 1.0; `CONTINUES_DEMAND_SUPPLY_MATCHING` with 50% of probability increases and 50% of probability decreases price level on `surgeLevelAdaptionStep` for each time bin and TAZ
* surgePricing.surgeLevelAdaptionStep: value to be randomly added or removed from the price leve in case of  `CONTINUES_DEMAND_SUPPLY_MATCHING` strategy.
* surgePricing.minimumSurgeLevel: the min price level.
* surgePricing.numberOfCategories: number of price categories. These categories are used in the output of price statistic.
* cav.valueOfTime: is used when searching a parking stall for CAVs
* human.refuelRequiredThresholdInMeters: when range below this value, ride-hail vehicle driven by a human will charge
* human.noRefuelThresholdInMeters: when range above noRefuelThresholdInMeters, ride-hail vehicle driven by a human will not charge
* cav.refuelRequiredThresholdInMeters: when range below this value, EV ride-hail CAVs will charge
* cav.noRefuelThresholdInMeters: when range above noRefuelThresholdInMeters, EV ride-hail CAVs will not charge
* rangeBufferForDispatchInMeters: do not dispatch vehicles below this range to ensure enough available to get to charger minute penalty if out of range
* charging.multinomialLogit.params.drivingTimeMultiplier - one minute of driving is one util
* charging.multinomialLogit.params.queueingTimeMultiplier - one minute of queueing is one util
* charging.multinomialLogit.params.chargingTimeMultiplier - one minute of charging is one util
* charging.multinomialLogit.params.insufficientRangeMultiplier - indicator variable so straight 60 minute penalty if out of range
* iterationStats.timeBinSizeInSec: time bin size of ride-hail statistic
* bestResponseType: How to choose the best proposal from proposals of ride-hail trips. Options are MIN_COST (by min cost of the trip), MIN_UTILITY (by min utility. Considered cost and customer subscription)

Secondary activities generation
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
::

    beam.agentsim.agents.tripBehaviors.multinomialLogit.generate_secondary_activities = true
    beam.agentsim.agents.tripBehaviors.multinomialLogit.intercept_file_path = ${beam.inputDirectory}"/activity-intercepts.csv"
    beam.agentsim.agents.tripBehaviors.multinomialLogit.activity_file_path = ${beam.inputDirectory}"/activity-params.csv"
    beam.agentsim.agents.tripBehaviors.multinomialLogit.additional_trip_utility = 0.0
    beam.agentsim.agents.tripBehaviors.multinomialLogit.max_destination_distance_meters = 16000
    beam.agentsim.agents.tripBehaviors.multinomialLogit.max_destination_choice_set_size = 6
    beam.agentsim.agents.tripBehaviors.multinomialLogit.destination_nest_scale_factor = 1.0
    beam.agentsim.agents.tripBehaviors.multinomialLogit.mode_nest_scale_factor = 1.0
    beam.agentsim.agents.tripBehaviors.multinomialLogit.trip_nest_scale_factor = 1.0

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
^^^^^^^^^^^^^^^^^^^^^
::

    beam.agentsim.agents.activities.activityTypeToFixedDurationMap = ["<activity type> -> <duration>"]
    beam.agentsim.agents.modeIncentive.filePath = ""

*
    beam.agentsim.agents.activities.activityTypeToFixedDurationMap - by default is empty. For specified activities the duration will be fixed.
    The durations of the rest activities will be calculated based on activity end time.
*   modeIncentive.filePath - path to a file containing incentives (cost decrease) for certain Modes depending on person age and income.

Replanning
^^^^^^^^^^
::

    beam.replanning.maxAgentPlanMemorySize = "int | 5"
    beam.replanning.Module_2 = "ClearRoutes"
    beam.replanning.ModuleProbability_2 = 0.1
    beam.replanning.clearModes.modes = []
    beam.replanning.clearModes.iteration = 0
    beam.replanning.clearModes.strategy = "AtBeginningOfIteration"

This section controls process of merging the person plans from the previos beam run to the current run (see `lastBaseOutputDir`).

* maxAgentPlanMemorySize - max number of plans to keep for a particular person.
* Module_2, ModuleProbability_2 - if `Module_2` set to "ClearRoutes" then fraction `ModuleProbability_2` of routes saved in the route history are cleared.
* clearModes.modes - The list of modes to be cleared
* clearModes.iteration - The iteration number (zero-based) when the modes are cleared.
* clearModes.strategy - options: AtBeginningOfIteration, AtBeginningAndAllSubsequentIterations. Clear mode strategy.

Calibration
^^^^^^^^^^^
::

    beam.calibration.mode.benchmarkFilePath = ""
    beam.calibration.roadNetwork.travelTimes.zoneBoundariesFilePath = ""
    beam.calibration.roadNetwork.travelTimes.zoneODTravelTimesFilePath = ""

    beam.calibration.google.travelTimes.enable = false
    beam.calibration.google.travelTimes.numDataPointsOver24Hours = 100
    beam.calibration.google.travelTimes.minDistanceInMeters = 5000
    beam.calibration.google.travelTimes.iterationInterval = 5
    beam.calibration.google.travelTimes.tolls = true
    beam.calibration.google.travelTimes.queryDate = "2020-10-14"
    beam.calibration.google.travelTimes.offPeakEnabled = false

    beam.calibration.studyArea.enabled = false
    beam.calibration.studyArea.lat = 0
    beam.calibration.studyArea.lon = 0
    beam.calibration.studyArea.radius = 0

* mode.benchmarkFilePath: path to a csv file containing all beam mode shares. It allows to build analysis graphs that compares current run data with the benchmark data.
* roadNetwork.travelTimes.zoneBoundariesFilePath: path to a geojson file that contains census tract data.
* roadNetwork.travelTimes.zoneODTravelTimesFilePath: path to a csv file that contains census travel time data. These 2 files are needed if we want to build travel time graphs (scatterplot_simulation_vs_reference.png and simulation_vs_reference_histogram.png).
* google.travelTimes.enable: enables gathering google map estimated travel time for particular origin/destination/vehicle type/departure time and saving the simulation data along with the google data to googleTravelTimeEstimation.csv file.
* google.travelTimes.numDataPointsOver24Hours: the number of simulated car path-traversal which are used to get google statistic.
* google.travelTimes.minDistanceInMeters: only path-traversal events with travel length greater or equal to this value are used.
* google.travelTimes.iterationInterval: google statistic is gathered only on the iterations with this interval.
* google.travelTimes.tolls: if set to false then google statistic contains only paths that avoids tolls.
* google.travelTimes.queryDate: date of the statistic.
* google.travelTimes.offPeakEnabled: if true then departure time is always set to 3AM.
* studyArea.enabled: enables writing car travel time data for a studied area.
* studyArea.lat: latitude of the center of the studied area.
* studyArea.lon: longitude of the center of the studied area.
* studyArea.radius: radius of the studied area.


Output
^^^^^^^^^
::

    # this will write out plans and throw and exception at the beginning of simulation
    beam.output.writePlansAndStopSimulation = false

*
    beam.output.writePlansAndStopSimulation - if set to true will write plans into 'generatedPlans.csv.gz'
    and stop simulation with exception at the beginning of agentSim iteration.
    The functionality was created to generate full population plans with secondary activities for full unscaled input.

Simulation metric
^^^^^^^^^^^^^^^^^
::

    beam.sim.metric.collector.influxDbSimulationMetricCollector.database = "beam"
    beam.sim.metric.collector.influxDbSimulationMetricCollector.connectionString = "http://localhost:8086"
    beam.sim.metric.collector.metrics = "beam-run, beam-iteration"

* influxDbSimulationMetricCollector.database - Influx database name.
* influxDbSimulationMetricCollector.connectionString - Influx database connection string.
* metrics - type of metric to be written. Possible values: rh-ev-cav-count, rh-ev-cav-distance, rh-ev-nocav-count, rh-ev-nocav-distance, rh-noev-cav-count, rh-noev-cav-distance, rh-noev-nocav-count, rh-noev-nocav-distance, beam-run, beam-iteration, mode-choices, ride-hail-trip-distance, ride-hail-waiting-time, average-travel-time, ride-hail-inquiry-served, ride-hail-inquiry-not-available, ride-hail-allocation-reserved, ride-hail-allocation-failed, beam-run-RH-ev-cav, beam-run-RH-ev-non-cav, beam-run-RH-non-ev-cav,  beam-run-RH-non-ev-non-cav, ride-hail-waiting-time-map, beam-run-public-fast-charge-cnt, beam-run-public-fast-charge-stalls-cnt, beam-run-charging-depots-stalls-cnt, beam-run-charging-depots-cnt, beam-run-private-fleet-size, beam-run-households, beam-run-population-size

Defining what data BEAM writes out
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

There's the list of parameters responsible for writing out data produced by BEAM.

::

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
    beam.outputs.generalizedLinkStats.startTime = 25200
    beam.outputs.generalizedLinkStats.endTime = 32400
    beam.outputs.collectAndCreateBeamAnalysisAndGraphs = true
    beam.outputs.displayPerformanceTimings = false
    beam.outputs.defaultWriteInterval = 1
    beam.outputs.events.eventsToWrite = "ActivityEndEvent,ActivityStartEvent,PersonEntersVehicleEvent,PersonLeavesVehicleEvent,ModeChoiceEvent,PathTraversalEvent,ReserveRideHailEvent,ReplanningEvent,RefuelSessionEvent,ChargingPlugInEvent,ChargingPlugOutEvent,ParkingEvent,LeavingParkingEvent"
    beam.outputs.events.fileOutputFormats = "csv"
    beam.outputs.matsim.deleteITERSFolderFiles = ""
    beam.outputs.matsim.deleteRootFolderFiles = ""
    beam.outputs.stats.binSize = 3600
    # Skims configuration
    beam.router.skim = {
      keepKLatestSkims = 1
      writeSkimsInterval = 0
      writeAggregatedSkimsInterval = 0
      activity-sim-skimmer {
        name = "activity-sim-skimmer"
        fileBaseName = "activitySimODSkims"
        fileOutputFormat = "csv"
      }
      drive-time-skimmer {
        name = "drive-time-skimmer"
        fileBaseName = "skimsTravelTimeObservedVsSimulated"
      }
      origin-destination-skimmer {
        name = "od-skimmer"
        fileBaseName = "skimsOD"
        writeAllModeSkimsForPeakNonPeakPeriodsInterval = 0
        writeFullSkimsInterval = 0
        poolingTravelTimeOveheadFactor = 1.21
      }
      taz-skimmer {
        name = "taz-skimmer"
        fileBaseName = "skimsTAZ"
        geoHierarchy = "TAZ"
      }
      transit-crowding-skimmer {
        name = "transit-crowding-skimmer"
        fileBaseName = "skimsTransitCrowding"
      }
    }
    beam.metrics.level = "verbose"
    beam.exchange.output.activitySimSkimsEnabled = false
    beam.exchange.output.sendNonChosenTripsToSkimmer = true
    beam.exchange.output.geo.filePath = string

All integer values that end with 'Interval' mean writing data files at iteration which number % value = 0. In case value = 0
writing is disabled.

* outputs.writeGraphs: enable writing activity locations to #.activityLocations.png
* outputs.writePlansInterval: enable writing plans of persons at the iteration to #.plans.csv.gz
* outputs.writeEventsInterval: enable writing AgentSim events to #.events.csv.gz
* outputs.writeAnalysis: enable analysis with python script analyze_events.py and writing different data files
* outputs.writeR5RoutesInterval: enable writing routing requests/responses to files #.routingRequest.parquet, #.routingResponse.parquet, #.embodyWithCurrentTravelTime.parquet
* physsim.writeEventsInterval: enable writing physsim events to #.physSimEvents.csv.gz
* physsim.events.fileOutputFormats: file format for physsim event file; valid options: xml(.gz) , csv(.gz), none - DEFAULT: csv.gz
* physsim.events.eventsToWrite: types of physsim events to write
* physsim.writePlansInterval: enable writing of physsim plans to #.physsimPlans.xml.gz
* physsim.writeRouteHistoryInterval: enable writing route history to #.routeHistory.csv.gz. It contains timeBin,originLinkId,destLinkId,route (link ids)
* physsim.linkStatsWriteInterval: enable writing link statistic to #.linkstats_unmodified.csv.gz"
* outputs.generalizedLinkStatsInterval: enable writing generalized link statistic (with generalized time and cost) to #.generalizedLinkStats.csv.gz
* outputs.generalizedLinkStats.startTime, endTime: write link statistic only within this time interval
* outputs.collectAndCreateBeamAnalysisAndGraphs: if true various beam analysis csv files and graphs are generated.
* outputs.displayPerformanceTimings: enables writing some internal Scheduler and Routing statistic.
* outputs.defaultWriteInterval: the default iteration interval that is used in the output.
* outputs.events.eventsToWrite: the list of events that need to be written in events.csv file.
* outputs.events.fileOutputFormats: event file output format. Valid options: xml(.gz) , csv(.gz), none.
* outputs.matsim.deleteITERSFolderFiles: comma separated list of matsim iteration output files to be deleted before beam shutdown.
* outputs.matsim.deleteRootFolderFiles: comma separated list of matsim root output files to be deleted before beam shutdown.
* outputs.stats.binSize: bin size for various histograms.
* router.skim.keepKLatestSkims: How many skim data iterations to keep
* router.skim.writeSkimsInterval: enable writing all skim data for a particular iteration to corresponding files
* router.skim.writeAggregatedSkimsInterval: enable writing all aggregated skim data (for all iterations) to corresponding files
* router.skim.activity-sim-skimmer.name: ActivitySim skimmer event name
* router.skim.activity-sim-skimmer.fileBaseName: ActivitySim skims base file name
* router.skim.activity-sim-skimmer.fileOutputFormat: ActivitySim skims file format: "csv" or "omx"
* router.skim.drive-time-skimmer.name: drive time skimmer event name
* router.skim.drive-time-skimmer.fileBaseName: drive time skims base file name
* router.skim.origin-destination-skimmer.name: origin-destination skimmer event name
* router.skim.origin-destination-skimmer.fileBaseName: origin-destination skims base file name
* router.skim.origin-destination-skimmer.writeAllModeSkimsForPeakNonPeakPeriodsInterval: enable writing ODSkims for peak and non-peak time periods to #.skimsODExcerpt.csv.gz
* router.skim.origin-destination-skimmer.writeFullSkimsInterval: enable writing ODSkims for all TAZes presented in the scenario to #.skimsODFull.csv.gz
* router.skim.origin-destination-skimmer.poolingTravelTimeOveheadFactor: ride-hail pooling trip travel time overhead factor comparing to a solo trip
* router.skim.taz-skimmer.name: TAZ skimmer event name
* router.skim.taz-skimmer.fileBaseName: TAZ skims base file name
* router.skim.taz-skimmer.geoHierarchy: GEO unit that is used in TAZ skimmer (TAZ or H3)
* router.skim.transit-crowding-skimmer.name: transit crowding skimmer event name
* router.skim.transit-crowding-skimmer.fileBaseName: transit crowding skims base file name
* metrics.level: the level of beam metrics. Possible values: off, short, regular, verbose
* beam.exchange.output.activitySimSkimsEnabled: enables writing out skims in activity sim format (ActivitySim skims). See `router.skim.activity-sim-skimmer` params.
* beam.exchange.output.sendNonChosenTripsToSkimmer: enables saving not chosen trip data to origin-destination and ActivitySim skims.
* beam.exchange.output.geo.filePath: path to a file in beam TAZ format that contains centroids of geo unit different than the scenario units. If defined the ActivitySim skims are written using these geo units.

Termination criterion name options
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

::

  beam.sim.termination.criterionName = "TerminateAtFixedIterationNumber"
  beam.sim.termination.terminateAtRideHailFleetStoredElectricityConvergence.minLastIteration = 0
  beam.sim.termination.terminateAtRideHailFleetStoredElectricityConvergence.maxLastIteration = 0
  beam.sim.termination.terminateAtRideHailFleetStoredElectricityConvergence.relativeTolerance = 0.01

* criterionName: Possible values: `TerminateAtFixedIterationNumber`, `TerminateAtRideHailFleetStoredElectricityConvergence`.
  `TerminateAtFixedIterationNumber` terminates the simulation when iteration number lastIteration finishes.
  `TerminateAtRideHailFleetStoredElectricityConvergence` terminates it when the ride-hail fleet stored electricity converges (total stored electricity of the ride-hail fleet at the beginning of the iteration equals to it at the end of the iteration).
* terminateAtRideHailFleetStoredElectricityConvergence.minLastIteration: the min number of iteration when this criterion can terminate the simulation.
* terminateAtRideHailFleetStoredElectricityConvergence.maxLastIteration: the max number of last iteration allowed.
* terminateAtRideHailFleetStoredElectricityConvergence.relativeTolerance: allowed max relative difference (difference of total stored electricity relative to total capacity).

Debug configuration
^^^^^^^^^^^^^^^^^^^
::

    beam.debug {
      debugEnabled = false
      agentTripScoresInterval = 0

      triggerMeasurer {
        enabled = false
        writeStuckAgentDetectionConfig = true
      }

      stuckAgentDetection {
        enabled = false
        checkIntervalMs = "200ms"
        defaultTimeoutMs = "60s"
        overallSimulationTimeoutMs = "100s"
        checkMaxNumberOfMessagesEnabled = true
        thresholds = [
          # Possible values (with deviations from default):
          # triggerType = "beam.agentsim.agents.PersonAgent$ActivityStartTrigger" && markAsStuckAfterMs = 20s
          # triggerType = "beam.agentsim.agents.PersonAgent$ActivityEndTrigger" && markAsStuckAfterMs = 60s
          # triggerType = "beam.agentsim.agents.PersonAgent$PersonDepartureTrigger"
          # triggerType = "beam.agentsim.agents.modalbehaviors.DrivesVehicle$StartLegTrigger" && markAsStuckAfterMs = 18s
          # triggerType = "beam.agentsim.agents.modalbehaviors.DrivesVehicle$EndLegTrigger" && markAsStuckAfterMs = 60s
          # triggerType = "beam.agentsim.agents.modalbehaviors.DrivesVehicle$AlightVehicleTrigger" && markAsStuckAfterMs = 21s
          # triggerType = "beam.agentsim.agents.modalbehaviors.DrivesVehicle$BoardVehicleTrigger" && markAsStuckAfterMs = 21s
          # triggerType = "beam.agentsim.agents.modalbehaviors.DrivesVehicle$StartRefuelSessionTrigger" && markAsStuckAfterMs = 21s
          # riggerType = "beam.agentsim.agents.modalbehaviors.DrivesVehicle$EndRefuelSessionTrigger" && markAsStuckAfterMs = 21s
          # triggerType = "beam.agentsim.agents.InitializeTrigger"
          # triggerType = "beam.agentsim.agents.ridehail.RideHailManager$BufferedRideHailRequestsTimeout"
          # triggerType = "beam.agentsim.agents.ridehail.RideHailManager$RideHailAllocationManagerTimeout" && markAsStuckAfterMs = 40s
          {
            triggerType = "beam.agentsim.agents.PersonAgent$ActivityStartTrigger"
            markAsStuckAfterMs = "20s"
            actorTypeToMaxNumberOfMessages {
              population = 10
              transitDriverAgent = 0
              rideHailAgent = 0
              rideHailManager = 0
            }
          }
        ]
      }

      debugActorTimerIntervalInSec = 0
      actor.logDepth = 0
      memoryConsumptionDisplayTimeoutInSec = 0
      clearRoutedOutstandingWorkEnabled = false
      secondsToWaitToClearRoutedOutstandingWork = 60

      vmInformation.createGCClassHistogram = false
      # it implies vmInformation.createGCClassHistogram = true
      vmInformation.writeHeapDump = false
      writeModeChoiceAlternatives = false

      writeRealizedModeChoiceFile = false
      messageLogging = false
      # the max of the next 2 values is taken for the initialization step
      maxSimulationStepTimeBeforeConsideredStuckMin = 60
      maxSimulationStepTimeBeforeConsideredStuckAtInitializationMin = 600
    }

* debugEnabled - enables debug features.
* agentTripScoresInterval - iteration interval of writing person trips score to #.agentTripScores.csv.gz file.
* triggerMeasurer.enabled - enables the scheduler to measure trigger resolution statistic. It's written to the log file at the end of each iteration.
* triggerMeasurer.writeStuckAgentDetectionConfig - enables writing stuck agent detection config at the end of each iteration.
* stuckAgentDetection.enabled - enables stuck agent detection.
* stuckAgentDetection.checkIntervalMs - how often stuck detection happens.
* stuckAgentDetection.defaultTimeoutMs - timeout for triggers that are not configured in `thresholds` section.
* stuckAgentDetection.overallSimulationTimeoutMs - timeout for the whole simulation. If no progress for this time interval the simulation is considered got stuck and this event is logged out.
* stuckAgentDetection.checkMaxNumberOfMessagesEnabled - enables the scheduler to check that an agent doesn't exceed the configured number of messages of certain type. If it happens a warning is logged out.
* stuckAgentDetection.thresholds - contains entries for each trigger type. `markAsStuckAfterMs` timeout for this trigger. `actorTypeToMaxNumberOfMessages` max number of this triggers for a particular actor type.
* debugActorTimerIntervalInSec - if it's greater than zero then each this interval a state of the scheduler is printed out.
* actor.logDepth - the number of actor messages to keep. In case of an actor failure these messages are printed out.
* memoryConsumptionDisplayTimeoutInSec - not used.
* clearRoutedOutstandingWorkEnabled - In case of remote routing workers it enables clearing a routing work in case of a configured timeout exceeded.
* secondsToWaitToClearRoutedOutstandingWork - timeout for clearing remote routing work.
* vmInformation.createGCClassHistogram - enables creating a memory statistic histogram with base name "vmNumberOfMBytesOfClassOnHeap".
* vmInformation.writeHeapDump - enables writing a heap dump to heapDump.$simulation-name.hprof at the end of each iteration.
* writeModeChoiceAlternatives - enables writing mode choice data to #.modeChoiceDetailed.csv.gz file.
* writeRealizedModeChoiceFile - enables writing realized mode choice data to #.realizedModeChoice.csv.gz file.
* messageLogging - enables writing all the actor messages and transitions to #.actor_messages.#.csv.gz files.
* maxSimulationStepTimeBeforeConsideredStuckMin - timeout in minutes for a single tick of simulation. If simulation time doesn't change within this wall time interval the simulation is considered got stuck and Beam exits.
* maxSimulationStepTimeBeforeConsideredStuckAtInitializationMin - timeout in minutes for a single tick of simulation at the initialization phase. If simulation time doesn't change within this wall time interval the simulation is considered got stuck and Beam exits.

Technical parameters
^^^^^^^^^^^^^^^^^^^^
::

    beam.cluster.enabled = false
    beam.actorSystemName = ClusterSystem
    beam.useLocalWorker = true

* cluster.enabled - enables cluster for routing work.
* actorSystemName - name of the akka actor system.
* useLocalWorker - enables local worker for routing work.

Matsim parameters
^^^^^^^^^^^^^^^^^^^^
::


    beam.replanning.Module_1
    beam.replanning.ModuleProbability_1
    beam.replanning.Module_3
    beam.replanning.ModuleProbability_3
    beam.replanning.Module_4
    beam.replanning.ModuleProbability_4
    beam.replanning.fractionOfIterationsToDisableInnovation
    beam.calibration.counts.countsScaleFactor
    beam.calibration.counts.writeCountsInterval
    beam.calibration.counts.averageCountsOverIterations
    beam.calibration.counts.inputCountsFile

These parameter values go to the corresponding Matsim module configuration parameters.

* beam.calibration.counts.inputCountsFile - must not be an empty string. If you don't need this parameter then delete it completely.

Parameters that are not supported anymore
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
::

    beam.agentsim.firstIteration
    beam.debug.memoryConsumptionDisplayTimeoutInSec
    beam.cluster.clusterType
    beam.calibration.objectiveFunction
    beam.calibration.meanToCountsWeightRatio
    beam.experimental.optimizer.enabled
    beam.physsim.initializeRouterWithFreeFlowTimes
    beam.physsim.jdeqsim.cacc.adjustedMinimumRoadSpeedInMetersPerSecond
    beam.physsim.relaxation.type
    beam.physsim.relaxation.experiment2_0.internalNumberOfIterations
    beam.physsim.relaxation.experiment2_0.fractionOfPopulationToReroute
    beam.physsim.relaxation.experiment2_0.clearRoutesEveryIteration
    beam.physsim.relaxation.experiment2_0.clearModesEveryIteration
    beam.physsim.relaxation.experiment2_1.internalNumberOfIterations
    beam.physsim.relaxation.experiment2_1.fractionOfPopulationToReroute
    beam.physsim.relaxation.experiment2_1.clearRoutesEveryIteration
    beam.physsim.relaxation.experiment2_1.clearModesEveryIteration
    beam.physsim.relaxation.experiment3_0.internalNumberOfIterations
    beam.physsim.relaxation.experiment3_0.fractionOfPopulationToReroute
    beam.physsim.relaxation.experiment4_0.percentToSimulate
    beam.physsim.relaxation.experiment5_0.percentToSimulate
    beam.physsim.relaxation.experiment5_1.percentToSimulate
    beam.physsim.relaxation.experiment5_2.percentToSimulate
    beam.urbansim.backgroundODSkimsCreator.*

