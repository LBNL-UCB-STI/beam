package beam.agentsim

import akka.actor.Actor

/*
 * Some clarification on nomenclature:
 *
 * Registered: resource is managed by the ResourceManager
 * CheckedIn / CheckedOut: resource is available / unavailable for use
 * InUse / Idle: resource is actively being used or not, but this does not signify available to other users
 *
 * E.g. in a vehicle sharing setting, a vehicle would be Checked Out once the user's session starts (key card is swiped)
 * but if the user parks the vehicle while shopping, it would be Idle. Only when the user checks the vehicle back in
 * does it become available to other users.
 */

/**
  * Responsible for maintaining a grouping of resources and their current locations.
  */
trait ResourceManager {

  this: Actor =>

}
