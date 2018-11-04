package beam.integration

import org.matsim.api.core.v01.events.Event
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}

object ReadEventsBeam {

  def fromFile(filePath: String): Traversable[Event] =
    new Traversable[Event] {
      override def foreach[U](f: Event => U): Unit = {
        val eventsManager = EventsUtils.createEventsManager()
        eventsManager.addHandler(new BasicEventHandler {
          def handleEvent(event: Event): Unit = f(event)
        })
        new MatsimEventsReader(eventsManager).readFile(filePath)
      }
    }

}
