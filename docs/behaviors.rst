Behaviors
=========

Person Agents in BEAM exhibit several within-day behaviors that govern their use of the transportation system.

Mode Choice
-----------

The most prominent behavior is mode choice. Mode choice can be specified either exogenously as a field in the persons plans, or it can be selected during replanning, or it can remain unset and be selected within the day. For agents that start the day at home, a tour can either be home based or nested within a home based tour (for instance, if an agent takes a lunch trip from work).



Tour Mode vs Trip Mode
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Individual plans can be considered as both sequences of trips and sequences of tours. Trips are defined by a travel leg and a destination activity, and each tour is defined as a sequence of trips that start and end at the same location.

Consider an agent who has a sequence of activities:

``Home`` --> ``Work`` --> ``Eat`` --> ``Work`` --> ``Shop`` --> ``Home``

Primary Home Based Tour
''''''''''''''''''''''''
**Home-based Tour** ---> ``Home`` --> ``Work`` --> ``Shop`` --> ``Home``

This tour includes the primary work trip, in addition to a shopping stop on the way home

Secondary Work Based Tour
''''''''''''''''''''''''
**Work-based Subtour** ---> ``Work`` --> ``Eat`` --> ``Work``

This subtour includes a work-based leg to go out to eat, followed by the return leg back to work


The motivation for dividing the plan into tours is largly due to to constraints around vehicle choice, where decisions made at the tour level impact all trips in the tour. For instance, if this agent chose to drive their car to work as their first trip of the day, they would be constrained to use that car for the rest of the trips in their primary home based tour such that the personal vehicle is returned home at the end of the day. However, the legs to and from the mid-day eating activity are considered as part of a secondary work-based tour rather than the primary home-based tour because the trip mode for these legs is *not* constrained to make use of the private car. Because the agent returns back to the same location at the end of the work based tour, they are able to leave behind their vehicle on the eating trip and then pick it back up after the second work trip.

Therefore, for each tour there is an initial choice of *tour mode*, which applies for the entire tour and constrains the modes available for each trip on that tour. Then, for each of these trips the *trip mode* is chosen upon departing for the trip, given the constraints posed by the chosen tour mode.

In BEAM the following **trip modes** are considered:

* Walk
* Bike
* Drive (alone or with passengers)
* Private car passenger (only allowed if defined in input plans)
* Walk to Transit
* Drive to Transit (Park and Ride)
* Bike to Transit (Park and Ride)
* Ride Hail (Solo, Pooled)
* Ride Hail to/from Transit

And the following **tour modes** are considered, with the trip modes that are allowed under that tour mode

* Walk based
    * Walk
    * Private car passenger
    * Walk to Transit
    * Drive / bike to Transit (only for first and last legs of tour)
    * Ride Hail (Solo, Pooled)
    * Ride Hail to/from Transit
    * Drive and Bike (only if they use shared vehicles)
* Car based
    * Drive (alone or with passengers)
* Bike based
    * Bike

When an agent starts a day without pre-chosen trip and tour modes, they make their first choices when they are departing on their first trip of the day. They first estimate the utility of taking each trip of their upcoming tour via every available mode, using the same utility equations used in the trip mode choice model (see below). The utilities for the first trip are taken from the beam router using the vehicles (shared and private) available to the agent at the time of their departure, and the utilities for the remaining trips are estimated from the skims. For each trip in the tour, the utilities are grouped into tour modes based on which modes are allowed by each tour mode, and the expected maximum utility is calculated for each tour mode for each trip by taking the logsum of the utilities for the available modes. The total expected utility of a tour mode is taken by summing the maximum expected utilities of each trip given that tour mode. The tour mode is chosen via a multinomial logit over the expected utilities for the three tour modes.

Once the tour mode is chosen, it is stored for the remainder of the tour, and the agent completes a trip mode choice process each time they depart on a trip (including immediately after making their initial tour mode choice). In some cases, a tour mode choice also involves choosing a specific personal vehicle. This is most apparent for ``CAR_BASED`` and ``BIKE_BASED`` tour modes, which involve choosing the vehicle that is taken along on the tour and must be returned home at the end of the day. In addition, ``WALK_BASED`` tours can be assigned personal vehicles if a personal vehicle is used for access to transit in the first trip of the tour (for instance, a multimodal park and ride trip). In that case, the vehicle remains parked at a transit station and needs to be returned home on the last trip of the day by a multimodal transit trip, with the vehicle being used for the egress rather than access portion of the trip). Currently, BEAM does not support multimodal trips with personal vehicles in the middle of tours rather than as the first and last legs.

In all cases (whether mode is specified before the day or chosen within the day) person agents use WALK as a fallback option throughout if constraints otherwise prevent their previously determined mode from being possible for any given trip. E.g. if a person is in the middle of a RIDE_HAIL tour, but the Ride Hail Manager is unable to match a driver to the person, then the person will walk.

There are two mode choice models that are possible within BEAM. 

Multinomial Logit Mode Choice
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

The first is a simple multinomial logit choice model that has the following form for modal alternative j:

