package beam.agentsim.agents.memberships


import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.households.{Household, Households}
import org.matsim.vehicles.Vehicle

import scala.collection.concurrent.TrieMap
import scala.collection.{JavaConverters, mutable}

case class HouseholdMembershipAllocator(households: Households,
                                        implicit val population: org.matsim.api.core.v01.population.Population){

  import beam.agentsim.agents.memberships.Memberships.RankedGroup._

  val memberships: Map[Id[Person], Household] = allocateMembership()

  def lookupMemberRank(id: Id[Person]): Option[Int] = memberships(id).lookupMemberRank(id)

  private def allocateMembership(): Map[Id[Person], Household] = {
    JavaConverters.mapAsScalaMap(households.getHouseholds).flatMap({ case (_, hh) =>
      JavaConverters.asScalaBuffer(hh.getMemberIds).map(personId =>
        personId -> hh)
    }).toMap
  }


  val vehicleAllocationsByRank: TrieMap[Id[Household],mutable.Map[Id[Person], Id[Vehicle]]] = TrieMap[Id[Household],
    mutable
  .Map[Id[Person],Id[Vehicle]]]()


  def lookupVehicleForRankedPerson(person: Id[Person]): Option[Id[Vehicle]]={
    val household = memberships(person)
    vehicleAllocationsByRank.getOrElseUpdate(household.getId,{
      val vehicleRes = mutable.Map[Id[Person],Id[Vehicle]]()

      val householdVehicles = JavaConverters.collectionAsScalaIterable(household.getVehicleIds).toIndexedSeq
      for (i <- householdVehicles.indices.toSet ++ household.rankedMembers.indices.toSet) {
        if (i < householdVehicles.size & i < household.rankedMembers.size) {
          vehicleRes += (household.rankedMembers(i).memberId -> householdVehicles(i))
        }
      }
      vehicleRes
    }).get(person)
  }


}
