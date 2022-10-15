package beam.utils.scenario.urbansim.censusblock

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import beam.sim.common.GeoUtils
import beam.utils.scenario.urbansim.censusblock.entities.InputHousehold
import beam.utils.scenario.urbansim.censusblock.merger.{HouseholdMerger, PersonMerger, PlanMerger}
import beam.utils.scenario.urbansim.censusblock.reader._
import beam.utils.scenario.{HouseholdInfo, PersonInfo, PlanElement, ScenarioSource}
import org.matsim.api.core.v01.Coord
import org.slf4j.LoggerFactory

import scala.collection.immutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class UrbansimReaderV2(
  val inputPersonPath: String,
  val inputPlanPath: String,
  val inputHouseholdPath: String,
  val inputBlockPath: String,
  val geoUtils: GeoUtils,
  val shouldConvertWgs2Utm: Boolean,
  val modeMap: Map[String, String]
) extends ScenarioSource {

  private val logger = LoggerFactory.getLogger(getClass)

  private val inputHouseHoldMap: Map[String, InputHousehold] = {
    logger.info("Start reading of households info...")
    val reader = new HouseHoldReader(inputHouseholdPath)
    try {
      reader
        .iterator()
        .map(h => h.householdId -> h)
        .toMap
    } finally {
      logger.info("Households info has been read successfully.")
      reader.close()
    }
  }

  override def getPersons: Iterable[PersonInfo] = {
    val merger = new PersonMerger(inputHouseHoldMap)
    val personReader = new PersonReader(inputPersonPath)

    logger.info("Merging incomes into person...")

    try {
      merger.merge(personReader.iterator()).toList
    } finally {
      logger.info("Incomes merged successfully.")
      personReader.close()
    }
  }

  override def getPlans: Iterable[PlanElement] = {
    val merger = new PlanMerger(modeMap)

    logger.info("Merging modes into plan...")

    val planReader = new PlanReader(inputPlanPath)

    try {
      val iter: Iterator[PlanElement] = merger.merge(planReader.iterator())
      import scala.concurrent.ExecutionContext.Implicits.global
      /*val futures: Iterator[Future[PlanElement]] = iter.map{ plan: PlanElement => Future {
        if (plan.planElementType == PlanElement.Activity && shouldConvertWgs2Utm) {
          val utmCoord = geoUtils.wgs2Utm(new Coord(plan.activityLocationX.get, plan.activityLocationY.get))
          plan.copy(activityLocationX = Some(utmCoord.getX), activityLocationY = Some(utmCoord.getY))
        } else {
          plan
        }
      }
      }
      val x: Future[Iterator[PlanElement]] = Future.sequence(futures)
*/


      implicit val system = ActorSystem()

      import akka.stream.scaladsl.Source
      val source: Source[PlanElement, NotUsed] = Source.fromIterator(()=>iter).mapAsync(100){ plan: PlanElement => Future {
        if (plan.planElementType == PlanElement.Activity && shouldConvertWgs2Utm) {
          val utmCoord = geoUtils.wgs2Utm(new Coord(plan.activityLocationX.get, plan.activityLocationY.get))
          plan.copy(activityLocationX = Some(utmCoord.getX), activityLocationY = Some(utmCoord.getY))
        } else {
          plan
        }
      }}

      import akka.stream.scaladsl.Sink
      val sink = Sink.seq[PlanElement]

      val output: Future[immutable.Seq[PlanElement]] = source.runWith(sink)
      val result = Await.result(output, Duration.Inf)
      system.terminate()
      result.toList
/*
      merger
        .merge(planReader.iterator())
        .map { plan: PlanElement =>
          if (plan.planElementType == PlanElement.Activity && shouldConvertWgs2Utm) {
            val utmCoord = geoUtils.wgs2Utm(new Coord(plan.activityLocationX.get, plan.activityLocationY.get))
            plan.copy(activityLocationX = Some(utmCoord.getX), activityLocationY = Some(utmCoord.getY))
          } else {
            plan
          }
        }
        .toList

 */
    } finally {
      logger.info("Modes merged successfully into plan.")
      planReader.close()
    }
  }

  override def getHousehold: Iterable[HouseholdInfo] = {
    logger.debug("Reading of the blocks...")
    val blockReader = new BlockReader(inputBlockPath)
    val blocks = blockReader
      .iterator()
      .map(b => b.blockId -> b)
      .toMap
    val merger = new HouseholdMerger(blocks)

    logger.debug("Merging blocks into households...")

    try {
      merger
        .merge(inputHouseHoldMap.valuesIterator)
        .map { household =>
          if (shouldConvertWgs2Utm) {
            val utmCoord = geoUtils.wgs2Utm(new Coord(household.locationX, household.locationY))
            household.copy(locationX = utmCoord.getX, locationY = utmCoord.getY)
          } else {
            household
          }
        }
        .toList
    } finally {
      logger.debug("Blocks merged successfully.")
      blockReader.close()
    }
  }
}
