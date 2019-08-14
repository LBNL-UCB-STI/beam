package beam.utils.csv.conversion

import java.io.File

import scala.xml.parsing.ConstructingParser
import scala.xml.{Node, NodeSeq}

class HouseholdsXml2CsvConverter(householdAttributesXml: File) extends Xml2CsvFileConverter {

  override val fields: Seq[String] = Seq("householdId", "incomeValue", "locationX", "locationY")

  private type HouseholdId = String
  private type HouseHoldIdToAttributes = Map[HouseholdId, HouseHoldAttributes]

  private case class Household(householdId: HouseholdId, income: Income, locationX: Double, locationY: Double) {
    override def toString: String = Seq(householdId, income.value, locationX, locationY).mkString(FieldSeparator)
  }

  private case class Income(currency: String, period: String, value: String) {
    override def toString: String = Seq(value, currency).mkString(FieldSeparator)
  }

  private case class Vehicle(refId: Int) {
    override def toString: String = refId.toString
  }

  private case class Person(refId: Int) {
    override def toString: String = refId.toString
  }

  private case class HouseHoldAttributes(
    householdId: HouseholdId,
    homeCoordX: Double,
    homeCoordY: Double,
    housingType: String
  ) {
    override def toString: String = {
      val values = Seq(householdId, homeCoordX, homeCoordY, housingType)
      values.mkString(FieldSeparator)
    }
  }

  private def readHouseHoldIdToAttributes(): HouseHoldIdToAttributes = {
    def toHouseholdAttributes(node: Node): HouseHoldAttributes = {
      val attrs = node \\ "attribute"

      def fromSeq(name: String): String = attrs.find(_.attributes("name").text == name).get.text

      HouseHoldAttributes(
        householdId = node.attributes("id").toString,
        homeCoordX = fromSeq("homecoordx").toDouble,
        homeCoordY = fromSeq("homecoordy").toDouble,
        housingType = fromSeq("housingtype")
      )
    }

    val parser = ConstructingParser.fromFile(householdAttributesXml, preserveWS = true)
    val doc = parser.document()
    val householdNodes: NodeSeq = doc.docElem \\ "objectattributes" \ "object"
    householdNodes.toIterator
      .map(node => toHouseholdAttributes(node))
      .map(hha => (hha.householdId, hha))
      .toMap
  }

  private def toHousehold(node: Node, houseHoldIdToAttributes: HouseHoldIdToAttributes): Household = {
    val id = node.attributes("id").toString
    Household(
      householdId = id,
      income = toIncome((node \ "income").head),
      locationX = houseHoldIdToAttributes(id).homeCoordX,
      locationY = houseHoldIdToAttributes(id).homeCoordY
    )
  }

  private def toIncome(node: Node): Income = {
    Income(
      period = node.attributes("period").text.trim,
      value = node.text.trim,
      currency = node.attributes("currency").text.trim
    )
  }

  override def contentIterator(sourceFile: File): Iterator[String] = {
    val parser = ConstructingParser.fromFile(sourceFile, preserveWS = true)
    val doc = parser.document()
    val householdNodes: NodeSeq = doc.docElem \\ "households" \ "household"
    val householdIdsToAttributes = readHouseHoldIdToAttributes()
    householdNodes.toIterator.map(node => toHousehold(node, householdIdsToAttributes).toString + LineSeparator)
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
