
.. _model-outputs:

Model Outputs
=============


ModeChosenAnalysisObject
------------------------
+--------------------------------------------+-------------------+--------------------------+---------------------------+
| description                                | field             | outputFile               | className                 |
+============================================+===================+==========================+===========================+
| iteration number                           | iterations        | /modeChoice.csv          | ModeChosenAnalysisObject$ |
+--------------------------------------------+-------------------+--------------------------+---------------------------+
| Car chosen as travel mode                  | car               | /modeChoice.csv          | ModeChosenAnalysisObject$ |
+--------------------------------------------+-------------------+--------------------------+---------------------------+
| Drive to transit chosen as travel mode     | drive_transit     | /modeChoice.csv          | ModeChosenAnalysisObject$ |
+--------------------------------------------+-------------------+--------------------------+---------------------------+
| Ride Hail chosen as travel mode            | ride_hail         | /modeChoice.csv          | ModeChosenAnalysisObject$ |
+--------------------------------------------+-------------------+--------------------------+---------------------------+
| Walk chosen as travel mode                 | walk              | /modeChoice.csv          | ModeChosenAnalysisObject$ |
+--------------------------------------------+-------------------+--------------------------+---------------------------+
| Walk to transit chosen as travel mode      | walk_transit      | /modeChoice.csv          | ModeChosenAnalysisObject$ |
+--------------------------------------------+-------------------+--------------------------+---------------------------+
| Bike chosen as travel mode                 | iterations        | /referenceModeChoice.csv | ModeChosenAnalysisObject$ |
+--------------------------------------------+-------------------+--------------------------+---------------------------+
| iteration number                           | bike              | /referenceModeChoice.csv | ModeChosenAnalysisObject$ |
+--------------------------------------------+-------------------+--------------------------+---------------------------+
| Car chosen as travel mode                  | car               | /referenceModeChoice.csv | ModeChosenAnalysisObject$ |
+--------------------------------------------+-------------------+--------------------------+---------------------------+
| Drive to transit chosen as travel mode     | drive_transit     | /referenceModeChoice.csv | ModeChosenAnalysisObject$ |
+--------------------------------------------+-------------------+--------------------------+---------------------------+
| Ride Hail chosen as travel mode            | ride_hail         | /referenceModeChoice.csv | ModeChosenAnalysisObject$ |
+--------------------------------------------+-------------------+--------------------------+---------------------------+
| Ride Hail to transit chosen as travel mode | ride_hail_transit | /referenceModeChoice.csv | ModeChosenAnalysisObject$ |
+--------------------------------------------+-------------------+--------------------------+---------------------------+
| Walk chosen as travel mode                 | walk              | /referenceModeChoice.csv | ModeChosenAnalysisObject$ |
+--------------------------------------------+-------------------+--------------------------+---------------------------+
| Walk to transit chosen as travel mode      | walk_transit      | /referenceModeChoice.csv | ModeChosenAnalysisObject$ |
+--------------------------------------------+-------------------+--------------------------+---------------------------+

RealizedModeAnalysisObject
--------------------------
+----------------------------------------+---------------+-------------------+-----------------------------+
| description                            | field         | outputFile        | className                   |
+========================================+===============+===================+=============================+
| Car chosen as travel mode              | car           | /realizedMode.csv | RealizedModeAnalysisObject$ |
+----------------------------------------+---------------+-------------------+-----------------------------+
| Drive to transit chosen as travel mode | drive_transit | /realizedMode.csv | RealizedModeAnalysisObject$ |
+----------------------------------------+---------------+-------------------+-----------------------------+
| Other modes of travel chosen           | other         | /realizedMode.csv | RealizedModeAnalysisObject$ |
+----------------------------------------+---------------+-------------------+-----------------------------+
| Ride Hail chosen as travel mode        | ride_hail     | /realizedMode.csv | RealizedModeAnalysisObject$ |
+----------------------------------------+---------------+-------------------+-----------------------------+
| Walk chosen as travel mode             | walk          | /realizedMode.csv | RealizedModeAnalysisObject$ |
+----------------------------------------+---------------+-------------------+-----------------------------+
| Walk to transit chosen as travel mode  | walk_transit  | /realizedMode.csv | RealizedModeAnalysisObject$ |
+----------------------------------------+---------------+-------------------+-----------------------------+

