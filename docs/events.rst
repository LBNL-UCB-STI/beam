
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

LinkEnterEvent
~~~~~~~~~~~~~~

* Time - Time of the vehicle entering.
* Link - ID of the link being entering.
* Vehicle - Vehicle ID of the vehicle entering.

LinkLeaveEvent
~~~~~~~~~~~~~~

* Time - Time of the vehicle leaving.
* Link - ID of the link being leaving.
* Vehicle - Vehicle ID of the vehicle leaving.

VehicleEntersTrafficEvent
~~~~~~~~~~~~~~~~~~~~~~~~~

* Time - Time of the vehicle entering.
* Vehicle - Vehicle ID of the vehicle entering.
* Link - ID of the link being entering.
* Person - Person ID of the vehicle driver.
* Network Mode - String denoting the network mode ("car").
* Relative Position - the relative position of the vehicle on the link (0.0 - 1.0).

VehicleLeavesTrafficEvent
~~~~~~~~~~~~~~~~~~~~~~~~~

* Time - Time of the vehicle leaving.
* Vehicle - Vehicle ID of the vehicle leaving.
* Link - ID of the link being leaving.
* Person - Person ID of the vehicle driver.
* Network Mode - String denoting the network mode ("car").
* Relative Position - the relative position of the vehicle on the link (0.0 - 1.0).

BEAM Events
-----------
These events are specific to BEAM and are thrown within the AgentSim:

ModeChoiceEvent
~~~~~~~~~~~~~~~
Note that this event corresponds to the moment of choosing a mode, if mode choice is occurring dynamically within the day. If mode choice occurs outside of the simulation day, then this event is not thrown. Also, the time of choosing mode is not always the same as the departure time.

* Time - Time of the mode choice.
* Person - Person ID of the person making the mode choice.
* Mode - The chosen trip mode (e.g. WALK_TRANSIT or CAR)
* Expected Maximum Utility - The logsum from the utility function used to evaluate modal alternatives. If the mode choice model is not a random utility based model, then this will be left blank.
* Location - Link ID of the nearest location.
* Available Alternatives - Comma-separated list of the alternatives considered by the agent during the mode choice process.
* Persona Vehicle Available - Boolean denoting whether this agent had a personal vehicle available to them during the mode choice process.
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
* Vehicle - the ID of the vehicle making the movement.
* Vehicle Type - String indicating the type of vehicle.
* Start X - X coordinate of the starting location of the movement. Coordinates are output in WGS (lat/lon).
* Start Y - Y coordinate of the starting location of the movement. Coordinates are output in WGS (lat/lon).
* End X - X coordinate of the ending location of the movement. Coordinates are output in WGS (lat/lon).
* End Y - Y coordinate of the ending location of the movement. Coordinates are output in WGS (lat/lon).
* End Leg Fuel Level - Amount of fuel (in Joules) remaining in the vehicle at the end of the movement.

ChargingPlugInEvent
~~~~~~~~~~~~~~~~~~~
It indicates that an electric vehicle starts charging.

* vehicle - The id of the vehicle
* primaryFuelLevel - Primary fuel level of the vehicle at the start charging event in joules.
* secondaryFuelLevel - Secondary fuel level of the vehicle at the start charging event in joules.
* price - Cost in dollars for the parking stall.
* locationX - X coordinate of the parking stall.
* locationY - Y coordinate of the parking stall.
* parkingType - Type of parking: Residential, Public, Workplace.
* pricingModel - Pricing model: FlatFee, Block.
* chargingPointType - Charging point type: HouseholdSocket, BlueHouseholdSocket, Cee16ASocket, Cee32ASocket,
    Cee63ASocket, ChargingStationType1, ChargingStationType2, ChargingStationCcsComboType1,
    ChargingStationCcsComboType2, TeslaSuperCharger. Or Custom type with included id, installed capacity, current type.
* parkingTaz - The id of TAZ where the parking stall resides.

ChargingPlugOutEvent
~~~~~~~~~~~~~~~~~~~~
It indicates that a vehicle finished charging.

