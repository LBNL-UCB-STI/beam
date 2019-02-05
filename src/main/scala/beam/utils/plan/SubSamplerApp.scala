package beam.utils.plan

import java.util

import beam.sim.common.GeoUtils
import beam.utils.scripts.PopulationWriterCSV
import beam.utils.{BeamVehicleUtils, FileUtils}
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.config.{Config, ConfigUtils}
import org.matsim.core.population.io.PopulationWriter
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.households.{Household, Households, HouseholdsWriterV10}
import org.matsim.utils.objectattributes.ObjectAttributesXmlWriter
import org.supercsv.io.CsvMapWriter
import org.supercsv.prefs.CsvPreference
import scala.collection.JavaConverters._
import scala.collection.{immutable, mutable}
import scala.collection.mutable.ListBuffer
import scala.util.{Random, Try}

case class IncomeRange(min: Double, max: Double)

object SubSamplerApp extends App {

  private val HomeCoordinateX = "homecoordx"

  private val HomeCoordinateY = "homecoordy"

  private sealed trait Quadrant

  private case object UpperLeft extends Quadrant

  private case object UpperRight extends Quadrant

  private case object LowerLeft extends Quadrant

  private case object LowerRight extends Quadrant

  case class HouseHoldCoordinate(id: Id[Household], x: Double, y: Double)

  private object Quadrant {

    def from(hhCoordinate: HouseHoldCoordinate, quadrantOriginCoord: Coord): Quadrant = {
      if (hhCoordinate.y > quadrantOriginCoord.getY) {
        if (hhCoordinate.x < quadrantOriginCoord.getX) {
          UpperLeft
        } else {
          UpperRight
        }
      } else {
        if (hhCoordinate.x < quadrantOriginCoord.getX) {
          LowerLeft
        } else {
          LowerRight
        }
      }

    }
  }

  val SIMPLE_RANDOM_SAMPLING = "simple"
  val STRATIFIED_SAMPLING = "stratified"

  var vehicles: mutable.HashMap[String, util.Map[String, String]] = _

  def loadScenario(sampleDir: String) = {
    val conf = ConfigUtils.createConfig

    conf.plans().setInputFile(s"$sampleDir/population.xml.gz")
    conf.plans().setInputPersonAttributeFile(s"$sampleDir/populationAttributes.xml.gz")

    conf.households().setInputFile(s"$sampleDir/households.xml.gz")
    conf.households().setInputHouseholdAttributesFile(s"$sampleDir/householdAttributes.xml.gz")

    //    conf.vehicles().setVehiclesFile(s"$sampleDir/vehicles.csv.gz")
    vehicles = BeamVehicleUtils.readCsvFileByLine(
      s"$sampleDir/vehicles.csv.gz",
      mutable.HashMap[String, util.Map[String, String]]()
    ) {
      case (line, acc) =>
        val vehicleId = line.get("vehicleId")
        acc += ((vehicleId, line))
    }

    ScenarioUtils.loadScenario(conf)
  }

  def getSimpleRandomSample(
    scenario: Scenario,
    hhIdSet: immutable.Set[Id[Household]],
    sampleSize: Int,
    hhSampling: Boolean
  ): immutable.Set[Id[Household]] = {
    val randomizedHHIds = Random.shuffle(hhIdSet)
    if (hhSampling) {
      if (hhIdSet.size < sampleSize) {
        throw new IllegalArgumentException("sampleSize larger than original data")
      }

      randomizedHHIds.takeRight(sampleSize)
    } else {

      var numberOfAgentsInSample = 0
      val selectedHouseholds = randomizedHHIds.takeWhile { hhId =>
        numberOfAgentsInSample += scenario.getHouseholds.getHouseholds.get(hhId).getMemberIds.size()
        numberOfAgentsInSample < sampleSize
      }

      if (numberOfAgentsInSample < sampleSize) {
        throw new IllegalArgumentException("sampleSize larger than original data")
      }

      selectedHouseholds
    }
  }

