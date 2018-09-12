package beam.utils.matsim_conversion

import java.io.FileWriter
import java.util

import beam.utils.FileUtils
import org.supercsv.io.CsvMapWriter
import org.supercsv.prefs.CsvPreference

import scala.xml._
import scala.xml.dtd.{DocType, SystemID}
import scala.xml.transform.{RewriteRule, RuleTransformer}

object MatsimPlanConversion {

  val beamFuelTypesTitles = Seq("fuelTypeId","priceInDollarsPerMJoule")
  val beamFuelTypes = Seq(
    Seq("gasoline","0.03"), Seq("diesel","0.02")
  )

  val beamVehicleTypeTitles = Seq(
    "vehicleTypeId","seatingCapacity","standingRoomCapacity","lengthInMeter","primaryFuelType",
    "primaryFuelConsumptionInJoulePerMeter","primaryFuelCapacityInJoule","secondaryFuelType",
    "secondaryFuelConsumptionInJoulePerMeter","secondaryFuelCapacityInJoule","automationLevel",
    "maxVelocity","passengerCarUnit","rechargeLevel2RateLimitInWatts","rechargeLevel3RateLimitInWatts",
    "vehicleCategory"
  )
  val beamVehicleTypes = Seq(
    Seq("CAR-1","4","0","4.5","gasoline","2","4","gasoline","80","3","level","60","unit","50","40","CAR"),
    Seq("SUV-2","6","0","5.5","gasoline","2","4","gasoline","80","3","level","50","unit","50","40","SUV"),
    Seq("BUS-DEFAULT","50","50","12","diesel","2","4","gasoline","80","3","level","50","unit","50","40","TRANSIT-BUS"),
    Seq("SUBWAY-DEFAULT","50","50","12","diesel","2","4","gasoline","80","3","level","50","unit","50","40","TRANSIT-SUBWAY"),
    Seq("TRAM-DEFAULT","50","50","7.5","diesel","2","4","gasoline","80","3","level","50","unit","50","40","TRANSIT-TRAM"),
    Seq("RAIL-DEFAULT","50","50","7.5","diesel","2","4","gasoline","80","3","level","50","unit","50","40","TRANSIT-RAIL"),
    Seq("CABLE_CAR-DEFAULT","50","50","7.5","diesel","2","4","gasoline","80","3","level","50","unit","50","40","TRANSIT-CABLE_CAR"),
  )

  val beamVehicleTitles = Seq(
    "vehicleId","vehicleTypeId"
  )

