Protocols
=========

Because BEAM is implemented using the Actor framework and simulations are executed asynchronously, there are many communications protocols between Actors and Agents that must be specified and followed. The following describes the key protocols with diagrams and narrative.

Trip Planning
-------------

RoutingRequests
~~~~~~~~~~~~~~~

One of the more familiar protocols, any Actor can consult the router service for routing information by sending a RoutingRequest and receiving a RoutingResponse. 

The RoutingRequest message contains:

* Departure Window
* Origin / Destination
* Modes to Consider
* Vehicles to consider (their Id, Location, Mode)

The RoutingResponse message contains:

* A vector of BeamTrips
  
BeamTrips contain:

* A trip classifer (i.e. the overall mode)
* A map with BeamLegs as keys and BeamVehicleAssignments as values

BeamLegs contain:

* Start time
* Mode
* Duration
* Either a BeamStreetPath or BeamTransitSegment

BeamStreetPaths contain:

* Vector of link Ids
* Optional Trajectory (as a vector of Spacetimes)

BeamTransitSegments contain:

* Origin stop 
* Destination stop

BeamVehicleAssignment contains:

* Id of a BeamVehicle
* Boolean asDriver
* Optional PassengerSchedule

Traveling
---------

When a PersonAgent travels, she may transition from being a driver of a vehicle to being a passenger of a vehicle. The protocol for being a driver of a vehicle is listed separately below because that logic is implemented in its own trait (DrivesVehicle) to allow BeamAgents other than PersonAgents to drive vehicles. Some agents (e.g. TransitDrivers) may not be Persons and therefore do not "travel" but they do of course operate a vehilce and move around with it.

Driver
~~~~~~

*Starting Leg*

1. The Driver receives a StartLegTrigger from the Waiting state.
2. The Driver sends NotifyLegStart messages to all passengers in the PassengerSchedule associated with the current BeamLeg.
3. The Driver sends an UpdateTrajectory message to the vehicle she controls.
4. When all expected BoardingConfirmation messages are recieved from the vehicle, the Drive schedules an EndLegTrigger and transitions to the Moving state.

*Ending Leg*

1. The Driver receives an EndLegTrigger from the Moving state.
2. The Driver sends NotifyLegEnd messages to all passengers in the PassengerSchedule associated with the current BeamLeg.
3. When all expected AlightingConfirmation messages are recieved from the vehicle, the Driver proceeds with the following steps.
4. If the Driver has more legs in the PassengerSchedule, she schedules an StartLegTrigger.
5. Else the Driver sends a UnbecomeDriver message to the vehicle and schedules a CompleteDrivingMissionTrigger.
6. The Driver transitions to the Waiting state.

Traveler
~~~~~~~~

*Starting Trip*

1. The PersonAgent receives a PersonDepartureTrigger from the scheduler while in Waiting state. She dequeues the first BeamLeg in her BeamTrip, all BeamTrips start with a leg that is of mode WALK.
2. The PersonAgent sends an BecomeDriver message to her BodyVehicle and passes it a PassengerSchedule that defines her walking leg.
3. The PersonAgent stays in Waiting state and responds with a completion notice scheduling the StartLegTrigger.

*Process Next Leg Method*

The following protocol is used more than once by the traveler so it is defined here as a function with no arguments.

1. If there are no more legs in the BeamTrip, the PersonAgent schedules the EndActivityTrigger and transitions to the InActivity state.
2. Else the PersonAgent checks the BeamVehicleAssignment associated with the BeamTrip.
3. If the PersonAgent is the driver of the next BeamLeg, then she sends an BecomeDriver message to that BeamVehicle and schedules a StartLegTrigger and stays in the current state (which could be either Waitint or Moving depending on the circumstance).

*Complete Driving Mission*

1. The PersonAgent receives a CompleteDrivingMissionTrigger from the scheduler.
2. The PersonAgent executes the ProcessNextLegModule method.

*Notify Start Leg*