  def getSimpleRandomSampleOfHouseholds(scenario: Scenario, sampleSize: Int): immutable.Set[Id[Household]] = {
    getSimpleRandomSample(
      scenario,
      scenario.getHouseholds.getHouseholds.keySet().asScala.toSet,
      sampleSize,
      hhSampling = false
    )
  }

  def getStratifiedSampleOfHousholds(scenario: Scenario, sampleSize: Int): immutable.Set[Id[Household]] = {
    val setList = splitPopulationInFourPartsSpatially(scenario, getAverageCoordinateHouseholds(scenario))

    val setList2 = splitByHHTravelDistance(scenario,setList,3)

    val setList3 = splitByNumberOfVehiclePerHousehold(scenario,setList2,2)

    val setList4 = splitByNumberOfPeopleLivingInHousehold(scenario,setList3,2)

    sample(scenario, setList4, sampleSize)

  }

  def loadHouseHoldCoordinates(households: Households): Seq[HouseHoldCoordinate] = {
    households.getHouseholds.keySet.asScala.map { hhId =>
      val x =
        households.getHouseholdAttributes.getAttribute(hhId.toString, HomeCoordinateX).toString.toDouble
      val y =
        households.getHouseholdAttributes.getAttribute(hhId.toString, HomeCoordinateY).toString.toDouble
      HouseHoldCoordinate(hhId, x, y)
    }.toSeq
  }

  def printNumberOfHouseholdsInForQuadrants(scenario: Scenario, quadrantOriginCoord: Coord): Unit = {
    val elements = loadHouseHoldCoordinates(scenario.getHouseholds)
      .map(coord => Quadrant.from(coord, quadrantOriginCoord))
      .groupBy(identity)
      .mapValues(_.size)

    val message =
      s"""printNumberOfHouseholdsInForQuadrants:
         | quadUpperLeft: ${elements.getOrElse(UpperLeft, 0)}
         | quadUpperRight: ${elements.getOrElse(UpperRight, 0)}
         | quadLowerLeft: ${elements.getOrElse(LowerLeft, 0)}
         | quadLowerRight: ${elements.getOrElse(LowerRight, 0)}
      """.stripMargin
    print(message)
  }

  def splitByNumberOfVehiclePerHousehold(scenario: Scenario,hhIds:mutable.Set[Id[Household]],intervals:Int): ListBuffer[mutable.Set[Id[Household]]]= {
    val household = scenario.getHouseholds.getHouseholds
    val bucket = Math.ceil(hhIds.size.toDouble / intervals ).toInt
    val result = hhIds.map(household.get(_)).toList.sortWith(_.getVehicleIds.size > _.getVehicleIds.size).map(_.getId).grouped(bucket)
    result.map(list => mutable.Set(list : _*)).to[ListBuffer]
  }

  def splitByNumberOfVehiclePerHousehold(scenario: Scenario,hhSetList:ListBuffer[mutable.Set[Id[Household]]],intervals:Int): ListBuffer[mutable.Set[Id[Household]]] ={
    var resultList=mutable.ListBuffer[mutable.Set[Id[Household]]] ()

    for (hhSet <-hhSetList){
      resultList++=splitByNumberOfVehiclePerHousehold(scenario,hhSet,intervals)
    }

    resultList
  }

  def splitByNumberOfPeopleLivingInHousehold(scenario: Scenario,hhIds:mutable.Set[Id[Household]],intervals:Int): ListBuffer[mutable.Set[Id[Household]]]= {
    val household = scenario.getHouseholds.getHouseholds
    val bucket = Math.ceil(hhIds.size.toDouble / intervals ).toInt
    val result = hhIds.map(household.get(_)).toList.sortWith(_.getMemberIds.size > _.getMemberIds.size).map(_.getId).grouped(bucket)
    result.map(list => mutable.Set(list : _*)).to[ListBuffer]
  }


