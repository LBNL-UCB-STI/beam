package scripts

import java.io.File

import scala.xml.{Node, NodeSeq}
import scala.xml.parsing.ConstructingParser

object HouseHoldsConverter extends XmlFileConverter {

  private case class Household(id: Int, income: Income, members: Seq[Person], vehicles: Seq[Vehicle]) {
    override def toString: String = {
      val membersAsStr = members.mkString(ArrayFieldStartDelimiter, ArrayElementsDelimiter, ArrayFieldFinishDelimiter)
      val vehiclesAsStr = vehicles.mkString(ArrayFieldStartDelimiter, ArrayElementsDelimiter, ArrayFieldFinishDelimiter)
      Seq(id, income, membersAsStr, vehiclesAsStr).mkString(FieldSeparator)
    }
  }

  private case class Income(currency: String, period: String, value: String) {
    override def toString: String = Seq(currency, period, value).mkString(FieldSeparator)
  }

  private case class Vehicle(refId: Int) {
    override def toString: String = refId.toString
  }

  private case class Person(refId: Int) {
    override def toString: String = refId.toString
  }

  private def toHousehold(node: Node): Household = {
    val r = Household(
      id = node.attributes("id").toString.toInt,
      income = toIncome((node \ "income").head),
      members = (node \ "members" \ "personId").map(toPerson),
      vehicles = (node \ "vehicles" \ "vehicleDefinitionId").map(toVehicle)
    )
    println(r)
    r
//    Household(0, Income("USD", "2019", "VALUE"), Seq())
  }

  private def toIncome(node: Node): Income = {
    Income(
      currency = node.attributes("currency").text,
      period = node.attributes("period").text,
      value = node.text
    )
  }

  private def toVehicle(node: Node): Vehicle = {
    Vehicle(
      refId = node.attributes("refId").text.toInt
    )
  }

  private def toPerson(node: Node): Person = {
    Person(
      refId = node.attributes("refId").text.toInt
    )
  }

  override def toCsv(sourceFile: File, destinationFile: File): Iterator[String] = {
    val parser = ConstructingParser.fromFile(sourceFile, preserveWS = true)
    val doc = parser.document()
    val householdNodes: NodeSeq = doc.docElem \\ "households" \ "household"

//    println(householdNodes.take(2))
    // TODO: change header
    val header = Iterator("householdId;income;memberPersonIds;vehicleIds", LineSeparator)
    val contentIterator = householdNodes.toIterator.map(node => toHousehold(node).toString + LineSeparator)
    header ++ contentIterator
  }
}

/*
  <household id="1">
    <members>
      <personId refId="1" />
      <personId refId="2" />
      <personId refId="3" />
    </members>
    <vehicles>
      <vehicleDefinitionId refId="1" />
      <vehicleDefinitionId refId="2" />
    </vehicles>
    <income currency="usd" period="year">50000</income>
  </household>

 */