RideHailRevenueAnalysisObject
-----------------------------
+----------------------------------+-------------+----------------------+--------------------------------+
| description                      | field       | outputFile           | className                      |
+==================================+=============+======================+================================+
| iteration number                 | iteration # | /rideHailRevenue.csv | RideHailRevenueAnalysisObject$ |
+----------------------------------+-------------+----------------------+--------------------------------+
| Revenue generated from ride hail | revenue     | /rideHailRevenue.csv | RideHailRevenueAnalysisObject$ |
+----------------------------------+-------------+----------------------+--------------------------------+

PersonTravelTimeAnalysisObject
------------------------------
+----------------------------------------------------------------------------------+--------+--------------------------------------+---------------------------------+
| description                                                                      | field  | outputFile                           | className                       |
+==================================================================================+========+======================================+=================================+
| Travel mode chosen                                                               | Mode   | /ITERS/it.0/0.averageTravelTimes.csv | PersonTravelTimeAnalysisObject$ |
+----------------------------------------------------------------------------------+--------+--------------------------------------+---------------------------------+
| Average time taken to travel by the chosen mode during the given hour of the day | Hour,* | /ITERS/it.0/0.averageTravelTimes.csv | PersonTravelTimeAnalysisObject$ |
+----------------------------------------------------------------------------------+--------+--------------------------------------+---------------------------------+

FuelUsageAnalysisObject
-----------------------
+----------------------------------------------------------------------------------------------+-------+---------------------------------+--------------------------+
| description                                                                                  | field | outputFile                      | className                |
+==============================================================================================+=======+=================================+==========================+
| Mode of travel chosen by the passenger                                                       | Modes | /ITERS/it.0/0.energyUse.png.csv | FuelUsageAnalysisObject$ |
+----------------------------------------------------------------------------------------------+-------+---------------------------------+--------------------------+
| Energy consumed by the vehicle while travelling by the chosen mode within the given time bin | Bin_* | /ITERS/it.0/0.energyUse.png.csv | FuelUsageAnalysisObject$ |
+----------------------------------------------------------------------------------------------+-------+---------------------------------+--------------------------+

PhyssimCalcLinkSpeedStatsObject
-------------------------------
+----------------------------------------------------------------------------------------------+------------------+-----------------------------------------------------+----------------------------------+
| description                                                                                  | field            | outputFile                                          | className                        |
+==============================================================================================+==================+=====================================================+==================================+
| A given time slot within a day                                                               | Bin              | /ITERS/it.0/0.physsimLinkAverageSpeedPercentage.csv | PhyssimCalcLinkSpeedStatsObject$ |
+----------------------------------------------------------------------------------------------+------------------+-----------------------------------------------------+----------------------------------+
| The average speed at which a vehicle can travel across the network during the given time bin | AverageLinkSpeed | /ITERS/it.0/0.physsimLinkAverageSpeedPercentage.csv | PhyssimCalcLinkSpeedStatsObject$ |
+----------------------------------------------------------------------------------------------+------------------+-----------------------------------------------------+----------------------------------+

PhyssimCalcLinkSpeedDistributionStatsObject
-------------------------------------------
+-----------------------------------------------------------------------------------------------------------+----------------------------+----------------------------------------------------+----------------------------------------------+
| description                                                                                               | field                      | outputFile                                         | className                                    |
+===========================================================================================================+============================+====================================================+==============================================+
| The possible full speed at which a vehicle can drive through the given link (in m/s)                      | freeSpeedInMetersPerSecond | /ITERS/it.0/0.physsimFreeFlowSpeedDistribution.csv | PhyssimCalcLinkSpeedDistributionStatsObject$ |
+-----------------------------------------------------------------------------------------------------------+----------------------------+----------------------------------------------------+----------------------------------------------+
| Total number of links in the network that allow vehicles to travel with speeds up to the given free speed | numberOfLinks              | /ITERS/it.0/0.physsimFreeFlowSpeedDistribution.csv | PhyssimCalcLinkSpeedDistributionStatsObject$ |
+-----------------------------------------------------------------------------------------------------------+----------------------------+----------------------------------------------------+----------------------------------------------+
| Average speed efficiency recorded by the the given network link in a day                                  | linkEfficiencyInPercentage | /ITERS/it.0/0.physsimFreeFlowSpeedDistribution.csv | PhyssimCalcLinkSpeedDistributionStatsObject$ |
+-----------------------------------------------------------------------------------------------------------+----------------------------+----------------------------------------------------+----------------------------------------------+
| Total number of links having the corresponding link efficiency                                            | numberOfLinks              | /ITERS/it.0/0.physsimFreeFlowSpeedDistribution.csv | PhyssimCalcLinkSpeedDistributionStatsObject$ |
+-----------------------------------------------------------------------------------------------------------+----------------------------+----------------------------------------------------+----------------------------------------------+

