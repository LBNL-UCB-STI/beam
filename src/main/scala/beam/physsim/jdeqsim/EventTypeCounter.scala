package beam.physsim.jdeqsim

import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.core.events.handler.BasicEventHandler

import scala.collection.mutable

class EventTypeCounter extends BasicEventHandler with StrictLogging {
  private val typeToNumberOfMessages = new mutable.HashMap[Class[_], Long]

  override def handleEvent(event: Event): Unit = {
    val clazz = event.getClass
    val prevValue = typeToNumberOfMessages.getOrElse(clazz, 0L)
    val newValue = prevValue + 1
    typeToNumberOfMessages.update(clazz, newValue)
  }

  def getStats: Seq[(String, Long)] = {
    typeToNumberOfMessages.map { case (clazz, cnt) => clazz.getSimpleName -> cnt }.toList.sortBy { case (clazz, _) =>
      clazz
    }
  }
}
