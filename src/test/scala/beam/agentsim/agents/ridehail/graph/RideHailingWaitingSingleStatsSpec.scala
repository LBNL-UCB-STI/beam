package beam.agentsim.agents.ridehail.graph
import java.{lang, util}

import beam.agentsim.agents.ridehail.graph.RideHailingWaitingSingleStatsSpec.{
  RideHailingWaitingSingleGraph,
  StatsValidationHandler
}
import beam.agentsim.events.ModeChoiceEvent
import beam.analysis.plots.RideHailingWaitingSingleStats
import beam.integration.IntegrationSpecCommon
import beam.sim.BeamServices
import com.google.inject.Provides
import org.matsim.api.core.v01.events.{Event, GenericEvent, PersonEntersVehicleEvent}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.events.handler.BasicEventHandler
import org.scalatest.{Matchers, WordSpecLike}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise
import scala.math.BigDecimal.RoundingMode

object RideHailingWaitingSingleStatsSpec {

  class RideHailingWaitingSingleGraph(
    beamServices: BeamServices,
    waitingComp: RideHailingWaitingSingleStats.RideHailingWaitingSingleComputation with EventAnalyzer
  ) extends BasicEventHandler
      with IterationEndsListener {

    private val railHailingSingleStat =
      new RideHailingWaitingSingleStats(beamServices.beamConfig, waitingComp)

    override def reset(iteration: Int): Unit = {
      railHailingSingleStat.resetStats()
    }

    override def handleEvent(event: Event): Unit = {
      event match {
        case evn
            if evn.getEventType.equalsIgnoreCase(ModeChoiceEvent.EVENT_TYPE)
            || evn.getEventType.equalsIgnoreCase(PersonEntersVehicleEvent.EVENT_TYPE) =>
          railHailingSingleStat.processStats(evn)
        case evn @ (_: ModeChoiceEvent | _: PersonEntersVehicleEvent) =>
          railHailingSingleStat.processStats(evn)
        case _ =>
      }
      Unit
    }

    override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
      railHailingSingleStat.createGraph(event)
      waitingComp.eventFile(event.getIteration)
    }
  }

  class StatsValidationHandler extends BasicEventHandler {

    private var personLastTime = Map[String, Double]()
    private var waitingTimes = Seq[Double]()

    override def handleEvent(event: Event): Unit = event match {
      case evn: ModeChoiceEvent =>
        personLastTime = updatePersonTime(evn)
      case evn: PersonEntersVehicleEvent =>
        waitingTimes = updateCounter(evn)
      case evn if evn.getEventType.equalsIgnoreCase(ModeChoiceEvent.EVENT_TYPE) =>
        personLastTime = updatePersonTime(evn)
      case evn if evn.getEventType.equalsIgnoreCase(PersonEntersVehicleEvent.EVENT_TYPE) =>
        waitingTimes = updateCounter(evn.asInstanceOf[PersonEntersVehicleEvent])
      case _ =>
    }

    private def updatePersonTime(evn: Event): Map[String, Double] = {
      val mode = evn.getAttributes.get(ModeChoiceEvent.ATTRIBUTE_MODE)
      if (mode.equalsIgnoreCase("ride_hailing")) {
        val personId = evn.getAttributes.get(ModeChoiceEvent.ATTRIBUTE_PERSON_ID)
        val time = evn.getTime
        personLastTime + (personId -> time)
      } else personLastTime
    }

    private def updateCounter(evn: PersonEntersVehicleEvent) = {
      val personId = evn.getPersonId.toString
      val personTime = personLastTime.get(personId)
      personLastTime = personLastTime - personId
      personTime.fold(waitingTimes)(w => waitingTimes :+ (evn.getTime - w))
    }

    def counterValue: Seq[Double] = waitingTimes
  }
}

class RideHailingWaitingSingleStatsSpec
    extends WordSpecLike
    with Matchers
    with IntegrationSpecCommon {

  "Ride Haling Single Graph Collected Data" must {

    "contains valid rideHailing single stats" in {
      val rideHailingComputation =
        new RideHailingWaitingSingleStats.RideHailingWaitingSingleComputation with EventAnalyzer {

          private val promise = Promise[util.Map[Integer, lang.Double]]()

          override def compute(
            stat: util.Map[Integer, lang.Double]
          ): Array[Array[Double]] = {
            promise.success(stat)
            super.compute(stat)
          }

          override def eventFile(iteration: Int): Unit = {
            val handler = new StatsValidationHandler
            parseEventFile(iteration, handler)
            promise.future.foreach { a =>
              val modes =
                BigDecimal(handler.counterValue.sum).setScale(3, RoundingMode.HALF_UP).toDouble
              val all = BigDecimal(a.asScala.values.map(_.toDouble).sum)
                .setScale(3, RoundingMode.HALF_UP)
                .toDouble

              modes shouldEqual all
            }
          }
        }
      GraphRunHelper(
        new AbstractModule() {
          override def install(): Unit = {
            addControlerListenerBinding().to(classOf[RideHailingWaitingSingleGraph])
          }

          @Provides def provideGraph(
            beamServices: BeamServices,
            eventsManager: EventsManager
          ): RideHailingWaitingSingleGraph = {
            val graph = new RideHailingWaitingSingleGraph(beamServices, rideHailingComputation)
            eventsManager.addHandler(graph)
            graph
          }
        },
        baseConfig
      ).run()
    }
  }
}
