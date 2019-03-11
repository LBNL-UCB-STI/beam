package beam.agentsim.agents.ridehail.graph
import java.{lang, util}

import beam.agentsim.agents.ridehail.graph.PersonTravelTimeStatsGraphSpec.{
  PersonTravelTimeStatsGraph,
  StatsValidationHandler
}
import beam.analysis.plots.PersonTravelTimeAnalysis
import beam.integration.IntegrationSpecCommon
import beam.utils.MathUtils
import com.google.inject.Provides
import org.matsim.api.core.v01.events.{Event, PersonArrivalEvent, PersonDepartureEvent}
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

object PersonTravelTimeStatsGraphSpec {

  class PersonTravelTimeStatsGraph(
    computation: PersonTravelTimeAnalysis.PersonTravelTimeComputation with EventAnalyzer
  ) extends BasicEventHandler
      with IterationEndsListener {

    private lazy val personTravelTimeStats =
      new PersonTravelTimeAnalysis(computation, true)

    override def reset(iteration: Int): Unit = {
      personTravelTimeStats.resetStats()
    }

    override def handleEvent(event: Event): Unit = {
      event match {
        case evn
            if evn.getEventType.equalsIgnoreCase(PersonDepartureEvent.EVENT_TYPE)
            || evn.getEventType.equalsIgnoreCase(PersonArrivalEvent.EVENT_TYPE) =>
          personTravelTimeStats.processStats(event)
        case evn @ (_: PersonArrivalEvent | _: PersonDepartureEvent) =>
          personTravelTimeStats.processStats(evn)
        case _ =>
      }
      Unit
    }

    override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
      personTravelTimeStats.createGraph(event)
      computation.eventFile(event.getIteration)
    }
  }

  class StatsValidationHandler extends BasicEventHandler {

    private var personTravelTime = Map[(String, String), Double]()
    private var counter = Seq[(String, Double)]()

    override def handleEvent(event: Event): Unit = event match {
      case evn if evn.getEventType.equalsIgnoreCase(PersonDepartureEvent.EVENT_TYPE) =>
        personTravelTime = updateDepartureTime(evn.asInstanceOf[PersonDepartureEvent])
      case evn if evn.getEventType.equalsIgnoreCase(PersonArrivalEvent.EVENT_TYPE) =>
        counter = updateCounterTime(evn.asInstanceOf[PersonArrivalEvent])
      case evn: PersonArrivalEvent =>
        counter = updateCounterTime(evn)
      case evn: PersonDepartureEvent =>
        personTravelTime = updateDepartureTime(evn)
      case _ =>
    }

    private def updateDepartureTime(evn: PersonDepartureEvent): Map[(String, String), Double] = {
      val mode = evn.getLegMode
      val personId = evn.getPersonId.toString
      val time = evn.getTime
      personTravelTime + ((mode, personId) -> time)
    }

    private def updateCounterTime(evn: PersonArrivalEvent): Seq[(String, Double)] = {
      val mode = evn.getLegMode
      val personId = evn.getPersonId.toString
      val modeTime = personTravelTime
        .get(mode -> personId)
        .map { time =>
          val travelTime = (evn.getTime - time) / 60
          mode -> travelTime
        }
      personTravelTime = personTravelTime - (mode -> personId)
      modeTime.fold(counter)(items => counter :+ items)
    }

    def counterValue: Seq[(String, Double)] = counter

    def isEmpty: Boolean = personTravelTime.isEmpty
  }
}

class PersonTravelTimeStatsGraphSpec extends WordSpecLike with Matchers with IntegrationSpecCommon {

  "Person Travel Time Graph Collected Data" must {

    "contains valid travel time stats" ignore {
      val travelTimeComputation = new PersonTravelTimeAnalysis.PersonTravelTimeComputation with EventAnalyzer {

        private val promise = Promise[util.Map[String, util.Map[Integer, util.List[lang.Double]]]]()

        override def compute(
          stat: util.Map[
            String,
            util.Map[Integer, util.List[lang.Double]]
          ]
        ): Tuple[util.List[String], Tuple[Array[Array[Double]], util.Map[String, lang.Double]]] = {
          promise.success(stat)
          super.compute(stat)
        }

        override def eventFile(iteration: Int): Unit = {
          val handler = new StatsValidationHandler
          parseEventFile(iteration, handler)
          promise.future.foreach { a =>
            val modes = handler.counterValue
              .groupBy(_._1)
              .map {
                case (mode, ms) =>
                  mode -> MathUtils.roundDouble(ms.map(_._2).sum)
              }

            val all = a.asScala.map {
              case (mode, times) =>
                mode -> MathUtils.roundDouble(times.asScala.values.flatMap(_.asScala).map(_.toDouble).sum)
            }
            handler.isEmpty shouldBe true
            modes shouldEqual all
          }
        }
      }

      GraphRunHelper(
        new AbstractModule() {
          override def install(): Unit = {
            addControlerListenerBinding().to(classOf[PersonTravelTimeStatsGraph])
          }

          @Provides def provideGraph(
            eventsManager: EventsManager
          ): PersonTravelTimeStatsGraph = {
            val graph = new PersonTravelTimeStatsGraph(travelTimeComputation)
            eventsManager.addHandler(graph)
            graph
          }
        },
        baseConfig
      ).run()
    }
  }
}