  def generateScenarioXml(conversionConfig: ConversionConfig): Unit = {
    val populationFile = conversionConfig.populationInput
    val populationDoc = XML.loadFile(populationFile)

    val transformedPopulationDoc = matsimPopulationToBeam(populationDoc)

    val persons = transformedPopulationDoc \\ "person"

    //Generate vehicles data
    generateFuelTypesDefaults(conversionConfig)
    val vehiclesWithTypeId = if (conversionConfig.generateVehicles){
      generateVehicleTypesDefaults(conversionConfig, Seq())
      generateVehiclesDataFromPersons(persons,conversionConfig)
    }else {
      val vehiclesFile = conversionConfig.vehiclesInput.get
      val vehiclesDoc = XML.loadFile(vehiclesFile)
      val vehicleTypes = generateVehicleTypesFromSource(vehiclesDoc \\ "vehicleType")
      generateVehicleTypesDefaults(conversionConfig, vehicleTypes)
      generateVehiclesDataFromSource(vehiclesDoc, conversionConfig)
    }

    val houseHolds =
      generateHouseholds(persons, vehiclesWithTypeId.map(_.head), conversionConfig.income)

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

    val populationOutput = conversionConfig.scenarioDirectory + "/population.xml"
    val householdsOutput = conversionConfig.scenarioDirectory + "/households.xml"
    val householdAttrsOutput = conversionConfig.scenarioDirectory + "/householdAttributes.xml"
    val populationAttrsOutput = conversionConfig.scenarioDirectory + "/populationAttributes.xml"

    XML.save(populationOutput, transformedPopulationDoc, "UTF-8", true, populationDoctype)
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

  def generateFuelTypesDefaults(conversionConfig: ConversionConfig) = {
    val beamFuelTypesPath = conversionConfig.scenarioDirectory + "/beamFuelTypes.csv"

    writeCsvFile(beamFuelTypesPath, beamFuelTypes, beamFuelTypesTitles)
  }

  def generateVehicleTypesDefaults(conversionConfig: ConversionConfig, vehicleTypes: Seq[Seq[String]]) = {
    val beamVehTypesPath = conversionConfig.scenarioDirectory + "/vehicleTypes.csv"

    writeCsvFile(beamVehTypesPath, beamVehicleTypes ++ vehicleTypes, beamVehicleTypeTitles)
  }

  def generateVehicleTypesFromSource(vehicleTypeSeq: NodeSeq): Seq[Seq[String]] = {
    val requiredFieldsForType = List("description", "capacity", "length", "engineInformation")

    for {
      vehicleType  <- vehicleTypeSeq
      requiredElem <- requiredFieldsForType if (vehicleType \\ requiredElem).isEmpty
    } yield {
      println(s"Input vehicle data is missing $requiredElem xml element in ${vehicleType.label}")
    }

    vehicleTypeSeq.map { vt =>
      val description = (vt \\ "description").text
      val seatingCap = vt \ "capacity" \\ "seats" \@ "persons"
      val standingCap = vt \ "capacity" \\ "standingRoom" \@ "persons"
      val length = vt \\ "length" \@ "meter"
      val fuelType = (vt \ "engineInformation" \\ "fuelType").text
      Seq(description,seatingCap,standingCap,length,fuelType,"2","4","gasoline","80","3","level","60","unit","50","40","CAR")
    }
  }

  def generateVehiclesDataFromPersons(persons: NodeSeq, conversionConfig: ConversionConfig): Seq[Seq[String]] = {
    val vehicles = persons.zipWithIndex.map {
      case (_, index) =>
        Seq(s"${index + 1}", "CAR-1")
    }
    val beamVehiclesPath = conversionConfig.scenarioDirectory + "/vehicles.csv"
    writeCsvFile(beamVehiclesPath, vehicles, beamVehicleTitles)
    vehicles
  }

  def writeCsvFile(beamVehiclesPath: String, data: Seq[Seq[String]], titles: Seq[String]) = {
    FileUtils.using(
      new CsvMapWriter(new FileWriter(beamVehiclesPath), CsvPreference.STANDARD_PREFERENCE)){ writer =>
      writer.writeHeader(titles :_*)
      val rows = data.map{ row =>
        row.zipWithIndex.foldRight(new util.HashMap[String, Object]()){ case ((s, i), acc) =>
          acc.put(titles(i), s)
          acc
        }
      }
      val titlesArray = titles.toArray
      rows.foreach(row => writer.write(row, titlesArray :_*))
    }
  }

  def generateVehiclesDataFromSource(vehiclesDoc: Elem, conversionConfig: ConversionConfig): Seq[Seq[String]] = {
    val vehicles = (vehiclesDoc \ "vehicle").map{ vehicle =>
      Seq(vehicle \@ "id", vehicle \@ "type")
    }
    val beamVehiclesPath = conversionConfig.scenarioDirectory + "/vehicles.csv"
    writeCsvFile(beamVehiclesPath, vehicles, beamVehicleTitles)
    vehicles
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

  def generateHouseholds(persons: NodeSeq, vehicles: Seq[String], income: HouseholdIncome) = {

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

  def unchainMetaData(m: MetaData): Iterable[GenAttr] =
    m flatMap (decomposeMetaData)

  def chainMetaData(l: Iterable[GenAttr]): MetaData = l match {
    case Nil          => Null
    case head :: tail => head.copy(next = chainMetaData(tail)).toMetaData
  }

  def mapMetaData(m: MetaData)(f: GenAttr => GenAttr): MetaData =
    chainMetaData(unchainMetaData(m).map(f))

}
