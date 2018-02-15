package beam.agentsim.agents.memberships


import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.households.{Household, Households}

import scala.collection.JavaConverters

case class HouseholdMembershipAllocator(households: Households,
                                        implicit val population: org.matsim.api.core.v01.population.Population) {

  import beam.agentsim.agents.memberships.Memberships.RankedGroup._

  val memberships: Map[Id[Person], Household] = allocateMembership()

  private val peopleMap = JavaConverters.mapAsScalaMap(population.getPersons).toMap

  def lookupMemberRank(id: Id[Person]): Option[Int] = memberships(id).lookupMemberRank(id)

  private def allocateMembership(): Map[Id[Person], Household] = {
    JavaConverters.mapAsScalaMap(households.getHouseholds).flatMap({ case (_, hh) =>
      JavaConverters.asScalaBuffer(hh.getMemberIds).map(personId =>
        personId -> hh)
    }).toMap
  }

}
