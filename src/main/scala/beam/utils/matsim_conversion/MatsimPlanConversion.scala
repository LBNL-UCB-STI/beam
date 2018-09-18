package beam.utils.matsim_conversion

import java.io.File

import org.apache.commons.io.FileUtils

import scala.xml._
import scala.xml.dtd.{DocType, SystemID}
import scala.xml.transform.{RewriteRule, RuleTransformer}

object MatsimPlanConversion {

  def generateSiouxFallsXml(conversionConfig: ConversionConfig): Unit = {
    val populationFile = conversionConfig.populationInput
    val populationDoc = XML.loadFile(populationFile)

    val transformedPopulationDoc = matsimPopulationToBeam(populationDoc)

    val persons = transformedPopulationDoc \\ "person"

    val xmlVehiclesOutput = if (conversionConfig.generateVehicles) {
      generateVehiclesFromPerson(persons)
    } else {
      val vehiclesFile = conversionConfig.vehiclesInput.get
      val vehiclesDoc = XML.loadFile(vehiclesFile)
      matsimVehiclesToBeam(vehiclesDoc)
    }
    val houseHolds =
      generateHouseholds(persons, xmlVehiclesOutput \\ "vehicle", conversionConfig.income)

    val populationAttrs = generatePopulationAttributes(persons)

    val householdAtrrs = generateHouseholdAttributes(persons)

    val householdsAttrDoctype = DocType(
      "objectattributes",
      SystemID("http://www.matsim.org/files/dtd/objectattributes_v1.dtd"),
      Nil
    )
    val populationAttrDoctype =
      DocType("objectattributes", SystemID("../dtd/objectattributes_v1.dtd"), Nil)

    val populationDoctype = DocType("population", SystemID("../dtd/population_v6.dtd"), Nil)

    val transitVehiclesOutput = conversionConfig.scenarioDirectory + "/transitVehicles.xml"
    val populationOutput = conversionConfig.scenarioDirectory + "/population.xml"
    val vehiclesOutput = conversionConfig.scenarioDirectory + "/vehicles.xml"
    val householdsOutput = conversionConfig.scenarioDirectory + "/households.xml"
    val householdAttrsOutput = conversionConfig.scenarioDirectory + "/householdAttributes.xml"
    val populationAttrsOutput = conversionConfig.scenarioDirectory + "/populationAttributes.xml"

//    if(conversionConfig.transitVehiclesInput.isDefined){
//      FileUtils.copyFile(
//        new File(conversionConfig.transitVehiclesInput.get),
//        new File(transitVehiclesOutput))
//    }

    XML.save(transitVehiclesOutput, transitVehiclesDoc, "UTF-8", true)
    XML.save(populationOutput, transformedPopulationDoc, "UTF-8", true, populationDoctype)
    XML.save(vehiclesOutput, xmlVehiclesOutput, "UTF-8", true)
    XML.save(householdsOutput, houseHolds, "UTF-8", true)
    XML.save(householdAttrsOutput, householdAtrrs, "UTF-8", true, householdsAttrDoctype)
    XML.save(populationAttrsOutput, populationAttrs, "UTF-8", true, populationAttrDoctype)
  }

  def generatePopulationAttributes(persons: NodeSeq): Elem = {
    val popAttrs = persons.zipWithIndex map {
      case (_, index) =>
        <object id={s"${index + 1}"}>
        <attribute name="rank" class="java.lang.Integer">1</attribute>
      </object>
    }

    <objectattributes>
      {popAttrs}
    </objectattributes>
  }

