package beam.utils.plan

import beam.utils.scripts.PopulationWriterCSV
import beam.utils.{BeamVehicleUtils, FileUtils}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.config.{Config, ConfigUtils}
import org.matsim.core.population.io.PopulationWriter
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.households.{Household, HouseholdsWriterV10}
import org.matsim.utils.objectattributes.ObjectAttributesXmlWriter
import org.supercsv.io.CsvMapWriter
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.{Random, Try}

case class IncomeRange(min: Double, max:Double)

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


  def getSimpleRandomeSample(scenario: Scenario, hhIdSet:mutable.Set[Id[Household]],sampleSize: Int, hhSampling:Boolean): mutable.Set[Id[Household]]={
    val randomizedHHIds = Random.shuffle(hhIdSet)
    if (hhSampling){

      if (hhIdSet.size<sampleSize){
        throw new RuntimeException("sampleSize larger than original data")
      }

      randomizedHHIds.takeRight(sampleSize)
    } else {

      var numberOfAgentsInSample = 0
      val selectedHouseholds = randomizedHHIds.takeWhile { hhId =>

        numberOfAgentsInSample += scenario.getHouseholds.getHouseholds.get(hhId).getMemberIds.size()

        numberOfAgentsInSample < sampleSize
      }

      if (numberOfAgentsInSample<sampleSize){
        throw new RuntimeException("sampleSize larger than original data")
      }

      selectedHouseholds
    }
  }


  def getSimpleRandomSampleOfHouseholds(scenario: Scenario, sampleSize: Int): mutable.Set[Id[Household]] = {
    getSimpleRandomeSample(scenario, scenario.getHouseholds.getHouseholds.keySet().asScala,sampleSize,false)
  }

  def getStratifiedSampleOfHousholds(scenario: Scenario, sampleSize: Int): mutable.Set[Id[Household]] = {
    var setList=splitPopulationInFourPartsSpatially(scenario, getAverageCoordinateHouseholds(scenario))


    setList=splitByIncomeGroups(scenario,setList,3)

    sample(scenario,setList,sampleSize)

  }


  def printNumberOfHouseholdsInForQuadrants(scenario: Scenario, quadrantOriginCoord:Coord)= {
    var quadUpperLeft=0
    var quadUpperRight=0
    var quadLowerLeft=0
    var quadLowerRight=0

    scenario.getHouseholds.getHouseholds.keySet.forEach { hhId =>

      val x = scenario.getHouseholds.getHouseholdAttributes.getAttribute(hhId.toString,"homecoordx").toString.toDouble
      val y = scenario.getHouseholds.getHouseholdAttributes.getAttribute(hhId.toString,"homecoordy").toString.toDouble

      if (y > quadrantOriginCoord.getY){
        if (x < quadrantOriginCoord.getX){
          quadUpperLeft+=1
        } else {
          quadUpperRight+=1
        }
      } else {
        if (x < quadrantOriginCoord.getX){
          quadLowerLeft+=1
        } else {
          quadLowerRight+=1
        }
      }
    }

    print(s"printNumberOfHouseholdsInForQuadrants: quadUpperLeft: $quadUpperLeft, quadUpperRight: $quadUpperRight, quadLowerLeft: $quadLowerLeft, quadLowerRight: $quadLowerRight  ")

  }

  def getAverageCoordinateHouseholds(scenario: Scenario): Coord = {
    var averageHHCoord:Option[Coord] = None


    scenario.getHouseholds.getHouseholds.keySet.forEach { hhId =>

      val x = scenario.getHouseholds.getHouseholdAttributes.getAttribute(hhId.toString,"homecoordx").toString.toDouble
      val y = scenario.getHouseholds.getHouseholdAttributes.getAttribute(hhId.toString,"homecoordy").toString.toDouble

      averageHHCoord = averageHHCoord match {
        case None => Some(new Coord(x,y))
        case Some(coordA) => Some(new Coord(coordA.getX +x,coordA.getY + y))
      }

      }


    val numberHouseholds=scenario.getHouseholds.getHouseholds.keySet.size()
    new Coord(averageHHCoord.get.getX/numberHouseholds,averageHHCoord.get.getY/numberHouseholds)
  }


  def splitPopulationInFourPartsSpatially(scenario: Scenario, quadrantOriginCoord:Coord): ListBuffer[mutable.Set[Id[Household]]]={
    var quadUpperLeft=scala.collection.mutable.Set[Id[Household]]()
    var quadUpperRight=scala.collection.mutable.Set[Id[Household]]()
    var quadLowerLeft=scala.collection.mutable.Set[Id[Household]]()
    var quadLowerRight=scala.collection.mutable.Set[Id[Household]]()

    scenario.getHouseholds.getHouseholds.keySet.forEach { hhId =>

      val x = scenario.getHouseholds.getHouseholdAttributes.getAttribute(hhId.toString,"homecoordx").toString.toDouble
      val y = scenario.getHouseholds.getHouseholdAttributes.getAttribute(hhId.toString,"homecoordy").toString.toDouble

      if (y > quadrantOriginCoord.getY){
        if (x < quadrantOriginCoord.getX){
          quadUpperLeft+=hhId
        } else {
          quadUpperRight+=hhId
        }
      } else {
        if (x < quadrantOriginCoord.getX){
          quadLowerLeft+=hhId
        } else {
          quadLowerRight+=hhId
        }
      }
    }

    ListBuffer(quadUpperLeft,quadUpperRight,quadLowerLeft,quadLowerRight)
  }


  def getIncomeInfo(scenario: Scenario,hhIds:mutable.Set[Id[Household]]):IncomeRange={

    var minIncome=Double.MaxValue
    var maxIncome=Double.MinValue
    for (hhId <- hhIds){

      val hh=scenario.getHouseholds.getHouseholds.get(hhId)
      val income=hh.getIncome.getIncome
      if (income>maxIncome){
        maxIncome=income
      }

      if (income<minIncome){
        minIncome=income
      }
    }

    IncomeRange(minIncome,maxIncome)
  }


  def splitByIncomeRange(scenario: Scenario,hhIds:mutable.Set[Id[Household]],intervals:Int): mutable.ListBuffer[mutable.Set[Id[Household]]]= {
    val incomeRange=getIncomeInfo(scenario,hhIds)


    val delta = (incomeRange.max - incomeRange.min) / intervals
    val household = scenario.getHouseholds.getHouseholds
    val hh = hhIds.map(household.get(_))




    val result=Range.BigDecimal(incomeRange.min, incomeRange.max, delta).toList.map(
      intervalStart => hh.filter(h => h.getIncome.getIncome >= intervalStart && h.getIncome.getIncome < intervalStart+delta).map(_.getId)
    )

    mutable.ListBuffer(result: _*)
  }

  def splitByIncomeGroups(scenario: Scenario,hhSetList:ListBuffer[mutable.Set[Id[Household]]],intervals:Int): mutable.ListBuffer[mutable.Set[Id[Household]]] ={
    var resultList=mutable.ListBuffer[mutable.Set[Id[Household]]] ()

    for (hhSet <-hhSetList){
      resultList++=splitByIncomeRange(scenario,hhSet,intervals)
    }

    resultList
  }



  // TODO !!!!!!!!!!!!!!!!!!!!!!!!!!!! sampleByHouseholdSize

  def sample(scenario: Scenario,hhSetList:ListBuffer[mutable.Set[Id[Household]]],sampleSize:Int):mutable.Set[Id[Household]]={
    val resultSet:mutable.Set[Id[Household]]=mutable.Set()
    // determine, how many from each set we will need to pick!



    val samplingRatio=sampleSize*1.0/scenario.getHouseholds.getHouseholds.keySet().size()


    for (hhSet <- hhSetList) {
      val numberOfSamplesToTakeFromSet=Math.ceil(hhSet.size*samplingRatio).toInt

      if (hhSet.size<numberOfSamplesToTakeFromSet*20){
        print("warning: set may be too small... to sample from")
      }


      resultSet++=getSimpleRandomeSample(scenario,hhSet,numberOfSamplesToTakeFromSet,true)
    }


    // rounding errors and similar are captured through this here
    getSimpleRandomeSample(scenario,resultSet,sampleSize,false)
  }

  // print stats of sample read!!!
  // e.g.



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


      def splitByIncome(scenario: Scenario, households:  List[Set[Id[Household]]], numberOfClusters:Int): List[Set[Id[Household]]] {
      // 10 sets (each set contains 100 housholdIds

      // number of clusters =5


      for each set: order by income the hh (set has still 100 housholdIds).

      go through sorted list of housholdIds (sorged by income) and take 20 and put them in separate set.




      // return: 50 sets (20 household ids)
  }


val newHHFac: HouseholdsFactoryImpl = new HouseholdsFactoryImpl()
def splitByIncome(scenario: Scenario, households:  List[mutable.Set[Id[Household]]], numberOfClusters:Int): List[Set[Id[Household]]] = {

  households.flatMap(household => {
  val set = Set(household.map(newHHFac.createHousehold(_))
  .filter(_.getIncome!= null).toList
  .sortWith(_.getIncome.getIncome > _.getIncome.getIncome)
  .map(_.getId): _*)
  set.sliding(set.size / numberOfClusters)
})





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


    val averageHHCoord = getAverageCoordinateHouseholds(sc)

    print(s"getAverageCoordinateHouseholdsAndNumber: averageHHCoord (${averageHHCoord})")

    printNumberOfHouseholdsInForQuadrants(sc,averageHHCoord)

   val list=splitPopulationInFourPartsSpatially(sc,averageHHCoord)


    val hhIdsToRemove = hhIsSampled

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
