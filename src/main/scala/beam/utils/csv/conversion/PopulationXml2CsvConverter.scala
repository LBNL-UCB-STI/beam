package beam.utils.csv.conversion

import java.io.File

import scala.util.Random
import scala.xml.parsing.ConstructingParser
import scala.xml.{Node, NodeSeq}

class PopulationXml2CsvConverter(householdsXml: File, populationAttributesXml: File) extends Xml2CsvFileConverter {

  override val fields: Seq[String] = Seq("personId", "age", "isFemale", "householdId", "householdRank", "excludedModes")

  private case class Person(
    personId: String,
    age: Int,
    isFemale: Boolean,
    householdId: String,
    householdRank: Int,
    excludedModes: String
  ) {

    override def toString: String =
      Seq(personId, age, isFemale, householdId, householdRank, excludedModes).mkString(FieldSeparator)
  }

  private case class HouseholdMembers(houseHoldId: String, memberIds: Seq[String])

  type MemberId = String
  type HouseholdId = String
  type MemberToHousehold = Map[MemberId, HouseholdId]

  private def readMemberToHousehold(): MemberToHousehold = {
    val parser = ConstructingParser.fromFile(householdsXml, preserveWS = true)
    val doc = parser.document().docElem
    val households: NodeSeq = doc \\ "household"
    val allHouseholdMembers = households.toIterator.map(node => toHouseholdMembers(node))
    allHouseholdMembers.flatMap { relation =>
      relation.memberIds.map(memberId => (memberId, relation.houseHoldId))
    }.toMap
  }

  type RankValue = Int
  private type MemberToRank = Map[MemberId, PersonAttributes]

  private def readMember2Rank(): MemberToRank = {
    val parser = ConstructingParser.fromFile(populationAttributesXml, preserveWS = true)
    val doc = parser.document().docElem
    val people: NodeSeq = doc \\ "object"
    val allPeopleAttributes: Iterator[PersonAttributes] = people.toIterator.map(node => toPersonAttributes(node))
    allPeopleAttributes.map(pa => (pa.objectId, pa)).toMap
  }

  private def toPersonAttributes(node: Node): PersonAttributes = {
    val attrs = node \\ "attribute"

    def fromSeq(name: String): String = attrs.find(_.attributes("name").text == name).get.text

    PersonAttributes(
      objectId = node.attributes("id").toString,
      excludedModes = fromSeq("excluded-modes"),
      rank = fromSeq("rank").toInt
    )
  }

  private case class PersonAttributes(objectId: String, excludedModes: String, rank: Int) {
    override def toString: String = Seq(objectId, excludedModes, rank).mkString(FieldSeparator)
  }

  private def toHouseholdMembers(node: Node): HouseholdMembers = {
    val householdId = node.attributes("id").text
    val memberIds = (node \ "members" \ "personId").map { node =>
      node.attributes("refId").text
    }
    HouseholdMembers(householdId, memberIds)
  }

  private def toPerson(node: Node, memberToHousehold: MemberToHousehold, member2Rank: MemberToRank): Person = {
    val memberId = node.attributes("id").toString
    Person(
      personId = memberId,
      age = 30,
      isFemale = Random.nextInt() > 0,
      householdId = memberToHousehold(memberId),
      householdRank = member2Rank(memberId).rank,
      excludedModes = member2Rank(memberId).excludedModes
    )
  }

  override def contentIterator(sourceFile: File): Iterator[String] = {
    val parser = ConstructingParser.fromFile(sourceFile, preserveWS = true)
    val doc = parser.document().docElem
    val peopleNodes: NodeSeq = doc \\ "population" \ "person"

    val memberToHousehold = readMemberToHousehold()
    val memberToRank: MemberToRank = readMember2Rank()

    peopleNodes.toIterator.map(node => toPerson(node, memberToHousehold, memberToRank).toString + LineSeparator)
  }

}

/*
population.xml
<population>
	<person id="1">
		<plan score="-494.58068848294334" selected="yes">
			<activity type="Home" x="166321.9" y="1568.87" end_time="13:45:00" />
			<activity type="Shopping" x="167138.4" y="1117" end_time="15:49:00" />
			<activity type="Home" x="166321.9" y="1568.87" end_time="18:30:21" />
			<activity type="Shopping" x="166045.2" y="2705.4" end_time="19:43:26" />
			<activity type="Home" x="166321.9" y="1568.87" />
		</plan>
	</person>


households.xml
<households
        xmlns="dtd"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="dtd ../dtd/households_v1.0.xsd">

  <household id="1">
    <members>
      <personId refId="1" />
      <personId refId="2" />
      <personId refId="3" />
    </members>

 */