RideHailWaitingAnalysisObject
-----------------------------
+---------------------------------------------------------------------------------------------+----------------------+--------------------------------------------------+--------------------------------+
| description                                                                                 | field                | outputFile                                       | className                      |
+=============================================================================================+======================+==================================================+================================+
| The time spent by a passenger waiting for a ride hail                                       | Waiting Time         | /ITERS/it.0/0.rideHailWaitingStats.csv           | RideHailWaitingAnalysisObject$ |
+---------------------------------------------------------------------------------------------+----------------------+--------------------------------------------------+--------------------------------+
| Hour of the day                                                                             | Hour                 | /ITERS/it.0/0.rideHailWaitingStats.csv           | RideHailWaitingAnalysisObject$ |
+---------------------------------------------------------------------------------------------+----------------------+--------------------------------------------------+--------------------------------+
| Frequencies of times spent waiting for a ride hail during the entire day                    | Count                | /ITERS/it.0/0.rideHailWaitingStats.csv           | RideHailWaitingAnalysisObject$ |
+---------------------------------------------------------------------------------------------+----------------------+--------------------------------------------------+--------------------------------+
| Time of a day in seconds                                                                    | timeOfDayInSeconds   | /ITERS/it.0/0.rideHailIndividualWaitingTimes.csv | RideHailWaitingAnalysisObject$ |
+---------------------------------------------------------------------------------------------+----------------------+--------------------------------------------------+--------------------------------+
| Unique id of the passenger travelling by the ride hail                                      | personId             | /ITERS/it.0/0.rideHailIndividualWaitingTimes.csv | RideHailWaitingAnalysisObject$ |
+---------------------------------------------------------------------------------------------+----------------------+--------------------------------------------------+--------------------------------+
| Unique id of the ride hail vehicle                                                          | rideHailVehicleId    | /ITERS/it.0/0.rideHailIndividualWaitingTimes.csv | RideHailWaitingAnalysisObject$ |
+---------------------------------------------------------------------------------------------+----------------------+--------------------------------------------------+--------------------------------+
| Time spent by the given passenger waiting for the arrival of the given ride hailing vehicle | waitingTimeInSeconds | /ITERS/it.0/0.rideHailIndividualWaitingTimes.csv | RideHailWaitingAnalysisObject$ |
+---------------------------------------------------------------------------------------------+----------------------+--------------------------------------------------+--------------------------------+

GraphSurgePricingObject
-----------------------
+---------------------------------------------------------------------------------------------------------------------+------------+----------------------------------------------+--------------------------+
| description                                                                                                         | field      | outputFile                                   | className                |
+=====================================================================================================================+============+==============================================+==========================+
| Travel fare charged by the ride hail in the given hour                                                              | PriceLevel | /ITERS/it.0/0.rideHailSurgePriceLevel.csv    | GraphSurgePricingObject$ |
+---------------------------------------------------------------------------------------------------------------------+------------+----------------------------------------------+--------------------------+
| Hour of the day                                                                                                     | Hour       | /ITERS/it.0/0.rideHailSurgePriceLevel.csv    | GraphSurgePricingObject$ |
+---------------------------------------------------------------------------------------------------------------------+------------+----------------------------------------------+--------------------------+
| Revenue earned by ride hail in the given hour                                                                       | Revenue    | /ITERS/it.0/0.rideHailRevenue.csv            | GraphSurgePricingObject$ |
+---------------------------------------------------------------------------------------------------------------------+------------+----------------------------------------------+--------------------------+
| Hour of the day                                                                                                     | Hour       | /ITERS/it.0/0.rideHailRevenue.csv            | GraphSurgePricingObject$ |
+---------------------------------------------------------------------------------------------------------------------+------------+----------------------------------------------+--------------------------+
| TAZ id                                                                                                              | TazId      | /ITERS/it.0/0.tazRideHailSurgePriceLevel.csv | GraphSurgePricingObject$ |
+---------------------------------------------------------------------------------------------------------------------+------------+----------------------------------------------+--------------------------+
| Type of data , can be "priceLevel" or "revenue"                                                                     | DataType   | /ITERS/it.0/0.tazRideHailSurgePriceLevel.csv | GraphSurgePricingObject$ |
+---------------------------------------------------------------------------------------------------------------------+------------+----------------------------------------------+--------------------------+
| Value of the given data type , can indicate either price Level or revenue earned by the ride hail in the given hour | Value      | /ITERS/it.0/0.tazRideHailSurgePriceLevel.csv | GraphSurgePricingObject$ |
+---------------------------------------------------------------------------------------------------------------------+------------+----------------------------------------------+--------------------------+
| Hour of the day                                                                                                     | Hour       | /ITERS/it.0/0.tazRideHailSurgePriceLevel.csv | GraphSurgePricingObject$ |
+---------------------------------------------------------------------------------------------------------------------+------------+----------------------------------------------+--------------------------+

