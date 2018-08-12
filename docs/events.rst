
.. _event-specifications:

Event Specifications
====================

For an overview of events, including compatibility with MATSim, see :ref:`matsim-events`.

The following lists each field in each event with some brief descriptions and contextual information where necessary.

MATSim Events
-------------

The following MATSim events are thrown within the AgentSim: 

ActivityStartEvent
~~~~~~~~~~~~~~~~~~

* Time - Time of the start of the activity.
* Activity Type - String denoting the type of activity (e.g. "Home" or "Work")
* Person - Person ID of the person agent engaged in the activity.
* Link - Link ID of the nearest link to the activity location
* Facility - Facility ID (unused in BEAM)

ActivityEndEvent
~~~~~~~~~~~~~~~~

* Time - Time of the end of the activity.
* Activity Type - String denoting the type of activity (e.g. "Home" or "Work")
* Person - Person ID of the person agent engaged in the activity.
* Link - Link ID of the nearest link to the activity location
* Facility - Facility ID (unused in BEAM)

PersonDepartureEvent
~~~~~~~~~~~~~~~~~~~~

* Time - Time of the person departure.
* Person - Person ID of the person departing.
* Leg Mode - String denoting the trip mode of the trip to be attempted (trip mode is the overall mode of the trip, which is different than the mode of individual sub-legs of the trip, e.g. a trip with leg mode TRANSIT might have sub-legs of mode WALK, BUS, SUBWAY, WALK).
* Link - Link ID of the nearest link to the departure location.

PersonArrivalEvent
~~~~~~~~~~~~~~~~~~

* Time - Time of the person arrival.
* Person - Person ID of the person arriving.
* Leg Mode - String denoting the trip mode of the trip completed (trip mode is the overall mode of the trip, which is different than the mode of individual sub-legs of the trip, e.g. a trip with leg mode TRANSIT might have sub-legs of mode WALK, BUS, SUBWAY, WALK).
* Link - Link ID of the nearest link to the arrival location.

PersonEntersVehicleEvent
~~~~~~~~~~~~~~~~~~~~~~~~

* Time - Time of the vehicle entry.
* Person - Person ID of the person entering the vehicle.
* Vehicle - Vehicle ID of the vehicle being entered.

PersonLeavesVehicleEvent
~~~~~~~~~~~~~~~~~~~~~~~~

* Time - Time of the vehicle exit.
* Person - Person ID of the person exiting the vehicle.
* Vehicle - Vehicle ID of the vehicle being exited.

BEAM Events
-----------
These events are specific to BEAM and are thrown within the AgentSim:

ModeChoiceEvent
~~~~~~~~~~~~~~~
Note that this event corresponds to the moment of choosing a mode, if mode choice is occurring dynamically within the day. If mode choice occurs outside of the simulation day, then this event is not thrown. Also, the time of choosing mode is not always the same as the departure time.

* Time - Time of the mode choice.
* Person - Person ID of the person making the mode choice.
* Mode - The chosen trip mode (e.g. WALK_TRANSIT or CAR)
* Expected Maximum Utility - The logsum from the utility function used to evalute modal alternatives. If the mode choice model is not a random utility based model, then this will be left blank.
* Location - Link ID of the nearest location.
* Available Alternatives - Comma-separated list of the alternatives considered by the agent during the mode choice process.
* Persona Vehicle Available - Boolean denoting whether this agent had a personal vehicle availalbe to them during the mode choice process.
* Length - the length of the chosen trip in meters.
* Tour index - the index of the chosen trip within the current tour of the agent (e.g. 0 means the first trip of the tour, 1 is the second trip, etc.)

PathTraversalEvent
~~~~~~~~~~~~~~~~~~
A Path Traversal is any time a vehicle moves within the BEAM AgentSim.

* Length - Length of the movement in meters.
* Fuel - fuel consumed during the movement in Joules.
* Num Passengers - the number of passengers on board during the vehicle movement (the driver does not count as a passenger).
* Links - Comma-separated list of link IDs indicating the path taken.
* Mode - the sub-leg mode of the traversal (e.g. BUS or CAR or SUBWAY).
* Departure Time - the time of departure.
* Arrival Time - the time of arrival.
* Vehicle - the ID of the vehilce making the movement.
* Vehicle Type - String indicating the type of vehicle.
* Start X - X coordinate of the starting location of the movement. Coordinates are output in WGS (lat/lon).
* Start Y - Y coordinate of the starting location of the movement. Coordinates are output in WGS (lat/lon).
* End X - X coordinate of the ending location of the movement. Coordinates are output in WGS (lat/lon).
* End Y - Y coordinate of the ending location of the movement. Coordinates are output in WGS (lat/lon).
* End Leg Fuel Level - Amount of fuel (in Joules) remaining in the vehicle at the end of the movement.
