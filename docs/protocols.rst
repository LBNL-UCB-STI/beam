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

* A vector of BeamTrips, which contain a vector of BeamLegs

BeamLegs contain:

* Start time
* Mode
* Duration
* Either a BeamStreetPath or BeamTransitSegment

BeamStreetPaths contain:

* Vector of link Ids
* Optional BeamVehicleAssignment
* Optional Trajectory (as a vector of Spacetimes)

BeamVehicleAssignment contains:

* Id of a BeamVehicle
* Boolean asDriver

Traveling
---------

When a PersonAgent travels, she does so as either an ActiveTraveler or a PassiveTraveler. PassiveTravelers are escortees who simply follow the board/alight instructions of their escorter. The following covers both cases.

ActiveTraveler
~~~~~~~~~~~~~~

1. The PersonAgent receives a StartLegTrigger from the scheduler while in Waiting state. She dequeues the first BeamLeg in her BeamTrip.
2. The PersonAgent consults the beamVehicleId in her current BeamLeg. If the Id is None and Mode is WALK and she has no carrier BeamVehicle, she sends an EnterVehicle message (with herself as driver) to her HumanBodyVehicle (if she is escorting other Agents, she sends them notification to enter their body). Otherwise, she compares the beamVehicleId to her outermost carrier vehicle, if the Id does not match, she sends an EnterVehicle message to the beamVehicleId. 
3. 

Household
---------

During initialization, we execute the rank and escort heuristc. Escorts and household vehicles are assigned to members.

At end of activity, 

1. The PersonAgent retrieves mobility status from her Household using a MobilityStatusInquiry message.
2. Household returns a MobilityStatusReponse message which notifies the person about two topics: a) whether she is an escortee (e.g. a child), an estorter (e.g. a parent), or traveling alone; b) the Id and location of at most one Car and at most one Bike that the person may use for their tour.
3. If the PersonAgent is an escortee, then she will enter a waiting state until she receives a BoardingNotice from her escorter.
4. If the PersonAgent is traveling alone or an escortee, then she goes through the mode choice process.
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

