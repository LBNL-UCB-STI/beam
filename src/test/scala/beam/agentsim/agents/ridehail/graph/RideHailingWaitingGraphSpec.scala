package beam.agentsim.agents.ridehail.graph
import java.{lang, util}

import beam.agentsim.agents.ridehail.graph.RideHailingWaitingGraphSpec.{RideHailingWaitingGraph, StatsValidationHandler}
import beam.agentsim.events.ModeChoiceEvent
import beam.analysis.plots.RideHailWaitingAnalysis
import beam.integration.IntegrationSpecCommon
import beam.utils.MathUtils
import com.google.inject.Provides
import org.matsim.api.core.v01.events.{Event, PersonEntersVehicleEvent}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.utils.collections.Tuple
import org.scalatest.{Matchers, WordSpecLike}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise

object RideHailingWaitingGraphSpec {

  class RideHailingWaitingGraph(
    waitingComp: RideHailWaitingAnalysis.WaitingStatsComputation with EventAnalyzer
  ) extends BasicEventHandler
      with IterationEndsListener {

    private lazy val railHailingStat =
      new RideHailWaitingAnalysis(waitingComp)

    override def reset(iteration: Int): Unit = {
      railHailingStat.resetStats()
    }

    override def handleEvent(event: Event): Unit = {
      event match {
        case evn
            if evn.getEventType.equalsIgnoreCase(ModeChoiceEvent.EVENT_TYPE)
            || event.getEventType.equalsIgnoreCase(PersonEntersVehicleEvent.EVENT_TYPE) =>
          railHailingStat.processStats(event)
        case evn @ (_: ModeChoiceEvent | _: PersonEntersVehicleEvent) =>
          railHailingStat.processStats(evn)
        case _ =>
      }
      Unit
    }

    override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
      railHailingStat.createGraph(event)
      waitingComp.eventFile(event.getIteration)
    }
  }

  class StatsValidationHandler extends BasicEventHandler {
    private var personLastTime = Map[String, Double]()
    private var waitingTimes = Seq[(Int, Double)]()

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
      if (mode.equalsIgnoreCase("ride_hail")) {
        val personId = evn.getAttributes.get(ModeChoiceEvent.ATTRIBUTE_PERSON_ID)
        val time = evn.getTime
        personLastTime + (personId -> time)
      } else personLastTime
    }

    private def updateCounter(evn: PersonEntersVehicleEvent) = {
      if (evn.getAttributes.get(PersonEntersVehicleEvent.ATTRIBUTE_VEHICLE).contains("rideHailVehicle")) {
        val personId = evn.getPersonId.toString
        val personTime = personLastTime.get(personId)
        personLastTime = personLastTime - personId

        personTime.fold(waitingTimes) { w =>
          val time = w.toInt / 3600
          waitingTimes :+ (time -> (evn.getTime - w) / 60)
        }
      } else waitingTimes
    }

    def counterValue: Seq[(Int, Double)] = waitingTimes
  }
}

class RideHailingWaitingGraphSpec extends WordSpecLike with Matchers with IntegrationSpecCommon {

  "Ride Haling Graph Collected Data" must {

    "contains valid rideHailing stats" in {
      val rideHailWaitingComputation = new RideHailWaitingAnalysis.WaitingStatsComputation with EventAnalyzer {

        private val promise = Promise[util.Map[Integer, util.List[lang.Double]]]()

        override def compute(
          stat: Tuple[util.List[
            lang.Double
          ], util.Map[Integer, util.List[
            lang.Double
          ]]]
        ): Tuple[util.Map[
          Integer,
          util.Map[lang.Double, Integer]
        ], Array[Array[Double]]] = {
          promise.success(stat.getSecond)
          super.compute(stat)
        }

        override def eventFile(iteration: Int): Unit = {
          val handler = new StatsValidationHandler
          parseEventFile(iteration, handler)
          promise.future.foreach { a =>
            val hours = handler.counterValue.groupBy(_._1).map {
              case (k, ks) =>
                k -> MathUtils.roundDouble(ks.map(_._2).sum)
            }

            val modes = a.asScala.map {
              case (i, is) =>
                i.toInt -> MathUtils.roundDouble(is.asScala.map(_.toDouble).sum)
            }.toMap

            hours shouldEqual modes
          }
        }
      }

      GraphRunHelper(
        new AbstractModule() {
          override def install(): Unit = {
            addControlerListenerBinding().to(classOf[RideHailingWaitingGraph])
          }

          @Provides def provideGraph(
            eventsManager: EventsManager
          ): RideHailingWaitingGraph = {
            val graph = new RideHailingWaitingGraph(rideHailWaitingComputation)
            eventsManager.addHandler(graph)
            graph
          }
        },
        baseConfig
      ).run()
    }
  }
}
