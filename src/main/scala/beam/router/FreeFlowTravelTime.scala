package beam.router

import java.util.concurrent.TimeUnit

import beam.router.BeamRouter.{UpdateTravelTimeLocal, UpdateTravelTimeRemote}
import beam.sim.{BeamServices, BeamWarmStart}
import beam.utils.TravelTimeCalculatorHelper
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.Person
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup
import org.matsim.core.router.util.TravelTime
import org.matsim.vehicles.Vehicle

class FreeFlowTravelTime extends TravelTime with LazyLogging {
  override def getLinkTravelTime(link: Link, time: Double, person: Person, vehicle: Vehicle): Double =
    link.getLength / link.getFreespeed
}

object FreeFlowTravelTime {

  def initializeRouterFreeFlow(beamServices: BeamServices, scenario: Scenario): Unit = {
    val maxHour = TimeUnit.SECONDS.toHours(new TravelTimeCalculatorConfigGroup().getMaxTime).toInt
    val beamRouter = beamServices.beamRouter

    val travelTime = new FreeFlowTravelTime
    beamRouter ! UpdateTravelTimeLocal(travelTime)

    BeamWarmStart.updateRemoteRouter(scenario, travelTime, maxHour, beamRouter)
  }

}
