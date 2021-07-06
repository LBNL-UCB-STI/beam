package beam.utils.matsim_conversion

import org.matsim.api.core.v01.Id

import java.io.{FileOutputStream, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream
import scala.reflect.ClassTag
import scala.xml._
import scala.xml.dtd.{DocType, SystemID}
import scala.xml.transform.{RewriteRule, RuleTransformer}

object MatsimPlanConversion {
  val UTF8: String = StandardCharsets.UTF_8.name()

  def generateScenarioData(conversionConfig: ConversionConfig): Unit = {
    val populationFile = conversionConfig.populationInput
    val populationDoc = XML.loadFile(populationFile)

    val transformedPopulationDoc = matsimPopulationToBeam(populationDoc)

    val persons = transformedPopulationDoc \\ "person"

    //Generate vehicles data
    VehiclesDataConversion.generateFuelTypesDefaults(conversionConfig.scenarioDirectory)
    val vehiclesWithTypeId = if (conversionConfig.generateVehicles) {
      VehiclesDataConversion.generateVehicleTypesDefaults(
        conversionConfig.scenarioDirectory,
        VehiclesDataConversion.beamVehicleTypes
      )
      VehiclesDataConversion.generateVehiclesDataFromPersons(persons, conversionConfig)
    } else {
      val vehiclesFile = conversionConfig.vehiclesInput.get
      val vehiclesDoc = XML.loadFile(vehiclesFile)
      val vehicleTypes = VehiclesDataConversion.generateVehicleTypesFromSource(vehiclesDoc \\ "vehicleType")
      VehiclesDataConversion.generateVehicleTypesDefaults(conversionConfig.scenarioDirectory, vehicleTypes)
      VehiclesDataConversion.generateVehiclesDataFromSource(conversionConfig.scenarioDirectory, vehiclesDoc)
    }

    val houseHolds =
      generateHouseholds(persons, vehiclesWithTypeId.flatMap(_.headOption), conversionConfig.income)

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

    val populationOutput = conversionConfig.scenarioDirectory + "/population.xml.gz"
    val householdsOutput = conversionConfig.scenarioDirectory + "/households.xml.gz"
    val householdAttrsOutput = conversionConfig.scenarioDirectory + "/householdAttributes.xml"
    val populationAttrsOutput = conversionConfig.scenarioDirectory + "/populationAttributes.xml"

    safeGzip(populationOutput, transformedPopulationDoc, UTF8, xmlDecl = true, populationDoctype)
    safeGzip(householdsOutput, houseHolds, UTF8, xmlDecl = true)
    XML.save(householdAttrsOutput, householdAtrrs, UTF8, xmlDecl = true, householdsAttrDoctype)
    XML.save(populationAttrsOutput, populationAttrs, UTF8, xmlDecl = true, populationAttrDoctype)
  }

  def safeGzip(filename: String, node: Node, enc: String, xmlDecl: Boolean = false, doctype: DocType = null): Unit = {

    val output = new FileOutputStream(filename)
    try {
      val writer = new OutputStreamWriter(new GZIPOutputStream(output), "UTF-8")
      try {
        XML.write(writer, node, enc, xmlDecl, doctype)
      } finally {
        writer.close()
      }
    } finally {
      output.close()
    }

  }

  def generatePopulationAttributes(persons: NodeSeq): Elem = {
    val popAttrs = persons.zipWithIndex map {
      case (person, _) =>
        <object id={s"${person.attribute("id").get.toString()}"}>
          <attribute name="excluded-modes" class="java.lang.String"></attribute>
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

  def generateHouseholds(persons: NodeSeq, vehicles: Seq[String], income: HouseholdIncome): Elem = {

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
            vehId <- mVeh
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
            val filteredAttrs = attrs.filter(m => m.key.equals("id"))
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

  case class GenAttr(pre: Option[String], key: String, value: Seq[Node], next: MetaData) {
    def toMetaData: Attribute = Attribute(pre, key, value, next)
  }

  def decomposeMetaData(m: MetaData): Option[GenAttr] = m match {
    case Null => None
    case PrefixedAttribute(pre, key, value, next) =>
      Some(GenAttr(Some(pre), key, value, next))
    case UnprefixedAttribute(key, value, next) =>
      Some(GenAttr(None, key, value, next))
  }

  def unchainMetaData(metaData: MetaData): Iterable[GenAttr] =
    metaData.flatMap(decomposeMetaData)

  def chainMetaData(l: Iterable[GenAttr]): MetaData = l match {
    case Nil          => Null
    case head :: tail => head.copy(next = chainMetaData(tail)).toMetaData
  }

  def mapMetaData(m: MetaData)(f: GenAttr => GenAttr): MetaData =
    chainMetaData(unchainMetaData(m).map(f))

  implicit class IdOps(val id: String) extends AnyVal {
    import scala.reflect.classTag
    def createId[T: ClassTag]: Id[T] = Id.create(id, classTag[T].runtimeClass.asInstanceOf[Class[T]])
  }

}
