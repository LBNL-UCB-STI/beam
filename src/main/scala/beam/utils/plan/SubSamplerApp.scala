package beam.utils.plan

import beam.utils.scripts.PopulationWriterCSV
import beam.utils.{BeamVehicleUtils, FileUtils}
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.config.{Config, ConfigUtils}
import org.matsim.core.population.io.PopulationWriter
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.households.{Household, HouseholdsWriterV10}
import org.matsim.utils.objectattributes.ObjectAttributesXmlWriter
import org.supercsv.io.CsvMapWriter
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.{Random, Try}

object SubSamplerApp extends App {
  val SIMPLE_RANDOM_SAMPLING = "simple"
  val STRATIFIED_SAMPLING = "stratified"

  var vehicles: mutable.HashMap[String, java.util.Map[String, String]] = _

  private def getConfig(sampleDir: String) = {
    val conf = ConfigUtils.createConfig

    conf.plans().setInputFile(s"$sampleDir/population.xml.gz")
    conf.plans().setInputPersonAttributeFile(s"$sampleDir/populationAttributes.xml.gz")

    conf.households().setInputFile(s"$sampleDir/households.xml.gz")
    conf.households().setInputHouseholdAttributesFile(s"$sampleDir/householdAttributes.xml.gz")

    //    conf.vehicles().setVehiclesFile(s"$sampleDir/vehicles.csv.gz")
    vehicles = BeamVehicleUtils.readCsvFileByLine(
      s"$sampleDir/vehicles.csv.gz",
      mutable.HashMap[String, java.util.Map[String, String]]()
    ) {
      case (line, acc) =>
        val vehicleId = line.get("vehicleId")
        acc += ((vehicleId, line))
    }
    conf
  }


  def getSimpleRandomSampleOfHouseholds(scenario: Scenario, sampleSize: Int): List[Set[Id[Household]]] = {
    val randomizedHHIds = Random.shuffle(scenario.getHouseholds.getHouseholds.keySet().asScala)

    var numberOfAgentsInSample = 0
    val selectedHouseholds = randomizedHHIds.takeWhile { hhId =>

      numberOfAgentsInSample += scenario.getHouseholds.getHouseholds.get(hhId).getMemberIds.size()

      numberOfAgentsInSample < sampleSize
    }

    List(selectedHouseholds.toSet)
  }

  def getStratifiedSampleOfHousholds(scenario: Scenario, sampleSize: Int): List[Set[Id[Household]]] = {
    List(scenario.getHouseholds.getHouseholds.keySet().asScala.toSet)
  }

  /*

    def createHouseholdSpatialClusters(scenario: Scenario, households:  List[Set[Id[Household]]], numberOfClusters:Int) ={
      // TODO: return multiple sets of same size clusters (spatial co-location)

      // TODO: use grid if cluster hard



      //

      households
    }

    def splitByDistance(scenario: Scenario, households:  List[Set[Id[Household]]], numberOfClusters:Int)= {
      // calculate distance travelled per day for all household members per day -> d_sum
      // sort households by d_sum
      // split into equal size groups: numberOfClusters

      households
    }


    def splitByIncome()




    def splitByAge
  */




  def samplePopulation(conf: Config, sampleSize: Int): Scenario = {

    val sc: Scenario = ScenarioUtils.loadScenario(conf)

    val hhIds = sc.getHouseholds.getHouseholds.keySet().asScala
    val hhIsSampled = if(samplingApproach == SIMPLE_RANDOM_SAMPLING) {
      getSimpleRandomSampleOfHouseholds(sc, sampleSize)
    } else if(samplingApproach == STRATIFIED_SAMPLING) {
      getStratifiedSampleOfHousholds(sc, sampleSize)
    } else {
      throw new IllegalArgumentException(s"$samplingApproach is not a valid sampling approach.")
    }

    val hhIdsToRemove = hhIds.diff(hhIsSampled.flatten.toSet)

    hhIdsToRemove.foreach(id => {
      val hh = sc.getHouseholds.getHouseholds.remove(id)
      sc.getHouseholds.getHouseholdAttributes.removeAllAttributes(id.toString)

      hh.getMemberIds.asScala.foreach(mId => {
        sc.getPopulation.removePerson(mId)
        sc.getPopulation.getPersonAttributes.removeAllAttributes(mId.toString)
      })

      hh.getVehicleIds.asScala.foreach(vId => {
        vehicles.remove(vId.toString)
      })
    })
    sc
  }

  private def writeSample(outDir: String, sc: Scenario): Unit = {
    new HouseholdsWriterV10(sc.getHouseholds).writeFile(s"$outDir/households.xml.gz")
    new PopulationWriter(sc.getPopulation).write(s"$outDir/population.xml.gz")
    PopulationWriterCSV(sc.getPopulation).write(s"$outDir/population.csv.gz")
    //    new VehicleWriterV1(sc.getVehicles).writeFile(s"$outDir/vehicles.csv.gz")
    new ObjectAttributesXmlWriter(sc.getHouseholds.getHouseholdAttributes)
      .writeFile(s"$outDir/householdAttributes.xml.gz")
    new ObjectAttributesXmlWriter(sc.getPopulation.getPersonAttributes)
      .writeFile(s"$outDir/populationAttributes.xml.gz")

    val writer =
      new CsvMapWriter(FileUtils.writerToFile(s"$outDir/vehicles.csv.gz"), CsvPreference.STANDARD_PREFERENCE, true)
    val header = vehicles.head._2.keySet().toArray(Array[String]())
    writer.writeHeader(header: _*)
    vehicles.foreach(veh => {
      writer.write(veh._2, header: _*)
      writer.flush()
    })
    writer.close()
  }

  /*
Params:
test/input/sf-light/sample/25k
1000
test/input/sf-light/sample/1.5k
   */

  val sampleDir = args(0)
  val sampleSize = Try(args(1).toInt).getOrElse(1000)
  val outDir = args(2)
  val samplingApproach = args(3).toLowerCase()

  val sampler = SubSamplerApp
  val conf = getConfig(sampleDir)
  val sc = sampler.samplePopulation(conf, sampleSize)
  writeSample(outDir, sc)
}