V_j = ASC_j + Beta_cost * cost + Beta_time * time + Beta_xfer * num_transfers + Beta_occup * transit_occupancy

The ASC (alternative specific constant) parameters as well as the Beta parameters can be configured in the BEAM configuration file and default to the following values:

::

    beam.agentsim.agents.modalBehaviors.multinomialLogit.params.cost = -1.0
    beam.agentsim.agents.modalBehaviors.multinomialLogit.params.time = -0.0047
    beam.agentsim.agents.modalBehaviors.multinomialLogit.params.transfer = -1.4
    beam.agentsim.agents.modalBehaviors.multinomialLogit.params.car_intercept = 0.0
    beam.agentsim.agents.modalBehaviors.multinomialLogit.params.walk_transit_intercept = 0.0
    beam.agentsim.agents.modalBehaviors.multinomialLogit.params.drive_transit_intercept = 0.0
    beam.agentsim.agents.modalBehaviors.multinomialLogit.params.ride_hail_transit_intercept = 0.0
    beam.agentsim.agents.modalBehaviors.multinomialLogit.params.ride_hail_intercept = 0.0
    beam.agentsim.agents.modalBehaviors.multinomialLogit.params.walk_intercept = 0.0
    beam.agentsim.agents.modalBehaviors.multinomialLogit.params.bike_intercept = 0.0
    beam.agentsim.agents.modalBehaviors.multinomialLogit.params.transit_crowding = 0.0
    beam.agentsim.agents.modalBehaviors.multinomialLogit.params.transit_crowding_percentile = 90

Latent Class Mode Choice
~~~~~~~~~~~~~~~~~~~~~~~~

This method is no longer being actively updated.

Parking
-------

In BEAM, parking is issued at the granularity of a Traffic Analysis Zone (TAZ) or a Link. Upon initialization, parking alternatives are read in from the CSV file listed in the BEAM config parameter *beam.agentsim.taz.parking*. Each row identifies the attributes of a parking alternative for a given TAZ, of which a given combination of attributes should be unique. Parking attributes include the following:

+---------------------+----------------------------------------------+
| attribute           | values                                       |
+=====================+==============================================+
| *parkingType*       | Workplace, Public, Residential               |
+---------------------+----------------------------------------------+
| *pricingModel*      | FlatFee, Block                               |
+---------------------+----------------------------------------------+
| *chargingPointType* | NoCharger, Level1, Level2, DCFast, UltraFast |
+---------------------+----------------------------------------------+
| *numStalls*         | *integer*                                    |
+---------------------+----------------------------------------------+
| *feeInCents*        | *integer*                                    |
+---------------------+----------------------------------------------+
| *reservedFor*       | Any, RideHailManager                         |
+---------------------+----------------------------------------------+

BEAM agents seek parking mid-tour, from within a leg of their trip. A search is run which starts at the trip destination and expands outward, seeking to find the closest TAZ centers with increasing search radii. Agents will pick the closest and cheapest parking alternative with attributes which match their use case.

The following should be considered when configuring a set of parking alternatives. The default behavior is to provide a nearly unbounded number of parking stalls for each combination of attributes, per TAZ, for the public, and provide no parking alternatives for ride hail agents. This behavior can be overridden manually by providing replacement values in the parking configuration file. Parking which is *reservedFor* a RideHailManager should only appear as *Workplace* parking. Free parking can be instantiated by setting *feeInCents* to zero. *numStalls* should be non-negative. Charging behavior is currently implemented for ride hail agents only.

the *chargingPointType* attribute will result in the following charger power in kW:

+----------------+--------+
| *chargingPointType* | kW|
+================+========+
| NoCharger      | 0.0    |
+----------------+--------+
| Level1         | 1.5    |
+----------------+--------+
| Level2         | 6.7    |
+----------------+--------+
| DCFast         | 50.0   |
+----------------+--------+
| UltraFast      | 250.0  |
+----------------+--------+

Refueling
---------

Refueling is based on remaining range of the vehicle. In order to control refueling behavior one need to modify parameters
in the following spaces

#. beam.agentsim.agents.vehicles.enroute
#. beam.agentsim.agents.vehicles.destination
#. beam.agentsim.agents.rideHail.human
#. beam.agentsim.agents.rideHail.cav

See :ref:`model-inputs` for more information about these parameters.

Reposition
----------

In BEAM, reposition of shared vehicles is based on availability. minAvailabilityMap stores the TAZs with lowest availability of vehicles, and we reposition the number of matchLimit vehicles from the TAZs with available fleets more than matchLimit based on statTimeBin and repositionTimeBin to determine when we start doing reposition and the frequency of repositioning.

There are several parameters we can adjust in repositioning:

+----------------+--------------------------------------------------+
| Parameters          | Meaning                                     |
+================+==================================================+
| *matchLimit*        | How many vehicles we want to reposition     |
+----------------+--------------------------------------------------+
| *repositionTimeBin* | How often we do repositioning               |
+----------------+--------------------------------------------------+
| *statTimeBin*       | When do we start repositioning              |
+----------------+--------------------------------------------------+

