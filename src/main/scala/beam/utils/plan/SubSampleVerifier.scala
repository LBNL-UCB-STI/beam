package beam.utils.plan

import java.nio.file.Paths
import java.util

import beam.utils.BeamVehicleUtils
import beam.utils.plan.SubSamplerApp.{getAverageCoordinateHouseholds, splitPopulationInFourPartsSpatially, splitByAvgIncomes, splitByAvgHHTravelDistances, splitByAvgNumberOfVehiclePerHousehold,splitByAvgNumberOfPeopleLivingInHousehold, getPercentageError}
import org.matsim.api.core.v01.Scenario
import org.matsim.core.config.{Config, ConfigUtils}
import org.matsim.core.scenario.ScenarioUtils

import scala.collection.mutable

object SubSampleVerifier extends App {
  var vehicles: mutable.HashMap[String, util.Map[String, String]] = _

  def loadScenario(sampleDir: String) = {
    val conf: Config = createConfig(sampleDir)

    ScenarioUtils.loadScenario(conf)
  }

  def createConfig(sampleDir: String) = {
    val conf = ConfigUtils.createConfig

    conf.plans().setInputFile(s"$sampleDir/population.xml.gz")
    conf.plans().setInputPersonAttributeFile(s"$sampleDir/populationAttributes.xml.gz")

    conf.households().setInputFile(s"$sampleDir/households.xml.gz")
    conf.households().setInputHouseholdAttributesFile(s"$sampleDir/householdAttributes.xml.gz")

    readVehicles(sampleDir)
    conf
  }

  private def readVehicles(sampleDir: String) = {
    vehicles = BeamVehicleUtils.readCsvFileByLine(
      s"$sampleDir/vehicles.csv.gz",
      mutable.HashMap[String, util.Map[String, String]]()
    ) {
      case (line, acc) =>
        val vehicleId = line.get("vehicleId")
        acc += ((vehicleId, line))
    }
  }

  def verifySample(population: Scenario, sample: Scenario) = {
    val refCoord = getAverageCoordinateHouseholds(population)

    val popQuads = splitPopulationInFourPartsSpatially(population, refCoord)
    val popAvgIncomes = splitByAvgIncomes(population, 3)
    val popAvgDistances = splitByAvgHHTravelDistances(population, 3)
    val popAvgNumOfVeh = splitByAvgNumberOfVehiclePerHousehold(population, 3)
    val popAvgNumOfMem = splitByAvgNumberOfPeopleLivingInHousehold(population, 3)

    val popQuadSizes = popQuads.map(_.size.toDouble)
    val popSize = population.getHouseholds.getHouseholds.keySet.size


    val sampleActualSize = sample.getHouseholds.getHouseholds.keySet.size
    val scalingFactor = popSize / sampleActualSize.toDouble
    val sampleQuads = splitPopulationInFourPartsSpatially(sample, refCoord)
    val sampleQuadSizes = sampleQuads.map(_.size * scalingFactor)
    val sampleSpatialErr = getPercentageError(popQuadSizes, sampleQuadSizes)

    val sampleAvgIncomes = splitByAvgIncomes(sample, 3)
    val sampleIncomeErr = getPercentageError(popAvgIncomes, sampleAvgIncomes)

    val sampleAvgDistances = splitByAvgHHTravelDistances(sample, 3)
    val sampleDistanceErr = getPercentageError(popAvgDistances, sampleAvgDistances)

    val sampleAvgNumOfVeh = splitByAvgNumberOfVehiclePerHousehold(sample, 3)
    val sampleNumOfVehErr = getPercentageError(popAvgNumOfVeh, sampleAvgNumOfVeh)

    val sampleAvgNumOfMem = splitByAvgNumberOfPeopleLivingInHousehold(sample, 3)
    val sampleNumOfMemErr = getPercentageError(popAvgNumOfMem, sampleAvgNumOfMem)

    val sampleError = sampleSpatialErr + sampleIncomeErr + sampleDistanceErr + sampleNumOfVehErr + sampleNumOfMemErr
    println(
      s"Sample error is: $sampleError (having spatial: $sampleSpatialErr, travel distance: $sampleDistanceErr, vehicles/hh: $sampleNumOfVehErr, members/hh: $sampleNumOfMemErr and income: $sampleIncomeErr)."
    )
  }

  def verifyVehicles(sample: Scenario): Unit = {
    sample.getHouseholds.getHouseholds.values().forEach(
      _.getVehicleIds.forEach(vId => if(!vehicles.contains(vId.toString)) {
        println(s"Vehicle [$vId] is missing in sample.")
      })
    )
  }

  private def verifySample(sampleDir: String, population: Scenario): Unit = {
    val sample = loadScenario(sampleDir)
    verifySample(population, sample)
    verifyVehicles(sample)
  }

  /*
  "..\\BeamCompetitions\\fixed-data\\sioux_faux\\sample\\15k"
  "..\\BeamCompetitions\\fixed-data\\sioux_faux\\sample\\sub\\1k/stratified/33"
   batch (optional)
   */

  val populationDir = args(0)
  val sampleDir = args(1)
  val batch = args.length == 3 && args(2).equalsIgnoreCase("batch")

  val population = loadScenario(populationDir)
  if(batch) {
    Paths.get(sampleDir).toFile.listFiles
      .filter(_.isDirectory).foreach(f => {
      val s = f.getAbsolutePath
      println(s)
      verifySample(s, population)
    })
  } else {
    verifySample(sampleDir, population)
  }
}