1. The PersonAgent receives a NotifyLegStart message from a Driver.
2. The PersonAgent sends an EnterVehicle message to the vehicle contained in the corresponding VehicleAssignment object.
3. The PersonAgent transitions to the Moving state.

*Notify End Leg* 

1. The PersonAgent receives a NotifyLegEnd message from a Driver.
2. If another BeamLeg exists in her BeamTrip AND the BeamVehicle associated with the next BeamLeg is identical to the current BeamVehicle, then she does nothing other than update her internal state to note the end of the leg.
4. Else she sends the current vehicle an ExitVehicle message.
5. The PersonAgent executes the ProcessNextLegModule method.

Household
---------

During initialization, we execute the rank and escort heuristc. Escorts and household vehicles are assigned to members.

1. The PersonAgent retrieves mobility status from her Household using a MobilityStatusInquiry message.
2. Household returns a MobilityStatusReponse message which notifies the person about two topics: a) whether she is an escortee (e.g. a child), an estorter (e.g. a parent), or traveling alone; b) the Id and location of at most one Car and at most one Bike that the person may use for their tour.
3. If the PersonAgent is an escortee, then she will enter a waiting state until she receives a AssignTrip message from her escorter which contains the BeamTrip that she will follow, at which point she schedules a PersonDepartureTrigger.
4. Else the PersonAgent goes through the mode choice process. After choosing a BeamTrip, she sends an appropriate BeamTrip to her escortees using the AssignTrip message.
5. The PersonAgent sends a VehicleConfirmationNotice to the Household, confirming whether or not she is using the Car or Bike. The Household will use this information to offer unused vehicles as options to subsequent household members.

Reserve
~~~~~~~

Enter/Exit
~~~~~~~~~~

Escort
~~~~~~

RideHailing
------------

The process of hailing a ride from a TNC is modeled after the real-world experience:

1. The PersonAgent inquires about the availability and pricing of the service using a RideHailingInquiry message. 
2. The RideHailingManager responds with a RideHailingInquiryResponse. 
3. The PersonAgent may choose to use the ride hailing service in the mode choice process. 
4. The PersonAgent sends a ReserveTaxi message attempting to book the service.
5. The RideHailingManager responds with a ReserveTaxiResponse which either confirms the reservation or notifies that the resource is unavailable.

Inquiry
~~~~~~~

The RideHailingInquiry message contains:

The RideHailingInquiryResponse message contains:

Reserve
~~~~~~~
The ReserveTaxi message contains:

The RideHailingInquiry message contains:

Transit
-------

Transit itineraries are returned by the router in the Trip Planning Protocol. In order to follow one of these itineraries, the PersonAgent must reserve a spot on the transit vehicle according to the following protocol:

1. PersonAgent sends ReservationRequest to the BeamVehicle.
2. The BeamVehicle forwards the reservation request to the Driver of the vehicle. The driver is responsible for managing the schedule and accepting/rejecting reservations from customers.
3. The Driver sends a ReservationConfirmation directly to the PersonAgent.
4. When the BeamVehicle makes it to the confirmed stop for boarding, the Driver sends a BoardingNotice to the PersonAgent.
5. The PersonAgent sends an EnterVehicle message to the BeamVehicle.
6. The BeamVehicle sends a BoardingConfirmation message to the Driver.
7. Also, concurrently, when the BeamVehicle is at the stop, the Driver sends an AlightingNotice to all passengers registered to alight at that stop.
8. Notified passengers send an ExitVehicle message to the BeamVehicle.
9. The BeamVehicle sends an AlightingConfirmation message to the Driver analogous to the boarding process.

Because the reservation process ensures that vehicles will not exceed capacity, the Driver need not send an acknowledgement to the PersonAgent.

Reserve
~~~~~~~

Boarding
~~~~~~~~

Alighting
~~~~~~~~~


Vehicles
--------

Enter/Exit
~~~~~~~~~~

Location 
~~~~~~~~
(course setting and querying)


