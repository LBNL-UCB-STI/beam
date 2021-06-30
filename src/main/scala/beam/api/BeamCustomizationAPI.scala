package beam.api

import beam.agentsim.events.eventbuilder.ComplexEventBuilder
import beam.api.agentsim.agents.ridehail.allocation.{DefaultRepositionManagerFactory, RepositionManagerFactory}
import beam.api.agentsim.agents.ridehail.{DefaultRidehailManagerCustomization, RidehailManagerCustomizationAPI}
import beam.api.agentsim.agents.vehicles.BeamVehicleAfterUseFuelHook
import beam.api.sim.termination.{DefaultTerminationCriterionFactory, TerminationCriterionFactory}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.api.experimental.events.EventsManager

/**
  * Beam customization API and its default implementation.
  */
trait BeamCustomizationAPI {

  /**
    * Allows to provide a custom [[RepositionManagerFactory]].
    * @return
    */
  def getRepositionManagerFactory: RepositionManagerFactory

  /**
    * Allows to provide a custom implementation of [[BeamVehicleAfterUseFuelHook]]
    * @return
    */
  def beamVehicleAfterUseFuelHook: Option[BeamVehicleAfterUseFuelHook]

  /**
    * Allows to provide a custom implementation of [[RidehailManagerCustomizationAPI]]
    * @return
    */
  def getRidehailManagerCustomizationAPI: RidehailManagerCustomizationAPI

  /**
    * Allows to register new/custom events with [[BeamEventsLogger]].
    * @param className
    * @return
    */
  def customEventsLogging(className: String): Option[Class[Event]]

  /**
    * A list of custom [[ComplexEventBuilder]]s can be provided.
    * @param eventsManager
    * @return
    */
  def getEventBuilders(eventsManager: EventsManager): List[ComplexEventBuilder]

  /**
    * Allows to provide a custom [[TerminationCriterionFactory]].
    * @return
    */
  def getTerminationCriterionFactory: TerminationCriterionFactory
}

/**
  * Default implementation of [[BeamCustomizationAPI]].
  */
class DefaultAPIImplementation extends BeamCustomizationAPI {
  override def getRepositionManagerFactory: RepositionManagerFactory = new DefaultRepositionManagerFactory()

  override def beamVehicleAfterUseFuelHook: Option[BeamVehicleAfterUseFuelHook] = None

  override def getRidehailManagerCustomizationAPI: RidehailManagerCustomizationAPI =
    new DefaultRidehailManagerCustomization()

  override def customEventsLogging(className: String): Option[Class[Event]] = None

  override def getEventBuilders(eventsManager: EventsManager): List[ComplexEventBuilder] = List.empty

  override def getTerminationCriterionFactory: TerminationCriterionFactory = new DefaultTerminationCriterionFactory()
}
