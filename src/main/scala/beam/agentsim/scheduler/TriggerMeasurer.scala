package beam.agentsim.scheduler

import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.utils.Statistics
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/*
  This is dumb implementation. We store all the data to build percentiles. There are different approaches to get percentile on stream
  More: https://blog.superfeedr.com/streaming-percentiles/
 */
class TriggerMeasurer extends LazyLogging {
  private val triggerWithIdToStartTime: mutable.Map[TriggerWithId, Long] =
    mutable.Map[TriggerWithId, Long]()
  private val triggerTypeToOccurrence: mutable.Map[Class[_], ArrayBuffer[Long]] =
    mutable.Map[Class[_], ArrayBuffer[Long]]()
  private val actorToNumOfTriggerMessages: mutable.Map[ActorRef, mutable.Map[Class[_], Int]] =
    mutable.Map[ActorRef, mutable.Map[Class[_], Int]]()

  def sent(t: TriggerWithId, actor: ActorRef): Unit = {
    triggerWithIdToStartTime.put(t, System.nanoTime())

    val triggerClazz = t.trigger.getClass
    actorToNumOfTriggerMessages.get(actor) match {
      case Some(triggerTypeToOccur) =>
        triggerTypeToOccur.get(triggerClazz) match {
          case Some(current) =>
            triggerTypeToOccur.update(triggerClazz, current + 1)
          case None =>
            triggerTypeToOccur.put(triggerClazz, 1)
        }
      case None =>
        actorToNumOfTriggerMessages.put(actor, mutable.Map[Class[_], Int](triggerClazz -> 1))
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
    triggerTypeToOccurrence.foreach {
      case (clazz, buf) =>
        val s = Statistics(buf.map(_.toDouble))
        sb.append(s"${nl}Type: $clazz${nl}Stats: $s$nl".stripMargin)
    }

    sb.append(s"${nl}Max number of trigger messages per actor type${nl}")
    // Do not remove `toIterable` (Map can't contain duplicates!)
    val actorTypeToTriggers: Iterable[(String, mutable.Map[Class[_], Int])] =
      actorToNumOfTriggerMessages.toIterable.map {
        case (actorRef, map) =>
          getType(actorRef) -> map
      }
    val maxPerActorType = actorTypeToTriggers
      .groupBy { case (actorType, _) => actorType }
      .map { case (actorType, seq) => actorType -> seq.view.maxBy { case (_, map) => map.values.size }._2 }

    maxPerActorType.foreach {
      case (actorType, map) =>
        val s = map.map { case (key, v) => s"\t\t${key} => $v$nl" }.mkString
        val str = s"""\t$actorType => ${map.values.sum}
        |$s""".stripMargin
        sb.append(str)
    }
    sb.toString()
  }

  private def getType(actorRef: ActorRef): String = {
    if (actorRef.path.parent.name == "router" && actorRef.path.name.indexOf("TransitDriverAgent-") != -1) {
//      val idx = actorRef.path.name.indexOf("TransitDriverAgent-")
//      val vehicleTypeAndOther = actorRef.path.name.substring(idx + "TransitDriverAgent-".length)
      "TransitDriverAgent"
    } else if (actorRef.path.parent.parent.name == "population") {
      "Population"
    } else if (actorRef.path.name.contains("rideHailAgent-")) {
      "RideHailAgent"
    } else if (actorRef.path.name == "RideHailManager") {
      "RideHailManager"
    } else {
      actorRef.path.toString
    }
  }
}
