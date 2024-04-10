BeamAgents
==========

BEAM is composed of Actors. Some of these Actors are BeamAgents. BeamAgents inherit the Akka FSM trait which provides a domain-specific language for programming agent actions as a finite state machine. 

*How are BeamAgents different from Actors?*

In general, we reserve "BeamAgent" (also referred to as "Agent") for entities in the simulation that exhibit agency. I.e. they don't just change state but they have some degree of control or autonomy over themselves or other Agents. 

A Person or a Manager is an Agent, but a Vehicle is only a tool used by Agents, so it is not a BeamAgent in BEAM.

Also, only BeamAgents can schedule callbacks with the BeamAgentScheduler. So any entity that needs to schedule itself (or be scheduled by other entities) to execute some process at a defined time within the simulation should be designed as a BeamAgent.

Programming a BeamAgent involves constructing a finite state machine and the corresponding logic that responds to Akka messages from different states and then optionally transitions between states.

Person Agents
-------------

Person agents follows their plans for the day. During Mode Choice phase they interact with Router, RideHailManager, ParkingManger.
After choosing a mode for the current trip they follow the trip schedule in order to reach the next activity location.

Ride Hail Agents
----------------

Ride Hail Agents keep the RideHailManger updated with their current location. They do various RideHailManger commands including
repositioning, customer pickup. They refuel their vehicles if they need to.

Transit Driver Agents
---------------------

Transit Driver Agents follows the transit schedules.



