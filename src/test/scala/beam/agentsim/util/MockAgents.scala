package beam.agentsim.util

import beam.agentsim.agents.PersonAgent.PersonData
import org.matsim.api.core.v01.Id
import org.matsim.core.population.PopulationUtils

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