  def splitByNumberOfPeopleLivingInHousehold(scenario: Scenario,hhSetList:ListBuffer[mutable.Set[Id[Household]]],intervals:Int): ListBuffer[mutable.Set[Id[Household]]] ={
    var resultList=mutable.ListBuffer[mutable.Set[Id[Household]]] ()

    for (hhSet <-hhSetList){
      resultList++=splitByNumberOfPeopleLivingInHousehold(scenario,hhSet,intervals)
    }

    resultList
  }

  def splitByHHTravelDistance(scenario: Scenario,hhIds:mutable.Set[Id[Household]],intervals:Int): ListBuffer[mutable.Set[Id[Household]]]= {
    val persons = scenario.getPopulation.getPersons
    val households = scenario.getHouseholds.getHouseholds
    val bucket = Math.ceil(hhIds.size.toDouble / intervals ).toInt
    val result = hhIds.map(households.get(_)).toList.map {
      household =>
        val sum = household.getMemberIds.stream().mapToDouble { personId =>
          val activity = persons.get(personId).getSelectedPlan.getPlanElements.asScala.filter(_.isInstanceOf[Activity]).map(_.asInstanceOf[Activity])
          activity.zip(activity.takeRight(activity.size-1)).map(x => getDifference(x._1.getCoord, x._2.getCoord)).sum  //sum of individual person
        }.sum    //sum of household(all members)
        (household, sum)
    }
      .sortWith((x,y) => x._2 > y._2)  //sorting by sum(sum is 2nd item of tuple)
      .map(_._1.getId)     //getting id from household tuple
      .grouped(bucket)      //grouping by bucket
    result.map(list => mutable.Set(list : _*)).to[ListBuffer]
  }

  def getDifference(coordX: Coord, coordY: Coord): Double = {
    GeoUtils.distFormula(coordX, coordY)
  }


  def splitByHHTravelDistance(scenario: Scenario,hhSetList:ListBuffer[mutable.Set[Id[Household]]],intervals:Int): ListBuffer[mutable.Set[Id[Household]]] ={
    var resultList=mutable.ListBuffer[mutable.Set[Id[Household]]] ()

    for (hhSet <-hhSetList){
      resultList++=splitByHHTravelDistance(scenario,hhSet,intervals)
    }

    resultList
  }

  def getAverageCoordinateHouseholds(scenario: Scenario): Coord = {
    val householdCoordinates = loadHouseHoldCoordinates(scenario.getHouseholds)
    val avgX = householdCoordinates.map(_.x).sum / householdCoordinates.size
    val avgY = householdCoordinates.map(_.y).sum / householdCoordinates.size
    new Coord(avgX, avgY)
  }

  def splitPopulationInFourPartsSpatially(
    scenario: Scenario,
    quadrantOriginCoord: Coord
  ): ListBuffer[Set[Id[Household]]] = {
    loadHouseHoldCoordinates(scenario.getHouseholds)
    val elements: Map[Quadrant, Set[Id[Household]]] = loadHouseHoldCoordinates(scenario.getHouseholds)
      .map(coord => (Quadrant.from(coord, quadrantOriginCoord), coord.id))
      .groupBy { case (quadrant, _) => quadrant }
      .mapValues { listOfIds: Seq[(_, Id[Household])] =>
        listOfIds.map(_._2).toSet
      }
    ListBuffer(
      elements.getOrElse(UpperLeft, Set()),
      elements.getOrElse(UpperRight, Set()),
      elements.getOrElse(LowerLeft, Set()),
      elements.getOrElse(LowerRight, Set())
    )
  }

  def getIncomeInfo(scenario: Scenario, hhIds: Iterable[Id[Household]]): IncomeRange = {
    var minIncome = Double.MaxValue
    var maxIncome = Double.MinValue
    for (hhId <- hhIds) {
      val hh = scenario.getHouseholds.getHouseholds.get(hhId)

      val income = hh.getIncome.getIncome
      if (income > maxIncome) {
        maxIncome = income
      }

      if (income < minIncome) {
        minIncome = income
      }
    }

    IncomeRange(minIncome, maxIncome)
  }

