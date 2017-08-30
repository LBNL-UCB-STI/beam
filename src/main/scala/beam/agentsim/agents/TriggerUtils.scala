package beam.agentsim.agents

import akka.actor.ActorRef
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import scala.reflect.{ClassTag, _}

/**
  * BEAM
  */
object TriggerUtils {
  def completed(triggerId: Long, scheduleTriggers: Vector[ScheduleTrigger] = Vector()): CompletionNotice = {
    CompletionNotice(triggerId, scheduleTriggers)
  }
  def schedule[T <: Trigger](tick: Double, recipient: ActorRef, messageArgs: Any*)(implicit tag: scala.reflect.ClassTag[T]): Vector[ScheduleTrigger] = {
    Vector[ScheduleTrigger](scheduleOne(tick, recipient, messageArgs: _*))
  }

  // every trigger should have tick property
  def scheduleOne[T <: Trigger : ClassTag](tick: Double, recipient: ActorRef, messageArgs: Any*): ScheduleTrigger = {
    val clazz = classTag[T].runtimeClass
    val trigger = try {
      if (messageArgs.nonEmpty) {
        val varargs = (new java.lang.Double(tick) :: messageArgs.toList).toArray
        val constructors = clazz.getConstructors
        val constructor = if (constructors.length > 1) {
          val argTypes = classOf[Double] :: messageArgs.toList.map(_.getClass)
          clazz.getConstructor(argTypes.toArray: _*)
        } else {
          constructors.head
        }
        constructor.newInstance(varargs.map(_.asInstanceOf[Object]): _*)
      } else {
        val constructor = clazz.getConstructor(classOf[Double])
        constructor.newInstance(new java.lang.Double(tick))
      }
    } catch {
      case ex: Throwable =>
        ex.printStackTrace()
        throw ex
    }
    ScheduleTrigger(trigger.asInstanceOf[T], recipient)
  }

}