RideHailingWaitingSingleAnalysisObject
--------------------------------------
+------------------------------------------------------+------------------+----------------------------------------------+-----------------------------------------+
| description                                          | field            | outputFile                                   | className                               |
+======================================================+==================+==============================================+=========================================+
| Time spent by a passenger on waiting for a ride hail | WaitingTime(sec) | /ITERS/it.0/0.rideHailWaitingSingleStats.csv | RideHailingWaitingSingleAnalysisObject$ |
+------------------------------------------------------+------------------+----------------------------------------------+-----------------------------------------+
| Hour of the day                                      | Hour*            | /ITERS/it.0/0.rideHailWaitingSingleStats.csv | RideHailingWaitingSingleAnalysisObject$ |
+------------------------------------------------------+------------------+----------------------------------------------+-----------------------------------------+

BeamMobsim
----------
+---------------------------------------------------------+-----------------+-------------------------------------------+-------------+
| description                                             | field           | outputFile                                | className   |
+=========================================================+=================+===========================================+=============+
| Unique id of the given ride hail agent                  | rideHailAgentID | /ITERS/it.0/0.rideHailInitialLocation.csv | BeamMobsim$ |
+---------------------------------------------------------+-----------------+-------------------------------------------+-------------+
| X co-ordinate of the starting location of the ride hail | xCoord          | /ITERS/it.0/0.rideHailInitialLocation.csv | BeamMobsim$ |
+---------------------------------------------------------+-----------------+-------------------------------------------+-------------+
| Y co-ordinate of the starting location of the ride hail | yCoord          | /ITERS/it.0/0.rideHailInitialLocation.csv | BeamMobsim$ |
+---------------------------------------------------------+-----------------+-------------------------------------------+-------------+

