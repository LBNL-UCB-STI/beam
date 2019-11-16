package akka.actor

trait BeamLoggingFSM[S, D] extends FSM[S, D] { this: Actor =>

  import FSM._

  def logDepth: Int = 0

  private[akka] override val debugEvent = context.system.settings.FsmDebugEvent

  private val events = new Array[Event](logDepth)
  private val states = new Array[AnyRef](logDepth)
  private var pos = 0
  private var full = false

  private def advance(): Unit = {
    val n = pos + 1
    if (n == logDepth) {
      full = true
      pos = 0
    } else {
      pos = n
    }
  }

  private[akka] abstract override def processEvent(event: Event, source: AnyRef): Unit = {
    if (debugEvent) {
      val srcstr = source match {
        case s: String               => s
        case Timer(name, _, _, _, _) => "timer " + name
        case a: ActorRef             => a.toString
        case _                       => "unknown"
      }
      log.debug("###BEAM### {} processing {} from {} in state {}", this.self, event, srcstr, stateName)
    }

    if (logDepth > 0) {
      states(pos) = stateName.asInstanceOf[AnyRef]
      events(pos) = event
      advance()
    }

    val oldState = stateName
    super.processEvent(event, source)
    val newState = stateName

    if (debugEvent && oldState != newState)
      log.debug("transition " + oldState + " -> " + newState)
  }

  /**
   * Retrieve current rolling log in oldest-first order. The log is filled with
   * each incoming event before processing by the user supplied state handler.
   * The log entries are lost when this actor is restarted.
   */
  protected def getLog: IndexedSeq[LogEntry[S, D]] = {
    val log =
      events.zip(states).filter(_._1 ne null).map(x => LogEntry(x._2.asInstanceOf[S], x._1.stateData, x._1.event))
    if (full) {
      IndexedSeq() ++ log.drop(pos) ++ log.take(pos)
    } else {
      IndexedSeq() ++ log
    }
  }

}
