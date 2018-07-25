Behaviors
=========

Person Agents in BEAM exhibit several within-day behaviors that govern their use of the transportation system.

Mode Choice
-------------

The most prominent behavior is mode choice. Mode choice can be specified either exogensously as a field in the persons plans, or it can be selected during replanning, or it can remain unset and be selected within the day.

Within day mode choice is selected based on the attributes of the first trip of each tour. Once a mode is selected for the tour, the person attempts to stick with that mode for the duration of the tour. 

In all cases (whether mode is specified before the day or chosen within the day) person agents use WALK as a fallback option throughout if constraints otherwise prevent their previously determined mode from being possible for any given trip. E.g. if a person is in the middle of a RIDE_HAIL tour, but the Ride Hail Manager is unable to match a driver to the person, then the person will walk.

In BEAM the following modes are considers:

* Walk
* Bike
* Drive (alone)
* Walk to Transit
* Drive to Transit (Park and Ride)
* Ride Hail
* Ride Hail to/from Transit

There are two mode choice models that are possible within BEAM. 

Multinomial Logit Mode Choice
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

The first is a simple multinomial logit choice model that has the following form for modal alternative j:

V_j = ASC_j + Beta_cost * cost + Beta_time * time + Beta_xfer * num_transfers + 

The ASC (alternative specific constant) parameters as well as the Beta parameters can be configured in the BEAM configuration file and default to the following values:

beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.cost = -1.0
beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.time = -0.0047
beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.transfer = -1.4
beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.car_intercept = 0.0
beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.walk_transit_intercept = 0.0
beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.drive_transit_intercept = 0.0
beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hail_transit_intercept = 0.0
beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hailing_intercept = 0.0
beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.walk_intercept = 0.0
beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.bike_intercept = 0.0

Latent Class Mode Choice
~~~~~~~~~~~~~~~~~~~~~~~~

Parking
-------

Refueling
---------

