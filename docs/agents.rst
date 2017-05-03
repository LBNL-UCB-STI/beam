Agents
======

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