StopWatchOutputs
----------------
+-----------------------------------------------------------+--------------------------------+----------------+------------------+
| description                                               | field                          | outputFile     | className        |
+===========================================================+================================+================+==================+
| Iteration number                                          | Iteration                      | /stopwatch.txt | StopWatchOutputs |
+-----------------------------------------------------------+--------------------------------+----------------+------------------+
| Begin time of the iteration                               | BEGIN iteration                | /stopwatch.txt | StopWatchOutputs |
+-----------------------------------------------------------+--------------------------------+----------------+------------------+
| Time at which the iteration start event listeners started | BEGIN iterationStartsListeners | /stopwatch.txt | StopWatchOutputs |
+-----------------------------------------------------------+--------------------------------+----------------+------------------+
| Time at which  the iteration start event listeners ended  | END iterationStartsListeners   | /stopwatch.txt | StopWatchOutputs |
+-----------------------------------------------------------+--------------------------------+----------------+------------------+
| Time at which the replanning event started                | BEGIN replanning               | /stopwatch.txt | StopWatchOutputs |
+-----------------------------------------------------------+--------------------------------+----------------+------------------+
| Time at which the replanning event ended                  | END replanning                 | /stopwatch.txt | StopWatchOutputs |
+-----------------------------------------------------------+--------------------------------+----------------+------------------+
| Time at which the beforeMobsim event listeners started    | BEGIN beforeMobsimListeners    | /stopwatch.txt | StopWatchOutputs |
+-----------------------------------------------------------+--------------------------------+----------------+------------------+
| Begin dump all plans                                      | BEGIN dump all plans           | /stopwatch.txt | StopWatchOutputs |
+-----------------------------------------------------------+--------------------------------+----------------+------------------+
| End dump all plans                                        | END dump all plans             | /stopwatch.txt | StopWatchOutputs |
+-----------------------------------------------------------+--------------------------------+----------------+------------------+
| Time at which the beforeMobsim event listeners ended      | END beforeMobsimListeners      | /stopwatch.txt | StopWatchOutputs |
+-----------------------------------------------------------+--------------------------------+----------------+------------------+
| Time at which the mobsim run started                      | BEGIN mobsim                   | /stopwatch.txt | StopWatchOutputs |
+-----------------------------------------------------------+--------------------------------+----------------+------------------+
| Time at which the mobsim run ended                        | END mobsim                     | /stopwatch.txt | StopWatchOutputs |
+-----------------------------------------------------------+--------------------------------+----------------+------------------+
| Time at which the afterMobsim event listeners started     | BEGIN afterMobsimListeners     | /stopwatch.txt | StopWatchOutputs |
+-----------------------------------------------------------+--------------------------------+----------------+------------------+
| Time at which the afterMobsim event listeners ended       | END afterMobsimListeners       | /stopwatch.txt | StopWatchOutputs |
+-----------------------------------------------------------+--------------------------------+----------------+------------------+
| Time at which the scoring event started                   | BEGIN scoring                  | /stopwatch.txt | StopWatchOutputs |
+-----------------------------------------------------------+--------------------------------+----------------+------------------+
| Time at which the scoring event ended                     | END scoring                    | /stopwatch.txt | StopWatchOutputs |
+-----------------------------------------------------------+--------------------------------+----------------+------------------+
| Time at which the iteration ends event listeners ended    | BEGIN iterationEndsListeners   | /stopwatch.txt | StopWatchOutputs |
+-----------------------------------------------------------+--------------------------------+----------------+------------------+
| Time at which compare with counts started                 | BEGIN compare with counts      | /stopwatch.txt | StopWatchOutputs |
+-----------------------------------------------------------+--------------------------------+----------------+------------------+
| Time at which compare with counts ended                   | END compare with counts        | /stopwatch.txt | StopWatchOutputs |
+-----------------------------------------------------------+--------------------------------+----------------+------------------+
| Time at which the iteration ended                         | END iteration                  | /stopwatch.txt | StopWatchOutputs |
+-----------------------------------------------------------+--------------------------------+----------------+------------------+

ScoreStatsOutputs
-----------------
+-------------------------------------------------------------------+---------------+-----------------+-------------------+
| description                                                       | field         | outputFile      | className         |
+===================================================================+===============+=================+===================+
| Iteration number                                                  | ITERATION     | /scorestats.txt | ScoreStatsOutputs |
+-------------------------------------------------------------------+---------------+-----------------+-------------------+
| Average of the total execution time for the given iteration       | avg. EXECUTED | /scorestats.txt | ScoreStatsOutputs |
+-------------------------------------------------------------------+---------------+-----------------+-------------------+
| Average of worst case time complexities for the given iteration   | avg. WORST    | /scorestats.txt | ScoreStatsOutputs |
+-------------------------------------------------------------------+---------------+-----------------+-------------------+
| Average of average case time complexities for the given iteration | avg. AVG      | /scorestats.txt | ScoreStatsOutputs |
+-------------------------------------------------------------------+---------------+-----------------+-------------------+
| Average of best case time complexities for the given iteration    | avg. BEST     | /scorestats.txt | ScoreStatsOutputs |
+-------------------------------------------------------------------+---------------+-----------------+-------------------+

