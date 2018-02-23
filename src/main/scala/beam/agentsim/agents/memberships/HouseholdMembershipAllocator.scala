package beam.agentsim.agents.memberships

import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.households.Household

import scala.collection.JavaConverters

case class HouseholdMembershipAllocator(scenario: Scenario) {
    import beam.agentsim.agents.memberships.Memberships.RankedGroup._

    implicit val population:org.matsim.api.core.v01.population.Population = scenario.getPopulation

    val memberships: Map[Id[Person], Household] = allocateMembership()

    def allocateMembership(): Map[Id[Person], Household] = {
      JavaConverters.mapAsScalaMap(scenario.getHouseholds.getHouseholds).flatMap({ case (_, hh) =>
        JavaConverters.asScalaBuffer(hh.getMemberIds).map(personId =>
          personId -> hh)
      }).toMap
    }

    def lookupMemberRank(id: Id[Person]):Option[Int] = memberships(id).lookupMemberRank(id)

  }
