package beam.utils.plan


import beam.utils.scripts.PopulationWriterCSV
import org.matsim.api.core.v01.Scenario
import org.matsim.core.config.{Config, ConfigUtils}
import org.matsim.core.population.io.PopulationWriter
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.households.HouseholdsWriterV10
import org.matsim.utils.objectattributes.ObjectAttributesXmlWriter
import org.matsim.vehicles.VehicleWriterV1

import scala.collection.JavaConverters._
import scala.util.{Random, Try}


object SubSamplerApp extends App {

  private def getConfig(sampleFile: String) = {
    val conf = ConfigUtils.createConfig

//    conf.network().setInputFile("test/input/sf-light/r5/physsim-network.xml")

    conf.plans().setInputFile(s"$sampleFile/population.xml.gz")
    conf.plans().setInputPersonAttributeFile(s"$sampleFile/populationAttributes.xml.gz")

    conf.households().setInputFile(s"$sampleFile/households.xml.gz")
    conf.households().setInputHouseholdAttributesFile(s"$sampleFile/householdAttributes.xml.gz")

//    conf.vehicles().setVehiclesFile(s"$sampleFile/vehicles.csv.gz")

    conf
  }

  def samplePopulation(conf: Config, sampleSize: Int): Scenario = {

    val sc: Scenario = ScenarioUtils.loadScenario(conf)

    val hhIds = sc.getHouseholds.getHouseholds.keySet().asScala
    val hhIdsToRemove = Random.shuffle(hhIds).takeRight(hhIds.size - sampleSize)

    hhIdsToRemove.foreach(id => {
      val hh = sc.getHouseholds.getHouseholds.remove(id)
      sc.getHouseholds.getHouseholdAttributes.removeAllAttributes(id.toString)

      hh.getMemberIds.asScala.foreach(mId => {
        sc.getPopulation.removePerson(mId)
        sc.getPopulation.getPersonAttributes.removeAllAttributes(mId.toString)
      })

      hh.getVehicleIds.asScala.foreach(vId => {
        sc.getVehicles.removeVehicle(vId)
      })
    })
    sc
  }

  private def writeSample(outDir: String, sc: Scenario): Unit = {
    new HouseholdsWriterV10(sc.getHouseholds).writeFile(s"$outDir/households.xml.gz")
    new PopulationWriter(sc.getPopulation).write(s"$outDir/population.xml.gz")
    PopulationWriterCSV(sc.getPopulation).write(s"$outDir/population.csv.gz")
    new VehicleWriterV1(sc.getVehicles).writeFile(s"$outDir/vehicles.xml.gz")
    new ObjectAttributesXmlWriter(sc.getHouseholds.getHouseholdAttributes)
      .writeFile(s"$outDir/householdAttributes.xml.gz")
    new ObjectAttributesXmlWriter(sc.getPopulation.getPersonAttributes)
      .writeFile(s"$outDir/populationAttributes.xml.gz")
  }

/*
Params:
test/input/sf-light/sample/25k
1000
test/input/sf-light/sample/1.5k
 */

  val sampleFile = args(0)
  val sampleSize = Try(args(1).toInt).getOrElse(1000)
  val outDir = args(2)

  val sampler = SubSamplerApp
  val conf = getConfig(sampleFile)
  val sc = sampler.samplePopulation(conf, sampleSize)
  writeSample(outDir, sc)
}