SummaryStatsOutputs
-------------------
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| description                                                                                                                     | field                                  | outputFile        | className           |
+=================================================================================================================================+========================================+===================+=====================+
| Iteration number                                                                                                                | Iteration                              | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Time taken by the agent to travel in a crowded transit                                                                          | agentHoursOnCrowdedTransit             | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Amount of diesel consumed in megajoule                                                                                          | fuelConsumedInMJ_Diesel                | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Amount of food consumed in megajoule                                                                                            | fuelConsumedInMJ_Food                  | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Amount of electricity consumed in megajoule                                                                                     | fuelConsumedInMJ_Electricity           | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Amount of gasoline consumed in megajoule                                                                                        | fuelConsumedInMJ_Gasoline              | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Time at which the beforeMobsim event listeners started                                                                          | numberOfVehicles_BEV                   | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Number of vehicles of type BODY-TYPE-DEFAULT                                                                                    | numberOfVehicles_BODY-TYPE-DEFAULT     | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Number of vehicles of type BUS-DEFAULT                                                                                          | numberOfVehicles_BUS-DEFAULT           | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Time at which the beforeMobsim event listeners ended                                                                            | numberOfVehicles_Car                   | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Time at which the mobsim run started                                                                                            | numberOfVehicles_SUBWAY-DEFAULT        | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Time taken by the passenger to travel by car                                                                                    | personTravelTime_car                   | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Time taken by the passenger to drive to the transit                                                                             | personTravelTime_drive_transit         | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Time taken by the passenger to travel by other means                                                                            | personTravelTime_others                | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Time taken by the passenger to travel on foot                                                                                   | personTravelTime_walk                  | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Time taken by the passenger to walk to the transit                                                                              | personTravelTime_walk_transit          | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Total cost (including subsidy) paid by the passenger to reach destination by walking to transit and then transit to destination | totalCostIncludingSubsidy_walk_transit | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Total cost (including subsidy) paid by the passenger to reach destination on a ride hail                                        | totalCostIncludingSubsidy_ride_hail    | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Total subsidy amount paid to passenger to reach destination by driving to transit and then transit to destination               | totalSubsidy_drive_transit             | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Total subsidy amount paid to passenger to reach destination by ride hail                                                        | totalSubsidy_ride_hail                 | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Total subsidy amount paid to passenger to reach destination by walking to transit and then transit to destination               | totalSubsidy_walk_transit              | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Total time taken by the passenger to travel from source to destination                                                          | totalTravelTime                        | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Sum of all the delay times incurred by the vehicle during the travel                                                            | totalVehicleDelay                      | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Time taken (in hours) by the vehicle to travel from source to destination                                                       | vehicleHoursTraveled_BEV               | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Time taken (in hours) by the vehicle to travel from source to destination                                                       | vehicleHoursTraveled_BODY-TYPE-DEFAULT | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Time taken (in hours) by the vehicle(bus) to travel from source to destination                                                  | vehicleHoursTraveled_BUS-DEFAULT       | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Time taken (in hours) by the vehicle(car) to travel from source to destination                                                  | vehicleHoursTraveled_Car               | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Time taken (in hours) by the vehicle (subway) to travel from source to destination                                              | vehicleHoursTraveled_SUBWAY-DEFAULT    | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Miles covered by the vehicle to travel from source to destination                                                               | vehicleMilesTraveled_BEV               | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Miles covered by the vehicle to travel from source to destination                                                               | vehicleMilesTraveled_BODY-TYPE-DEFAULT | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Miles covered by the vehicle(bus) to travel from source to destination                                                          | vehicleMilesTraveled_BUS-DEFAULT       | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Miles covered by the vehicle(car) to travel from source to destination                                                          | vehicleMilesTraveled_Car               | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Miles covered by the vehicle(subway) to travel from source to destination                                                       | vehicleMilesTraveled_SUBWAY-DEFAULT    | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+
| Miles covered by the vehicles(all modes) to travel from source to destination                                                   | vehicleMilesTraveled_total             | /summaryStats.txt | SummaryStatsOutputs |
+---------------------------------------------------------------------------------------------------------------------------------+----------------------------------------+-------------------+---------------------+

