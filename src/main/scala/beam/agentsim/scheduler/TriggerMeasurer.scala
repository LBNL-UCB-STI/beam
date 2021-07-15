package beam.agentsim.scheduler

import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Debug.StuckAgentDetection
import beam.sim.config.BeamConfig.Beam.Debug.StuckAgentDetection.Thresholds$Elm
import beam.sim.config.BeamConfig.Beam.Debug.StuckAgentDetection.Thresholds$Elm.ActorTypeToMaxNumberOfMessages
import beam.utils.Statistics
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/*
  This is dumb implementation. We store all the data to build percentiles. There are different approaches to get percentile on stream
  More: https://blog.superfeedr.com/streaming-percentiles/
 */
class TriggerMeasurer(val cfg: BeamConfig.Beam.Debug.TriggerMeasurer) extends LazyLogging {

  implicit val actorTypeToMaxNumberOfMessagesDecoder: Encoder[ActorTypeToMaxNumberOfMessages] =
    deriveEncoder[ActorTypeToMaxNumberOfMessages]
  implicit val thresholds$ElmDecoder: Encoder[Thresholds$Elm] = deriveEncoder[Thresholds$Elm]
  implicit val stuckAgentDetectionDecoder: Encoder[StuckAgentDetection] = deriveEncoder[StuckAgentDetection]

  private val triggerWithIdToStartTime: mutable.Map[TriggerWithId, Long] =
    mutable.Map[TriggerWithId, Long]()

  private val triggerTypeToOccurrence: mutable.Map[Class[_], ArrayBuffer[Long]] =
    mutable.Map[Class[_], ArrayBuffer[Long]]()

  private val actorToTriggerMessages: mutable.Map[ActorRef, mutable.Map[Class[_], Int]] =
    mutable.Map[ActorRef, mutable.Map[Class[_], Int]]()

  val transitDriverAgentName: String = "TransitDriverAgent"
  val populationName: String = "Population"
  val rideHailAgentName: String = "RideHailAgent"
  val rideHailManagerName: String = "RideHailManager"

  def sent(t: TriggerWithId, actor: ActorRef): Unit = {
    triggerWithIdToStartTime.put(t, System.nanoTime())
    val triggerClazz = t.trigger.getClass
    actorToTriggerMessages.get(actor) match {
      case Some(triggerTypeToOccur) =>
        triggerTypeToOccur.get(triggerClazz) match {
          case Some(current) =>
            triggerTypeToOccur.update(triggerClazz, current + 1)
          case None =>
            triggerTypeToOccur.put(triggerClazz, 1)
        }
      case None =>
        actorToTriggerMessages.put(actor, mutable.Map[Class[_], Int](triggerClazz -> 1))
    }
  }

  def resolved(t: TriggerWithId): Unit = {
    triggerWithIdToStartTime.get(t) match {
      case Some(startTime) =>
        val stopTime = System.nanoTime()
        val diff = TimeUnit.NANOSECONDS.toMillis(stopTime - startTime)
        val triggerClass = t.trigger.getClass
        triggerTypeToOccurrence.get(triggerClass) match {
          case Some(buffer) =>
            buffer.append(diff)
          case None =>
            val buffer = ArrayBuffer[Long](diff)
            triggerTypeToOccurrence.put(triggerClass, buffer)
        }
      case None =>
        logger.error(s"Can't find $t in triggerWithIdToStartTime")
    }
  }

  def getStat: String = {
    val sb = new mutable.StringBuilder()
    val nl = System.lineSeparator()
    triggerTypeToOccurrence.foreach { case (clazz, buf) =>
      val s = Statistics(buf.map(_.toDouble))
      sb.append(s"${nl}Type: $clazz${nl}Stats: $s$nl".stripMargin)
    }
    sb.append(s"$nl Max number of trigger messages per actor type$nl")
    getMaxPerActorType.foreach { case (actorType, triggetTypeToMaxMsgs) =>
      val s = triggetTypeToMaxMsgs.map { case (key, v) => s"\t\t$key => $v$nl" }.mkString
      val str = s"""\t$actorType => ${triggetTypeToMaxMsgs.map { case (_, v) => v }.sum}
        |$s""".stripMargin
      sb.append(str)
    }
    sb.toString()
  }

