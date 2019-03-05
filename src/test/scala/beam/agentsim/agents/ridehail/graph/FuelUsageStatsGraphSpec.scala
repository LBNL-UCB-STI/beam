package beam.agentsim.agents.ridehail.graph
import java.{lang, util}

import beam.agentsim.agents.ridehail.graph.FuelUsageStatsGraphSpec.{FuelUsageStatsGraph, StatsValidationHandler}
import beam.agentsim.events.PathTraversalEvent
import beam.analysis.PathTraversalSpatialTemporalTableGenerator
import beam.analysis.plots.FuelUsageAnalysis
import beam.integration.IntegrationSpecCommon
import beam.utils.MathUtils
import com.google.inject.Provides
import org.matsim.api.core.v01.events.Event
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

object FuelUsageStatsGraphSpec {

  class FuelUsageStatsGraph(compute: FuelUsageAnalysis.FuelUsageStatsComputation with EventAnalyzer)
      extends BasicEventHandler
      with IterationEndsListener {

    private lazy val fuelUsageStats =
      new FuelUsageAnalysis(compute, true)

    override def reset(iteration: Int): Unit = {
      fuelUsageStats.resetStats()
    }

    override def handleEvent(event: Event): Unit = {
      event match {
        case evn if evn.getEventType.equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE) =>
          fuelUsageStats.processStats(event)
        case evn: PathTraversalEvent =>
          fuelUsageStats.processStats(evn)
        case _ =>
      }
      Unit
    }

    override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
      fuelUsageStats.createGraph(event)
      compute.eventFile(event.getIteration)
    }
  }

  class StatsValidationHandler extends BasicEventHandler {

    private var counter = Seq[(String, Double)]()

    override def handleEvent(event: Event): Unit = event match {
      case evn if evn.getEventType.equalsIgnoreCase(PathTraversalEvent.EVENT_TYPE) =>
        counter = updateFuel(evn)
      case evn: PathTraversalEvent =>
        counter = updateFuel(evn)
      case _ =>
    }

    private def updateFuel(evn: Event): Seq[(String, Double)] = {
      val vehicleType = evn.getAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_TYPE)
      val originalMode = evn.getAttributes.get(PathTraversalEvent.ATTRIBUTE_MODE)
      val vehicleId = evn.getAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID)
      val lengthInMeters = evn.getAttributes.get(PathTraversalEvent.ATTRIBUTE_LENGTH).toDouble
      val fuelString = evn.getAttributes.get(PathTraversalEvent.ATTRIBUTE_PRIMARY_FUEL)

      val mode =
        if (originalMode.equalsIgnoreCase("car") && vehicleId.contains("rideHailVehicle"))
          "rideHail"
        else
          originalMode

      counter :+ (mode, fuelString.toDouble)
    }

    def counterValue = counter
  }
}

class FuelUsageStatsGraphSpec extends WordSpecLike with Matchers with IntegrationSpecCommon {
  "Fuel Usage Collected Data" must {

    "contains valid fuel usage stats" ignore {
      val fuelUsageComputation = new FuelUsageAnalysis.FuelUsageStatsComputation with EventAnalyzer {

        private val promise = Promise[java.util.Map[Integer, java.util.Map[String, lang.Double]]]()

        override def compute(
          stat: Tuple[util.Map[
            Integer,
            util.Map[String, lang.Double]
          ], util.Set[String]]
        ): Array[Array[Double]] = {
          promise.success(stat.getFirst)
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

            val all = a.asScala.values
              .flatMap(_.asScala)
              .groupBy(_._1)
              .map {
                case (s, is) =>
                  s -> MathUtils.roundDouble(is.map(_._2.toDouble).sum)
              }
            modes shouldEqual all
          }
        }
      }
      GraphRunHelper(
        new AbstractModule() {
          override def install(): Unit = {
            addControlerListenerBinding().to(classOf[FuelUsageStatsGraph])
          }

          @Provides def provideGraph(
            eventsManager: EventsManager
          ): FuelUsageStatsGraph = {
            val graph = new FuelUsageStatsGraph(fuelUsageComputation)
            eventsManager.addHandler(graph)
            graph
          }
        },
        baseConfig
      ).run()
    }
  }
}