CountsCompareOutputs
--------------------
+--------------------------------------------------------+---------------------------+---------------------------------+----------------------+
| description                                            | field                     | outputFile                      | className            |
+========================================================+===========================+=================================+======================+
| Iteration number                                       | Link Id                   | /ITERS/it.0/0.countsCompare.txt | CountsCompareOutputs |
+--------------------------------------------------------+---------------------------+---------------------------------+----------------------+
| Time taken by the agent to travel in a crowded transit | Count                     | /ITERS/it.0/0.countsCompare.txt | CountsCompareOutputs |
+--------------------------------------------------------+---------------------------+---------------------------------+----------------------+
| Amount of diesel consumed in megajoule                 | Station Id                | /ITERS/it.0/0.countsCompare.txt | CountsCompareOutputs |
+--------------------------------------------------------+---------------------------+---------------------------------+----------------------+
| Amount of food consumed in megajoule                   | Hour                      | /ITERS/it.0/0.countsCompare.txt | CountsCompareOutputs |
+--------------------------------------------------------+---------------------------+---------------------------------+----------------------+
| Amount of electricity consumed in megajoule            | MATSIM volumes            | /ITERS/it.0/0.countsCompare.txt | CountsCompareOutputs |
+--------------------------------------------------------+---------------------------+---------------------------------+----------------------+
| Amount of gasoline consumed in megajoule               | Relative Error            | /ITERS/it.0/0.countsCompare.txt | CountsCompareOutputs |
+--------------------------------------------------------+---------------------------+---------------------------------+----------------------+
| Time at which the beforeMobsim event listeners started | Normalized Relative Error | /ITERS/it.0/0.countsCompare.txt | CountsCompareOutputs |
+--------------------------------------------------------+---------------------------+---------------------------------+----------------------+
| GEH                                                    | GEH                       | /ITERS/it.0/0.countsCompare.txt | CountsCompareOutputs |
+--------------------------------------------------------+---------------------------+---------------------------------+----------------------+

EventOutputs
------------
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| description                                         | field                    | outputFile               | className    |
+=====================================================+==========================+==========================+==============+
| Person(Agent) Id                                    | person                   | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| vehicle id                                          | vehicle                  | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Start time of the vehicle                           | time                     | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Type of the event                                   | type                     | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Type of fuel used in the vehicle                    | fuel                     | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Duration of the travel                              | duration                 | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Cost of travel                                      | cost                     | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| X co-ordinate of the location                       | location.x               | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Y co-ordinate of the location                       | location.y               | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Parking type chosen by the vehicle                  | parking_type             | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Pricing model                                       | pricing_model            | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Charging type of the vehicle                        | charging_type            | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Parking TAZ                                         | parking_taz              | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Distance between source and destination             | distance                 | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Location of the vehicle                             | location                 | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Mode of travel                                      | mode                     | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Current tour mode                                   | currentTourMode          | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Expected maximum utility of the vehicle             | expectedMaximumUtility   | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Available alternatives for travel for the passenger | availableAlternatives    | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Whether the passenger possesses a personal vehicle  | personalVehicleAvailable | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Tour index                                          | tourIndex                | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Facility availed by the passenger                   | facility                 | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Time of departure of the vehicle                    | departTime               | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| X ordinate of the passenger origin point            | originX                  | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Y ordinate of the passenger origin point            | originY                  | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| X ordinate of the passenger destination point       | destinationX             | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Y ordinate of the passenger destination point       | destinationY             | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Fuel type of the vehicle                            | fuelType                 | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Num of passengers travelling in the vehicle         | num_passengers           | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Number of links in the network                      | links                    | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Departure time of the vehicle                       | departure_time           | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Arrival time of the vehicle                         | arrival_time             | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Type of vehicle                                     | vehicle_type             | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Total capacity of the vehicle                       | capacity                 | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| X ordinate of the start point                       | start.x                  | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Y ordinate of the start point                       | start.y                  | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| X ordinate of the vehicle end point                 | end.x                    | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Y ordinate of the vehicle end point                 | end.y                    | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Fuel level at the end of the travel                 | end_leg_fuel_level       | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Seating capacity of the vehicle                     | seating_capacity         | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+
| Type of cost of travel incurred on the passenger    | costType                 | /ITERS/it.0/0.events.csv | EventOutputs |
+-----------------------------------------------------+--------------------------+--------------------------+--------------+