* vehicle - The id of the vehicle
* primaryFuelLevel - Primary fuel level of the vehicle at the event time in joules.
* secondaryFuelLevel - Secondary fuel level of the vehicle at the event time in joules.
* price - Cost in dollars for the parking stall.
* locationX - X coordinate of the parking stall.
* locationY - Y coordinate of the parking stall.
* parkingType - Type of parking: Residential, Public, Workplace.
* pricingModel - Pricing model: FlatFee, Block.
* chargingPointType - Charging point type: HouseholdSocket, BlueHouseholdSocket, Cee16ASocket, Cee32ASocket,
    Cee63ASocket, ChargingStationType1, ChargingStationType2, ChargingStationCcsComboType1,
    ChargingStationCcsComboType2, TeslaSuperCharger. Or Custom type with included id, installed capacity, current type.
* parkingTaz - The id of TAZ where the parking stall resides.

FleetStoredElectricityEvent
~~~~~~~~~~~~~~~~~~~~~~~~~~~
This events happens at the beginning of each iteration and provides data about total amount of stored electric power
    in a fleet of vehicles.

* fleetId - The fleet id: ridehail-fleet-$fleet_name for a ride-hail fleet or all-private-vehicles for all the private vehicles.
* storedElectricityInJoules - Total stored electricity power of the fleet in Joules.
* storageCapacityInJoules - Total electric capacity of the fleet in Joules.

LeavingParkingEvent
~~~~~~~~~~~~~~~~~~~
It indicates that a vehicle leaves a parking stall.

* score - Negative value of parking cost + charged energy.
* parkingType - Type of parking: Residential, Public, Workplace.
* pricingModel - Pricing model: FlatFee, Block.
* chargingPointType - Charging point type (if presented): HouseholdSocket, BlueHouseholdSocket, Cee16ASocket, Cee32ASocket,
    Cee63ASocket, ChargingStationType1, ChargingStationType2, ChargingStationCcsComboType1,
    ChargingStationCcsComboType2, TeslaSuperCharger. Or Custom type with included id, installed capacity, current type.
* parkingTaz - Id of TAZ where the parking stall resides.
* vehicle - The id of the vehicle.
* driver - The id of the driver.

ParkingEvent
~~~~~~~~~~~~
A vehicle parks.

* vehicle - The id of the vehicle.
* driver - The id of the driver.
* cost - ost in dollars for the parking stall.
* locationX - X coordinate of the parking stall.
* locationY - Y coordinate of the parking stall.
* parkingType - Type of parking: Residential, Public, Workplace.
* pricingModel - Pricing model: FlatFee, Block.
* chargingPointType - Charging point type (if presented): HouseholdSocket, BlueHouseholdSocket, Cee16ASocket, Cee32ASocket,
    Cee63ASocket, ChargingStationType1, ChargingStationType2, ChargingStationCcsComboType1,
    ChargingStationCcsComboType2, TeslaSuperCharger. Or Custom type with included id, installed capacity, current type.
* parkingTaz - Id of TAZ where the parking stall resides.

RefuelSessionEvent
~~~~~~~~~~~~~~~~~~
Ending of a refuel session

* duration - The duration of the session.
* fuel - Energy delivered in Joules.
* vehicle - The vehicle id.
* price - The cost of fuel delivered.
* parkingZoneId - The parking zone id.
* locationX - X coordinate of the parking stall.
* locationY - Y coordinate of the parking stall.
* parkingType - Type of parking: Residential, Public, Workplace.
* pricingModel - Pricing model: FlatFee, Block.
* chargingPointType - Charging point type (if presented): HouseholdSocket, BlueHouseholdSocket, Cee16ASocket, Cee32ASocket,
    Cee63ASocket, ChargingStationType1, ChargingStationType2, ChargingStationCcsComboType1,
    ChargingStationCcsComboType2, TeslaSuperCharger. Or Custom type with included id, installed capacity, current type.
* parkingTaz - Id of TAZ where the parking stall resides.
* vehicleType - Id of the type of the vehicle.
* shiftStatus - Shift status of the ride-hail agent (if applicable): OnShift, OffShift.
* person - the driver id or the person id the vehicle belongs to (in case of automated vehicle).
* actType - String denoting the type of activity (e.g. "Home" or "Work").

