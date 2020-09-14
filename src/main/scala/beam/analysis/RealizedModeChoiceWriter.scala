package beam.analysis

import java.util

import beam.agentsim.events.handling.{BeamEventsLoggingSettings, BeamEventsWriterCSV}
import beam.agentsim.events.{ModeChoiceEvent, ReplanningEvent}
import beam.sim.BeamServices
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.core.controler.events.{IterationEndsEvent, IterationStartsEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, IterationStartsListener}
import org.matsim.core.events.handler.BasicEventHandler

import scala.collection.mutable

/**
  * Listens for [[ModeChoiceEvent]] and [[ReplanningEvent]] to calculate
  * realized mode choices.
  * Writes realizedModeChoice.csv.gz to iteration directory.
  */
class RealizedModeChoiceWriter(beamServices: BeamServices)
    extends BasicEventHandler
    with IterationEndsListener
    with IterationStartsListener
    with LazyLogging {

  private var csvWriter: BeamEventsWriterCSV = _
  private var csvFilePath: String = _

  /** Collects a previous ModeChoiceEvent for each person */
  private val personIdPrevMCE = mutable.Map.empty[Id[Person], Option[ModeChoiceEvent]]

  private val realizedModeChoiceFileName: String = "realizedModeChoice.csv.gz"

  override def handleEvent(event: Event): Unit = {
    event match {
      case modeChoiceEvent: ModeChoiceEvent =>
        val personId = modeChoiceEvent.personId
        val prevMCE = personIdPrevMCE.get(personId).flatten

        prevMCE match {
          case Some(realizedMCE @ _) =>
            // second (current) ModeChoiceEvent makes previous one "realized"
            csvWriter.writeEvent(realizedMCE)
            personIdPrevMCE(personId) = None

          case _ =>
            personIdPrevMCE(personId) = Some(modeChoiceEvent)
        }

      case replanningEvent: ReplanningEvent =>
        // ReplanningEvent discards previous history
        personIdPrevMCE(replanningEvent.getPersonId) = None

      case _ =>
    }
  }

  override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
    csvFilePath = beamServices.matsimServices.getControlerIO.getIterationFilename(
      event.getIteration,
      realizedModeChoiceFileName
    )

    // writeHeader is invoked in constructor
    csvWriter = new BeamEventsWriterCSV(
      csvFilePath,
      BeamEventsLoggingSettings.create(
        clazz => clazz.equals(classOf[ModeChoiceEvent]),
        () => java.util.Collections.singleton(classOf[ModeChoiceEvent]),
        (_: Event, eventAttributes: util.Map[String, String]) => eventAttributes.keySet()
      ),
      beamServices,
      classOf[ModeChoiceEvent]
    )
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    try {
      personIdPrevMCE.values.foreach {
        case Some(modeChoiceEvent @ _) =>
          csvWriter.handleEvent(modeChoiceEvent)

        case _ =>
      }
    } catch {
      case e: Throwable =>
        logger.error(s"Failed to write $realizedModeChoiceFileName", e)
    } finally {
      try {
        csvWriter.closeFile()
      } catch { case _: Throwable => }
    }
  }
}