  def generateHouseholdAttributes(persons: NodeSeq): Elem = {
    val popAttrs = persons.zipWithIndex map {
      case (person, index) =>
        val homeActivities = (person \\ "activity").filter(
          _.attributes.exists(
            a => "type".equalsIgnoreCase(a.key.toString) && "home".equalsIgnoreCase(a.value.toString)
          )
        )
        for {
          node   <- homeActivities.headOption
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

  def generateHouseholds(persons: NodeSeq, vehicles: NodeSeq, income: HouseholdIncome) = {

    val mPersons = persons.map(Option(_))
    val mVehicles = vehicles.map(Option(_))

    val mPersonsWithVehicles = mPersons.zipAll(mVehicles, None, None)

    val houseHoldChildren = mPersonsWithVehicles.zipWithIndex map {
      case ((mPerson, mVeh), index) =>
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
        <income currency={s"${income.currency}"} period={s"${income.period}"}>{income.value}</income>
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

            val capitalizedAttrs = mapMetaData(uAttrs) {
              case g @ GenAttr(_, key, Text(v), _) if "type".equals(key) =>
                g.copy(value = Text(v.capitalize))
              case o => o
            }

            elem.copy(label = "activity", attributes = capitalizedAttrs)
          case elem: Elem if elem.label == "leg" => NodeSeq.Empty
          case o                                 => o
        }
      }
    }

    val transform = new RuleTransformer(populationTransRule)
    transform(populationDoc)
  }

  def generateVehiclesFromPerson(persons: NodeSeq): Elem = {

    val vehicles = persons.zipWithIndex.map {
      case (_, index) =>
        <vehicle id={s"${index + 1}"} type="1"/>
    }

    <vehicleDefinitions xmlns="dtd"
                        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                        xsi:schemaLocation="dtd ../dtd/vehicleDefinitions_v1.0.xsd">
      <vehicleType id="1">
        <description>Car</description>
        <capacity>
          <seats persons="4"/>
          <standingRoom persons="0"/>
        </capacity>
        <length meter="4.5"/>
        <engineInformation>
          <fuelType>gasoline</fuelType>
          <gasConsumption literPerMeter="0.0001069"/> <!-- == 22 MPG U.S. Avg. Light Duty https://www.rita.dot.gov/bts/sites/rita.dot.gov.bts/files/publications/national_transportation_statistics/html/table_04_23.html -->
        </engineInformation>
      </vehicleType>
      {vehicles}
    </vehicleDefinitions>
  }

  def matsimVehiclesToBeam(vehiclesDoc: Elem): Node = {
    val requiredFieldsForType = List("description", "capacity", "length", "engineInformation")
    val vehicleTypeSeq = vehiclesDoc \\ "vehicleType"

    for {
      vehicleType  <- vehicleTypeSeq
      requiredElem <- requiredFieldsForType if (vehicleType \\ requiredElem).isEmpty
    } yield {
      println(s"Input vehicle data is missing $requiredElem xml element in ${vehicleType.label}")
    }

    val vehicleTransformRule = new RewriteRule {
      override def transform(n: Node): Seq[Node] = n match {
        case elem: Elem if elem.label == "vehicleType" =>
          val child = elem.child
          val filteredChild = child.filter {
            case e: Elem =>
              requiredFieldsForType.contains(e.label)
            case _ => true
          }
          elem.copy(child = filteredChild)
        case m => m
      }
    }
    val transform = new RuleTransformer(vehicleTransformRule)
    transform(vehiclesDoc)
  }

  case class GenAttr(pre: Option[String], key: String, value: Seq[Node], next: MetaData) {
    def toMetaData = Attribute(pre, key, value, next)
  }

  def decomposeMetaData(m: MetaData): Option[GenAttr] = m match {
    case Null => None
    case PrefixedAttribute(pre, key, value, next) =>
      Some(GenAttr(Some(pre), key, value, next))
    case UnprefixedAttribute(key, value, next) =>
      Some(GenAttr(None, key, value, next))
  }

  def unchainMetaData(m: MetaData): Iterable[GenAttr] = m.flatMap(decomposeMetaData)

  def chainMetaData(l: Iterable[GenAttr]): MetaData = l match {
    case Nil          => Null
    case head :: tail => head.copy(next = chainMetaData(tail)).toMetaData
  }

  def mapMetaData(m: MetaData)(f: GenAttr => GenAttr): MetaData =
    chainMetaData(unchainMetaData(m).map(f))

  /*
   * #################################################################################################################
   * Transit vehicles XML data
   * */

  val transitVehiclesDoc: Elem =
    <vehicleDefinitions
    xmlns="http://www.matsim.org/files/dtd"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://www.matsim.org/files/dtd http://www.matsim.org/files/dtd/vehicleDefinitions_v1.0.xsd">
      <vehicleType id="BUS-DEFAULT">
        <capacity>
          <seats persons="50"/>
          <standingRoom persons="50"/>
        </capacity>
        <length meter="12.0"/>
        <engineInformation>
          <fuelType>diesel</fuelType>
          <gasConsumption literPerMeter="0.0007215"/> <!-- == 3.26 MPG U.S. Avg. Transit Bus Fuel Economy https://www.afdc.energy.gov/data/ -->
        </engineInformation>
        <accessTime secondsPerPerson="4"/>
        <egressTime secondsPerPerson="4"/>
      </vehicleType>
      <vehicleType id="SUBWAY-DEFAULT">
        <capacity>
          <seats persons="50"/>
          <standingRoom persons="50"/>
        </capacity>
        <length meter="12.0"/>
        <engineInformation>
          <fuelType>diesel</fuelType>
          <gasConsumption literPerMeter="0.0034163"/> <!--
				== 0.69 MPG which is equivalent consumption of a BART car, derived from:
				https://www.bart.gov/sites/default/files/docs/2015%20Fact%20Sheet.pdf
				https://www.bart.gov/sites/default/files/docs/2015%20Green%20Fact%20Sheet%20-%20electronic_0.pdf
				The estimate is based on BART assumption of 21 MPG car that each passenger would otherwise take and claim of saving 280K gal per day)
				(14 miles/passenger trip) * (421K trips per day) / (21 MPG * 421K trips per day - 280K gallons saved per day)
				-->
        </engineInformation>
        <accessTime secondsPerPerson="1"/>
        <egressTime secondsPerPerson="1"/>
      </vehicleType>
      <vehicleType id="TRAM-DEFAULT">
        <capacity>
          <seats persons="50"/>
          <standingRoom persons="50"/>
        </capacity>
        <length meter="7.5"/>
        <width meter="1.0"/>
        <engineInformation>
          <fuelType>diesel</fuelType>
          <gasConsumption literPerMeter="0.0007215"/> <!-- == 3.26 MPG U.S. Avg. Transit Bus Fuel Economy https://www.afdc.energy.gov/data/ -->
        </engineInformation>
        <accessTime secondsPerPerson="1.0"/>
        <egressTime secondsPerPerson="1.0"/>
        <doorOperation mode="serial"/>
        <passengerCarEquivalents pce="1.0"/>
      </vehicleType>
      <vehicleType id="RAIL-DEFAULT">
        <capacity>
          <seats persons="50"/>
          <standingRoom persons="50"/>
        </capacity>
        <length meter="7.5"/>
        <width meter="1.0"/>
        <engineInformation>
          <fuelType>diesel</fuelType>
          <gasConsumption literPerMeter="0.0007215"/> <!-- == 3.26 MPG U.S. Avg. Transit Bus Fuel Economy https://www.afdc.energy.gov/data/ -->
        </engineInformation>
        <accessTime secondsPerPerson="1.0"/>
        <egressTime secondsPerPerson="1.0"/>
        <doorOperation mode="serial"/>
        <passengerCarEquivalents pce="1.0"/>
      </vehicleType>
      <vehicleType id="CABLE_CAR-DEFAULT">
        <capacity>
          <seats persons="50"/>
          <standingRoom persons="50"/>
        </capacity>
        <length meter="7.5"/>
        <width meter="1.0"/>
        <engineInformation>
          <fuelType>diesel</fuelType>
          <gasConsumption literPerMeter="0.0007215"/> <!-- == 3.26 MPG U.S. Avg. Transit Bus Fuel Economy https://www.afdc.energy.gov/data/ -->
        </engineInformation>
        <accessTime secondsPerPerson="1.0"/>
        <egressTime secondsPerPerson="1.0"/>
        <doorOperation mode="serial"/>
        <passengerCarEquivalents pce="1.0"/>
      </vehicleType>
    </vehicleDefinitions>

}