RideHailReservationConfirmationEvent
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
It indicates that a ride-hail reservation is confirmed or rejected with an error.

* person - The customer id.
* vehicle - The id of a ride-hail vehicle that is reserved.
* reservationType - Ride-hail reservation type: Solo or Pooled.
* errorCode - The error code in case of reservation rejection: UnknownInquiryId, RideHailVehicleTaken,
    RideHailNotRequested, UnknownRideHailReservation, RideHailRouteNotFound, ResourceUnavailable,
    ResourceCapacityExhausted.
* reservationTime - The time of reservation.
* requestedPickupTime - The requested pickup time.
* quotedWaitTimeInS - Quoted wait time in seconds.
* startX - X coordinate of the origin. Coordinates are output in WGS (lat/lon).
* startY - Y coordinate of the origin. Coordinates are output in WGS (lat/lon).
* endX - X coordinate of the destination. Coordinates are output in WGS (lat/lon).
* endY - Y coordinate of the destination. Coordinates are output in WGS (lat/lon).
* offeredPickupTime - Offered pickup time.
* directRouteDistanceInM - Trip distance in meters from origin to destination in Solo mode.
* directRouteDurationInS - Trip duration in seconds from origin to destination in Solo mode.
* cost - Estimated cost of the trip.
* wheelchairRequirement - Whether or not the customer requested a wheelchair.

ShiftEvent
~~~~~~~~~~
It happens when a ride-hail driver starts/ends shift.

* shiftEventType - Shift event type: StartShift, EndShift.
* driver - The id of the driver.
* vehicle - The id of the vehicle.
* vehicleType - The id of the vehicle type.
* primaryFuelLevel - The primary fuel level of the vehicle.

TeleportationEvent
~~~~~~~~~~~~~~~~~~
A special event indicates that a "virtual trip" is finished.

* currentTourMode - The current tour mode.
* departureTime - The departure time.
* person - The id of the person who does the trip.
* arrivalTime - The arrival time.
* startX - X coordinate of the origin.
* startY - Y coordinate of the origin.
* endX - X coordinate of the destination.
* endY - Y coordinate of the destination.

AgencyRevenueEvent
~~~~~~~~~~~~~~~~~~
Indicating a person pays for a transit vehicle at entering the vehicle.

* agencyId - The id of the transit agency.
* revenue - The amount of money payed.

PersonCostEvent
~~~~~~~~~~~~~~~
Indicates how much a trip or a leg costs for a person.
* person - The id of the person.
* mode - The trip/leg mode.
* incentive - Trip incentive for the person.
* tollCost - Toll cost.
* netCost - Total cost of the leg.

ReplanningEvent
~~~~~~~~~~~~~~~
Indicates that a person does replanning of the trip due to a failure (ride-hail reservation failed, missed transit)

* person - The id of the person.
* reason - The reason of replanning.
* startX - X coordinate of the current person location.
* startY - Y coordinate of the current person location.
* endX - X coordinate of the next activity location.
* endY - Y coordinate of the next activity location.

ReserveRideHailEvent
~~~~~~~~~~~~~~~~~~~~
It indicates a request of reservation of a ride-hail vehicle.

* person - The id of the persons who makes the request.
* departTime - The departure time.
* startX - X coordinate of the origin. Coordinates are output in WGS (lat/lon).
* startY - Y coordinate of the origin. Coordinates are output in WGS (lat/lon).
* endX - X coordinate of the destination. Coordinates are output in WGS (lat/lon).
* endY - Y coordinate of the destination. Coordinates are output in WGS (lat/lon).
* requireWheelchair - Whether or not the person requested a wheelchair.

FleetStoredElectricityEvent
~~~~~~~~~~~~~~~~~~~~~~~~~~~
It reports about total stored electricity event of a particular fleet. It happens twice per a simulation: at the beginning
and at the end.

* Time - time of the event.
* Fleet Id - the fleet id.
* Stored Electricity in Joules - Sum of stored electricity (primary fuel) of all the electric vehicles of the fleet.
* Storage Capacity in Joules - Sum of storage capacity (primary fuel) of all the electric vehicles of the fleet.
