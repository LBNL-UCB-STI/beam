BeamAgents
======

BEAM is composed of Actors. Some of these Actors are BeamAgents. BeamAgents inherit the Akka FSM trait which provides a domain-specific language for programming agent actions as a finite state machine. 

*How are BeamAgents different from Actors?*

In general, we reserve "BeamAgent" (also referred to as "Agent") for entities in the simulation that exhibit agency. I.e. they don't just change state but they have some degree of control or autonomy over themselves or other Agents. 

A Person or a Manager is a Agent, but a Vehicle is only a tool used by Agents, so it is not a BeamAgent in BEAM.

Also, only BeamAgents can schedule callbacks with the the BeamAgentScheduler. 

Programming a BeamAgent involves constructing a finite state machine and the corresponding logic that responds to Akka messages from different states and then optionally transitions between states.

In order to create blocks of code that execute during a transition from one state to another, use the Akka FAM `onTransition` partial to register a listener to particular transition::

  onTransition {
    case Uninitialized -> Initialized =>
      log.info("Uninit to Init")
  }

This partial can also be used to register listeners that execute during any entry to a state (regardless of where it came from) or upon exit from a state.::

  onTransition {
    case Uninitialized -> _ =>
      log.info("Uninit exit")
  }
  onTransition {
    case _ -> Initialized =>
      log.info("Init entry")
  }

Note that these listeners only execute when a transition occurs from one state to another, self-transitions (using `stay()`) will not invoke these handlers.
