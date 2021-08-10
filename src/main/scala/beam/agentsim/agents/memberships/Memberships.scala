package beam.agentsim.agents.memberships

import beam.agentsim.agents.memberships.Memberships.RankedGroup.MemberWithRank
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Id, Identifiable}
import org.matsim.households.Household
import scala.language.implicitConversions

import scala.collection.JavaConverters

object Memberships {

  trait RankedGroup[T <: Identifiable[T], G] {

    val members: Seq[T]
    val rankedMembers: Vector[MemberWithRank[T]]

    def lookupMemberRank(id: Id[T]): Option[Int]

    def sortByRank(r2: MemberWithRank[T], r1: MemberWithRank[T]): Boolean = {
      r1.rank.isEmpty || (r2.rank.isDefined && r1.rank.get > r2.rank.get)
    }

  }

  object RankedGroup {

    case class MemberWithRank[T <: Identifiable[T]](memberId: Id[T], rank: Option[Int])

    implicit def rankedHousehold(household: Household)(implicit
      population: org.matsim.api.core.v01.population.Population
    ): RankedGroup[Person, Household] = new RankedGroup[Person, Household] {

      override def lookupMemberRank(member: Id[Person]): Option[Int] = {
        population.getPersonAttributes.getAttribute(member.toString, "rank") match {
          case rank: Integer =>
            Some(rank)
          case _ =>
            None
        }
      }

      override val members: Seq[Person] =
        JavaConverters.asScalaBuffer(household.getMemberIds).map(population.getPersons.get)

      /**
        * Members sorted by rank.
        */
      override val rankedMembers: Vector[MemberWithRank[Person]] = members.toVector
        .map(mbr => MemberWithRank(mbr.getId, lookupMemberRank(mbr.getId)))
        .sortWith(sortByRank)

    }
  }

}