  private def splitByIncomeRange(
    scenario: Scenario,
    hhIds: Iterable[Id[Household]],
    intervals: Int
  ): mutable.ListBuffer[Set[Id[Household]]] = {
    val incomeRange = getIncomeInfo(scenario, hhIds)
    val hh = hhIds.map(household.get(_))

    val result: immutable.Seq[Set[Id[Household]]] = Range
      .BigDecimal(incomeRange.min, incomeRange.max, delta)
      .toList
      .map(
        intervalStart =>
          hh.filter(h => h.getIncome.getIncome >= intervalStart && h.getIncome.getIncome < intervalStart + delta)
            .map(_.getId)
            .toSet
      )

    mutable.ListBuffer(result: _*)
  }

  def splitByIncomeGroups(
    scenario: Scenario,
    hhSetList: Iterable[Iterable[Id[Household]]],
    intervals: Int
  ): mutable.ListBuffer[immutable.Set[Id[Household]]] = {
    var resultList = mutable.ListBuffer[immutable.Set[Id[Household]]]()

    val bucket = Math.ceil(hhIds.size.toDouble / intervals ).toInt
    val result = hhIds.map(household.get(_)).toList.sortWith(_.getIncome.getIncome > _.getIncome.getIncome).map(_.getId).grouped(bucket)
    result.map(list => mutable.Set(list : _*)).to[ListBuffer]
  }

  def splitByIncomeGroups(scenario: Scenario,hhSetList:ListBuffer[mutable.Set[Id[Household]]],intervals:Int): ListBuffer[mutable.Set[Id[Household]]] ={
    var resultList=mutable.ListBuffer[mutable.Set[Id[Household]]] ()

    for (hhSet <- hhSetList) {
      resultList ++= splitByIncomeRange(scenario, hhSet, intervals)
    }

    resultList
  }

  // TODO !!!!!!!!!!!!!!!!!!!!!!!!!!!! sampleByHouseholdSize

