BeamAgents
==========

BEAM is composed of Akka Actors, and the entities that exhibit autonomous decision-making within the simulation are implemented as ``BeamAgent`` actors. ``BeamAgent`` inherits the Akka ``FSM`` (Finite State Machine) trait, which provides a domain-specific language for modeling step-by-step agent behavior, state transitions, and responses to simulation events.

*How are BeamAgents different from regular Actors?*

In BEAM, we reserve ``BeamAgent`` (also referred to as an "Agent") for entities that exhibit agency—meaning they actively make choices, follow schedules, or coordinate other agents:

* **Agents**: ``PersonAgent``, ``RideHailAgent``, ``TransitDriverAgent``, and ``FreightCarrier`` / ``PayloadPlansConverter`` drivers are ``BeamAgent`` instances.
* **Service Managers / Routers**: Actors like ``RideHailManager``, ``ParkingManager``, ``ChargingNetworkManager``, and ``BeamRouter`` respond to requests from agents to allocate shared resources or compute routes.
* **Passive Entities**: A ``BeamVehicle`` is a resource used by agents (storing powertrain, fuel/state-of-charge, and seating capacity), not a ``BeamAgent`` itself.

Only ``BeamAgent`` instances (and manager actors) schedule time-stepped triggers with the ``BeamAgentScheduler``, which advances the within-day discrete-event simulation (`AgentSim`) in strict chronological order.

Person Agents
-------------

Each ``PersonAgent`` represents a synthetic traveler executing a daily plan made up of **Activities** (e.g., ``Home``, ``Work``, ``Shopping``) separated by **Legs** (trips between activities).

1. **Departure & Mode Choice**: When an activity ends, the ``PersonAgent`` wakes up at its scheduled departure time. If the trip does not already have a locked mode, the agent queries ``BeamRouter`` for travel alternatives (walk, bike, drive, transit, ride-hail, pooled ride-hail, cav) and queries ``RideHailManager`` or household vehicles for availability. It then evaluates a multinomial logit (or tour-based) mode choice model to select a mode and route.
2. **Trip Execution**: The agent traverses each leg segment—walking to transit stops, boarding/alighting transit vehicles driven by ``TransitDriverAgent``, waiting for a ``RideHailAgent`` pickup, or driving a household ``BeamVehicle``.
3. **Parking & Charging**: When driving a personal or shared vehicle, ``PersonAgent`` interacts with ``ParkingManager`` and ``ChargingNetworkManager`` near the destination to find a parking stall or EV charger, then walks the final egress leg to the next activity.
4. **Between-Iteration Replanning**: Across simulation iterations, agents can mutate or switch between plans in their memory (clearing modes, departure times, or routes) based on experienced utility scores to reach dynamic user equilibrium.

Ride Hail Agents
----------------

Each ``RideHailAgent`` controls an on-demand mobility (TNC / taxi / autonomous fleet) vehicle and remains in continuous communication with the central ``RideHailManager``:

* **Customer Pickup & Dropoff**: Executes single-passenger or pooled multi-passenger dispatches assigned by ``RideHailManager``, emitting ``PathTraversalEvent`` records for both deadheading (empty repositioning/pickup) and revenue service legs.
* **Repositioning**: During idle periods, follows relocation commands from ``RideHailManager`` to move toward areas of anticipated demand.
* **Refueling & Charging**: Monitors vehicle state of charge / fuel level and visits fast-charging depots or public stations via ``ChargingNetworkManager`` when needed.

Transit Driver Agents
---------------------

Each ``TransitDriverAgent`` operates a public transit vehicle (bus, rail, ferry, or tram) along a fixed GTFS schedule and route imported through R5:

* Moves from stop to stop according to scheduled departure times and network link travel times.
* Manages vehicle passenger capacity, allowing ``PersonAgent`` travelers to board and alight at scheduled transit stops.
* Emits ``PathTraversalEvent`` records with transit mode, occupancy, and energy consumption for every stop-to-stop segment.




