package beam.agentsim.agents.household

import beam.agentsim.agents.household.Memberships.RankedGroup.MemberWithRank
import org.matsim.api.core.v01.population.{Person, Population}
import org.matsim.api.core.v01.{Id, Identifiable, Scenario}
import org.matsim.households.Household

import scala.collection.JavaConverters

object Memberships {


  case class HouseholdMemberships(scenario: Scenario) {

    val memberships: Map[Id[Person], Household] = allocateMembership()

    implicit val population:Population = scenario.getPopulation

    def allocateMembership(): Map[Id[Person], Household] = {
      JavaConverters.mapAsScalaMap(scenario.getHouseholds.getHouseholds).flatMap({ case (_, hh) =>
        JavaConverters.asScalaBuffer(hh.getMemberIds).map(personId =>
          personId -> hh)
      }).toMap
    }

  }


  trait RankedGroup[T <: Identifiable[T], G] {

    def lookupMemberRank(value: Id[T]): Option[Int]

    val members: Seq[T]

    val rankedMembers: Vector[MemberWithRank[T]]

    def sortByRank(r2: MemberWithRank[T], r1: MemberWithRank[T]): Boolean = {
      r1.rank.isEmpty || (r2.rank.isDefined && r1.rank.get > r2.rank.get)
    }

  }

  object RankedGroup {
    case class MemberWithRank[T <: Identifiable[T]](memberId: Id[T], rank: Option[Int])

    implicit def rankedHousehold(household: Household)(implicit population: Population): RankedGroup[Person,Household] = new RankedGroup[Person,Household] {

      override def lookupMemberRank(member: Id[Person]): Option[Int] = {
        population.getPersonAttributes.getAttribute(member.toString, "rank")
        match {
          case rank: Integer =>
            Some(rank)
          case _ =>
            None
        }
      }

      override val members: Seq[Person] = JavaConverters.asScalaBuffer(household.getMemberIds).map(population.getPersons
        .get(_))

      /**
        * Members sorted by rank.
        */
      override val rankedMembers: Vector[MemberWithRank[Person]] = members.toVector.map(memb => MemberWithRank(memb
        .getId,
        lookupMemberRank
      (memb.getId))).sortWith(sortByRank)

    }

  }




}