LegHistogramOutputs
-------------------
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| description                                                                 | field                    | outputFile                     | className           |
+=============================================================================+==========================+================================+=====================+
| Time                                                                        | time                     | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Time                                                                        | time                     | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of departures on all modes                                     | departures_all           | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of arrivals on all modes                                       | arrivals_all             | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Duration of travel                                                          | duration                 | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of travels that got stuck on all modes                         | stuck_all                | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of travels by all modes                                        | en-route_all             | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of departures by car                                           | departures_car           | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of departures by car                                           | arrivals_car             | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of travels that got stuck while travelling by car              | stuck_car                | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of travels made by car                                         | en-route_car             | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of departures by drive to transit                              | departures_drive_transit | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of arrivals by drive to transit                                | arrivals_drive_transit   | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of travels that got stuck while travelling by drive to transit | stuck_drive_transit      | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of travels made by drive to transit                            | en-route_drive_transit   | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of departures by ride hail                                     | departures_ride_hail     | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of arrivals by ride hail                                       | arrivals_ride_hail       | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of travels that got stuck while travelling by ride hail        | stuck_ride_hail          | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of travels made by ride hail                                   | en-route_ride_hail       | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of departures on foot                                          | departures_walk          | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of arrivals on foot                                            | arrivals_walk            | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of travels that got stuck while travelling on foot             | stuck_walk               | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of travels made on foot                                        | en-route_walk            | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of departures by walk to transit                               | departures_walk_transit  | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of arrivals by walk to transit                                 | arrivals_walk_transit    | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of travels that got stuck while travelling by walk to transit  | stuck_walk_transit       | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+
| Total number of travels made by walk to transit                             | en-route_walk_transit    | /ITERS/it.0/0.legHistogram.txt | LegHistogramOutputs |
+-----------------------------------------------------------------------------+--------------------------+--------------------------------+---------------------+

RideHailTripDistanceOutputs
---------------------------
+---------------------------------------------------------------+---------------+----------------------------------------+-----------------------------+
| description                                                   | field         | outputFile                             | className                   |
+===============================================================+===============+========================================+=============================+
| Hour of the day                                               | hour          | /ITERS/it.0/0.rideHailTripDistance.csv | RideHailTripDistanceOutputs |
+---------------------------------------------------------------+---------------+----------------------------------------+-----------------------------+
| Number of passengers travelling in the ride hail              | numPassengers | /ITERS/it.0/0.rideHailTripDistance.csv | RideHailTripDistanceOutputs |
+---------------------------------------------------------------+---------------+----------------------------------------+-----------------------------+
| Total number of kilometers travelled by the ride hail vehicle | vkt           | /ITERS/it.0/0.rideHailTripDistance.csv | RideHailTripDistanceOutputs |
+---------------------------------------------------------------+---------------+----------------------------------------+-----------------------------+

TripDurationOutputs
-------------------
+---------+---------+--------------------------------+---------------------+
| description | field   | outputFile                     | className           |
+=========+=========+================================+=====================+
| Pattern | pattern | /ITERS/it.0/0.tripDuration.txt | TripDurationOutputs |
+---------+---------+--------------------------------+---------------------+
| Value   | (5*i)+  | /ITERS/it.0/0.tripDuration.txt | TripDurationOutputs |
+---------+---------+--------------------------------+---------------------+

BiasErrorGraphDataOutputs
-------------------------
+---------------------+---------------------+--------------------------------------+---------------------------+
| description         | field               | outputFile                           | className                 |
+=====================+=====================+======================================+===========================+
| Hour of the day     | hour                | /ITERS/it.0/0.biasErrorGraphData.txt | BiasErrorGraphDataOutputs |
+---------------------+---------------------+--------------------------------------+---------------------------+
| Mean relative error | mean relative error | /ITERS/it.0/0.biasErrorGraphData.txt | BiasErrorGraphDataOutputs |
+---------------------+---------------------+--------------------------------------+---------------------------+
| Mean bias value     | mean bias           | /ITERS/it.0/0.biasErrorGraphData.txt | BiasErrorGraphDataOutputs |
+---------------------+---------------------+--------------------------------------+---------------------------+

BiasNormalizedErrorGraphDataOutputs
-----------------------------------
+--------------------------------+--------------------------------+------------------------------------------------+-------------------------------------+
| description                    | field                          | outputFile                                     | className                           |
+================================+================================+================================================+=====================================+
| Hour of the day                | hour                           | /ITERS/it.0/0.biasNormalizedErrorGraphData.txt | BiasNormalizedErrorGraphDataOutputs |
+--------------------------------+--------------------------------+------------------------------------------------+-------------------------------------+
| Mean normalized relative error | mean normalized relative error | /ITERS/it.0/0.biasNormalizedErrorGraphData.txt | BiasNormalizedErrorGraphDataOutputs |
+--------------------------------+--------------------------------+------------------------------------------------+-------------------------------------+
| Mean bias value                | mean bias                      | /ITERS/it.0/0.biasNormalizedErrorGraphData.txt | BiasNormalizedErrorGraphDataOutputs |
+--------------------------------+--------------------------------+------------------------------------------------+-------------------------------------+
