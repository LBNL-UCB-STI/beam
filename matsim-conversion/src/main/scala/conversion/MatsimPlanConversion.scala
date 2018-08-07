package conversion

import scala.xml.{Elem, Node, NodeSeq, XML}
import scala.xml.transform.{RewriteRule, RuleTransformer}
import scala.xml.dtd.{DocType, SystemID}

object MatsimPlanConversion {
  def generateSiouxFallsXml(conversionConfig: ConversionConfig): Unit = {
    val populationFile = conversionConfig.populationInput
    val populationDoc = XML.loadFile(populationFile)

    val transformedPopulationDoc = matsimPopulationToBeam(populationDoc)

    val vehiclesFile = conversionConfig.vehiclesInput
    val vehiclesDoc = XML.loadFile(vehiclesFile)
    val transformedVehiclesDoc = matsimVehiclesToBeam(vehiclesDoc)

    val persons = transformedPopulationDoc \\ "person"

    val houseHolds = generateHouseholds(persons, transformedVehiclesDoc \\ "vehicle")

    val populationAttrs = generatePopulationAttributes(persons)

    val householdAtrrs = generateHouseholdAttributes(persons)

    val householdsAttrDoctype = DocType("objectattributes",
      SystemID("http://www.matsim.org/files/dtd/objectattributes_v1.dtd"),
      Nil)
    val populationAttrDoctype = DocType("objectattributes",
      SystemID("../dtd/objectattributes_v1.dtd"),
      Nil)

    val populationDoctype = DocType("population",
      SystemID("../dtd/population_v6.dtd"),
      Nil)

    val populationOutput = conversionConfig.outputDirectory + "/population.xml"
    val vehiclesOutput = conversionConfig.outputDirectory + "/vehicles.xml"
    val householdsOutput = conversionConfig.outputDirectory + "/households.xml"
    val householdAttrsOutput = conversionConfig.outputDirectory + "/householdAttributes.xml"
    val populationAttrsOutput = conversionConfig.outputDirectory + "/populationAttributes.xml"


    XML.save(populationOutput, transformedPopulationDoc, "UTF-8", true, populationDoctype)
    XML.save(vehiclesOutput, transformedVehiclesDoc, "UTF-8", true)
    XML.save(householdsOutput, houseHolds, "UTF-8", true)
    XML.save(householdAttrsOutput, householdAtrrs, "UTF-8", true, householdsAttrDoctype)
    XML.save(populationAttrsOutput, populationAttrs, "UTF-8", true, populationAttrDoctype)
  }

  def generatePopulationAttributes(persons: NodeSeq) = {
    val popAttrs = persons.zipWithIndex map { case (_, index) =>
      <object id={s"${index + 1}"}>
        <attribute name="rank" class="java.lang.Integer">1</attribute>
      </object>
    }

    <objectattributes>
      {popAttrs}
    </objectattributes>
  }

  def generateHouseholdAttributes(persons: NodeSeq): Elem = {
    val popAttrs = persons.zipWithIndex map { case (person, index) =>
      val homeActivities = (person \\ "activity").filter (_.attributes.exists(
        a => "type".equalsIgnoreCase(a.key.toString) && "home".equalsIgnoreCase(a.value.toString))
      )
      for {
        node <- homeActivities.headOption
        xValue <- node.attribute("x").map(_.toString)
        yValue <- node.attribute("y").map(_.toString)
      } yield {
        <object id={s"${index + 1}"}>
          <attribute name="homecoordx" class="java.lang.Double">{xValue}</attribute>
          <attribute name="homecoordy" class="java.lang.Double">{yValue}</attribute>
          <attribute name="housingtype" class="java.lang.String">House</attribute>
        </object>
      }
    }
    <objectattributes>
      {popAttrs.map(_.getOrElse(NodeSeq.Empty))}
    </objectattributes>
  }

  def generateHouseholds(persons: NodeSeq, vehicles: NodeSeq) = {

    val mPersons = persons.map(Option(_))
    val mVehicles = vehicles.map(Option(_))

    val mPersonsWithVehicles = mPersons.zipAll(mVehicles, None, None)

    val houseHoldChildren = mPersonsWithVehicles.zipWithIndex map { case ((mPerson, mVeh), index) =>
      <household id={s"${index + 1}"}>
        <members>
          {
          (for{
            n <- mPerson
            personId <- n.attribute("id").map(_.toString)
          } yield {
              <personId refId={s"$personId"} />
          }).getOrElse(NodeSeq.Empty)
          }
        </members>
        <vehicles>
          {
          (for{
            n <- mVeh
            vehId <- n.attribute("id").map(_.toString)
          } yield {
              <vehicleDefinitionId refId={s"$vehId"} />
          }).getOrElse(NodeSeq.Empty)
          }
        </vehicles>
        <income currency="usd" period="year">50000</income>
      </household>
    }

    val houseHoldXml =
      <households
      xmlns="dtd"
      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      xsi:schemaLocation="dtd ../dtd/households_v1.0.xsd">
        {houseHoldChildren}
      </households>

    houseHoldXml
  }

  def matsimPopulationToBeam(populationDoc: Elem): Node = {
    val populationTransRule = new RewriteRule {
      override def transform(n: Node): Seq[Node] = {
        n match {
          case elem: Elem if elem.label == "person" =>
            val attrs = elem.attributes
            val filteredAttrs = attrs.filter(_.key.equals("id"))
            elem.copy(attributes = filteredAttrs)
          case elem: Elem if elem.label == "act" =>
            val attrs = elem.attributes
            val uAttrs = attrs.remove("facility")
            elem.copy(label = "activity", attributes = uAttrs)
          case elem: Elem if elem.label == "leg" => NodeSeq.Empty
          case o => o
        }
      }
    }

    val transform = new RuleTransformer(populationTransRule)
    transform(populationDoc)
  }

  def matsimVehiclesToBeam(vehiclesDoc: Elem): Node = {
    val vehicleTransformRule = new RewriteRule {
      override def transform(n: Node): Seq[Node] = n match{
        case elem: Elem if elem.label == "vehicleType" =>
          val child = elem.child
          val filteredChild = child.filter{
            case e: Elem =>
              e.label == "capacity" || e.label == "length"
            case _ => true
          }
          //TODO
          val description = elem.attribute("id").map(_.toString()).getOrElse("CAR")
          val descriptionXml = <description>{description}</description>
          val engineInfoXml =
            <engineInformation>
              <fuelType>gasoline</fuelType>
              <gasConsumption literPerMeter="0.0001069"/>
            </engineInformation>
          elem.copy(child = (filteredChild ++ descriptionXml ++ engineInfoXml))
        case m => m
      }
    }
    val transform = new RuleTransformer(vehicleTransformRule)
    transform(vehiclesDoc)
  }
}