  def asStuckAgentDetectionConfig: String = {
    val maxPerActorType: Map[String, Map[String, Int]] = getMaxPerActorType
    prepareJsonConfig(maxPerActorType)
  }

  private def getMaxPerActorType: Map[String, Map[String, Int]] = {
    // Do not remove `toIterable` (Map can't contain duplicates!)
    val actorTypeToTriggers = actorToTriggerMessages.map { case (actorRef, map) =>
      getType(actorRef) -> map.map { case (k, v) => k.getName -> v }
    }

    val groupedByActorType = actorTypeToTriggers.groupBy { case (actorType, _) => actorType }

    val maxPerActorType = groupedByActorType.map { case (actorType, seq) =>
      val typeToCount = seq.values.flatten.toSeq
      val classToMaxMsgCount = typeToCount
        .groupBy { case (clazz, _) =>
          clazz
        }
        .map { case (clazz, xs) => (clazz, xs.maxBy { case (_, v) => v }._2) }
      actorType -> classToMaxMsgCount
    }
    maxPerActorType
  }

  def prepareJsonConfig(maxPerActorType: Map[String, Map[String, Int]]): String = {
    val temp = maxPerActorType.toSeq.flatMap { case (actorType, map) =>
      map.toIterator.map { case (triggerType, count) =>
        (triggerType, (actorType, count))
      }
    }
    val triggerType2MaxMessagesPerActorType = temp
      .groupBy { case (triggerType, _) => triggerType }
      .map { case (triggerType, seq) =>
        val actorType2Count = seq.map { case (_, (actorType, count)) => (actorType, count) }
        triggerType -> actorType2Count
      }
    val thresholds = triggerType2MaxMessagesPerActorType.map { case (triggerType, seq) =>
      val maxTime = triggerTypeToOccurrence(Class.forName(triggerType)).max
      val actorMax = seq.foldLeft(Thresholds$Elm.ActorTypeToMaxNumberOfMessages(None, None, None, None)) {
        case (acc, curr) =>
          val (name, count) = curr
          val newAcc = name match {
            case `transitDriverAgentName` => acc.copy(transitDriverAgent = Some(count))
            case `populationName`         => acc.copy(population = Some(count))
            case `rideHailAgentName`      => acc.copy(rideHailAgent = Some(count))
            case `rideHailManagerName`    => acc.copy(rideHailManager = Some(count))
            case x =>
              logger.error(s"Don't know what to do with $x")
              acc
          }
          newAcc
      }
      // To get default values that's why we create it from empty configuration
      val threshold = Thresholds$Elm.apply(ConfigFactory.empty())
      val finalMaxTime = if (threshold.markAsStuckAfterMs > maxTime) {
        threshold.markAsStuckAfterMs
      } else {
        maxTime
      }
      threshold.copy(
        markAsStuckAfterMs = finalMaxTime,
        triggerType = triggerType,
        actorTypeToMaxNumberOfMessages = actorMax
      )
    }.toList

    val sortedByTriggerType = thresholds.sortBy(x => x.triggerType)
    // To get default values that's why we create it from empty configuration
    val dummyCfg = ConfigFactory.parseString("thresholds = []")
    val stuckAgentDetection = StuckAgentDetection.apply(dummyCfg).copy(thresholds = sortedByTriggerType, enabled = true)
    Printer.spaces2.print(stuckAgentDetection.asJson)
  }

  private def getType(actorRef: ActorRef): String = {
    if (actorRef.path.parent.name == "router" && actorRef.path.name.indexOf("TransitDriverAgent-") != -1) {
      transitDriverAgentName
    } else if (actorRef.path.parent.name == "population" || actorRef.path.parent.parent.name == "population") {
      populationName
    } else if (actorRef.path.name.contains("rideHailAgent-")) {
      rideHailAgentName
    } else if (actorRef.path.name == "RideHailManager") {
      rideHailManagerName
    } else {
      actorRef.path.toString
    }
  }
}
