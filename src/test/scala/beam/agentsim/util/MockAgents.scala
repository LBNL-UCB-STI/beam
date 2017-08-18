package beam.agentsim.util

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestFSMRef, TestKit}
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.PersonAgent.PersonData
import beam.sim.BeamServices
import org.matsim.core.population.PopulationUtils
import org.matsim.api.core.v01.Id

/**
  * BEAM
  */
object MockAgents {


  val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
  homeActivity.setStartTime(1.0)
  homeActivity.setEndTime(10.0)
  val plan = PopulationUtils.getFactory.createPlan()
  plan.addActivity(homeActivity)
  val data = PersonData()

}