  def sample(
    scenario: Scenario,
    hhSetList: ListBuffer[immutable.Set[Id[Household]]],
    sampleSize: Int
  ): immutable.Set[Id[Household]] = {
    val resultSet: mutable.Set[Id[Household]] = mutable.Set()
    // determine, how many from each set we will need to pick!

    val samplingRatio = sampleSize * 1.0 / scenario.getHouseholds.getHouseholds.keySet().size()

    for (hhSet <- hhSetList) {
      val numberOfSamplesToTakeFromSet = Math.ceil(hhSet.size * samplingRatio).toInt

      if (hhSet.size < numberOfSamplesToTakeFromSet * 20) {
        print("warning: set may be too small... to sample from")
      }

      resultSet ++= getSimpleRandomSample(scenario, hhSet, numberOfSamplesToTakeFromSet, hhSampling = true)
    }

    // rounding errors and similar are captured through this here
    getSimpleRandomSample(scenario, resultSet.toSet, sampleSize, hhSampling = false)
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

  def samplePopulation(sc: Scenario, samplingApproach: String, sampleSize: Int): Scenario = {

    val hhIds = sc.getHouseholds.getHouseholds.keySet().asScala
    val hhIsSampled = if (samplingApproach == SIMPLE_RANDOM_SAMPLING) {
      getSimpleRandomSampleOfHouseholds(sc, sampleSize)
    } else if (samplingApproach == STRATIFIED_SAMPLING) {
      getStratifiedSampleOfHousholds(sc, sampleSize)
    } else {
      throw new IllegalArgumentException(s"$samplingApproach is not a valid sampling approach.")
    }

   // val averageHHCoord = getAverageCoordinateHouseholds(sc)

   // print(s"getAverageCoordinateHouseholdsAndNumber: averageHHCoord (${averageHHCoord})")

    //printNumberOfHouseholdsInForQuadrants(sc,averageHHCoord)

   //val list=splitPopulationInFourPartsSpatially(sc,averageHHCoord)

    println(hhIsSampled)
    val hhIdsToRemove = hhIds.diff(hhIsSampled)

    hhIdsToRemove.foreach(
      id => {
        val hh = sc.getHouseholds.getHouseholds.remove(id)
        sc.getHouseholds.getHouseholdAttributes.removeAllAttributes(id.toString)

        hh.getMemberIds.asScala.foreach(
          mId => {
            sc.getPopulation.removePerson(mId)
            sc.getPopulation.getPersonAttributes.removeAllAttributes(mId.toString)
          }
        )

        hh.getVehicleIds.asScala.foreach(
          vId => {
            vehicles.remove(vId.toString)
          }
        )
      }
    )
    sc
  }

  private def writeSample(sc: Scenario, outDir: String): Unit = {
    FileUtils.createDirectoryIfNotExists(outDir)
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
    vehicles.foreach(
      veh => {
        writer.write(veh._2, header: _*)
        writer.flush()
      }
    )
    writer.close()
  }

  def printStats(populationDir: String, sampleSize: Int): Unit = {

    var srcSc = loadScenario(populationDir)
    val refCoord = getAverageCoordinateHouseholds(srcSc)

    val srcQuads = splitPopulationInFourPartsSpatially(srcSc, refCoord)
    val srcQuadSizes = srcQuads.map(_.size)
    val srcSize = srcSc.getHouseholds.getHouseholds.keySet.size

    println(s"Population (households # $srcSize):")

    val simple = samplePopulation(srcSc, SIMPLE_RANDOM_SAMPLING, sampleSize)
    val simpleSize = simple.getHouseholds.getHouseholds.keySet.size
    var scalingFactor = srcSize / simpleSize
    val simpleQuads = splitPopulationInFourPartsSpatially(simple, refCoord)
    val simpleQuadSizes = simpleQuads.map(_.size * scalingFactor)
    val simpleErr = simpleQuadSizes.zip(srcQuadSizes).map(p => math.abs(p._2 - p._1) ).sum
    println()
    println(s"Simple Random (households # $simpleSize):")
    println(s"Abs error: $simpleErr")

    srcSc = loadScenario(populationDir)
    val stratified = samplePopulation(srcSc, STRATIFIED_SAMPLING, sampleSize)
    val stratifiedSize = stratified.getHouseholds.getHouseholds.keySet.size
    scalingFactor = srcSize / stratifiedSize
    val stratifiedQuads = splitPopulationInFourPartsSpatially(stratified, refCoord)
    val stratifiedQuadSizes = stratifiedQuads.map(_.size * scalingFactor)
    val stratifiedErr = stratifiedQuadSizes.zip(srcQuadSizes).map(p => math.abs(p._2 - p._1) ).sum
    println()
    println(s"Stratified (households # $stratifiedSize):")
    println(s"Abs error: $stratifiedErr")
  }

  private def generateSample(sampleDir: String, sampleSize: Int, outDir: String, samplingApproach: String) = {
    val srcSc = loadScenario(sampleDir)
    val sc = samplePopulation(srcSc, samplingApproach, sampleSize)
    writeSample(sc, outDir)
  }

  /*
Params:
test/input/sf-light/sample/25k
1000
test/input/sf-light/sample/1.5k
simple
   */

  /*
  "..\\BeamCompetitions\\fixed-data\\sioux_faux\\sample\\15k"
  1000
   */

  val sampleDir = args(0)
  val sampleSize = Try(args(1).toInt).getOrElse(1000)
//  val outDir = args(2)
//  val samplingApproach = args(3).toLowerCase()

  printStats(sampleDir, sampleSize)

//  generateSample(sampleDir, sampleSize, outDir, samplingApproach)

}
