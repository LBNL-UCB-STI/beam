package beam.utils.logging

import akka.actor.FSM.Event
import akka.actor.Status.Success
import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.scheduler.HasTriggerId
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.utils.csv.CsvWriter
import beam.utils.logging.MessageLogger._
import org.matsim.core.controler.OutputDirectoryHierarchy

import scala.concurrent.duration.DurationInt

/**
  * @author Dmitry Openkov
  */
class MessageLogger(iterationNumber: Int, controllerIO: OutputDirectoryHierarchy) extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[BeamMessage])
  context.system.eventStream.subscribe(self, classOf[BeamFSMMessage])
  context.system.eventStream.subscribe(self, classOf[BeamStateTransition[Any]])
  context.system.eventStream.subscribe(self, Finish.getClass)

  private var msgNum = 0
  private var fileNum = 0
  private var csvWriter: CsvWriter = createCsvWriter(iterationNumber, fileNum)

  /**
    * We put in this var a cancellable for scheduled CloseWriterMsg so that we could close our writer
    * if we don't receive messages for a long time (the simulation got stuck)
    */
  private var closeWriterCancellable: Cancellable =
    context.system.scheduler.scheduleOnce(24.hours, self, CloseWriterMsg)(context.system.dispatcher)

  override def receive: Receive = {
    case BeamMessage(sender, receiver, payload) =>
      //do not process a temp sender (it's ask pattern which is published with the correct sender at LoggingAskSupport)
      if (sender.path.parent.name != "temp") {
        val (senderParent, senderName) = userFriendly(sender)
        val (receiverParent, receiverName) = userFriendly(receiver)
        val (tick, triggerId) = extractTickAndTriggerId(payload)
        csvWriter.write(
          "message",
          senderParent,
          senderName,
          receiverParent,
          receiverName,
          payload,
          "",
          tick,
          triggerId
        )
        handleCsvWriter()
      }
    case BeamFSMMessage(sender, actor, event, tick, agentTriggerId) =>
      val (senderParent, senderName) = userFriendly(sender)
      val (parent, name) = userFriendly(actor)
      val (payloadTick, eventTriggerId) = extractTickAndTriggerId(event.event)
      val triggerId = Math.max(agentTriggerId, eventTriggerId)
      val actualTick = Math.max(tick, payloadTick)
      csvWriter.write(
        "event",
        senderParent,
        senderName,
        parent,
        name,
        event.event,
        event.stateData,
        actualTick,
        triggerId
      )
      handleCsvWriter()
    case BeamStateTransition(sender, actor, prevState, newState, tick, triggerId) =>
      val (senderParent, senderName) = userFriendly(sender)
      val (parent, name) = userFriendly(actor)
      csvWriter.write("transition", senderParent, senderName, parent, name, prevState, newState, tick, triggerId)
      handleCsvWriter()
    case CloseWriterMsg =>
      log.debug(s"Got CloseWriterMsg")
      val itIsWorthClosing = fileNum < 20 || msgNum >= 1000
      if (itIsWorthClosing) {
        log.debug(s"Closing current writer")
        closeWriterAndCreateNextOne()
      }
      scheduleNextCloseWriterMsg()
    case Finish =>
      log.debug(s"Finishing iteration")
      closeWriterCancellable.cancel()
      csvWriter.close()
      context.stop(self)
  }

  private def createCsvWriter(iterationNumber: Int, fileNum: Int) = {
    CsvWriter(
      controllerIO.getIterationFilename(iterationNumber, s"actor_messages_$fileNum.csv.gz"),
      "type",
      "sender_parent",
      "sender_name",
      "receiver_parent",
      "receiver_name",
      "payload",
      "state",
      "tick",
      "triggerId"
    )
  }

  def extractTickAndTriggerId(payload: Any): (Int, Long) = {
    payload match {
      case TriggerWithId(trigger, triggerId) => (trigger.tick, triggerId)
      case hasTriggerId: HasTriggerId        => (-1, hasTriggerId.triggerId)
      case Success(status: Long)             => (-1, status)
      case (x: HasTriggerId, _)              => (-1, x.triggerId)
      case _                                 => (-1, -1)
    }
  }

  private def handleCsvWriter(): Unit = {
    if (msgNum % 100000 == 0) {
      scheduleNextCloseWriterMsg()
    }
    msgNum = msgNum + 1
    if (msgNum >= NUM_MESSAGES_PER_FILE - 1) {
      closeWriterAndCreateNextOne()
    }
  }

  private def scheduleNextCloseWriterMsg(): Unit = {
    closeWriterCancellable.cancel()
    closeWriterCancellable =
      context.system.scheduler.scheduleOnce(60.seconds, self, CloseWriterMsg)(context.system.dispatcher)
  }

  private def closeWriterAndCreateNextOne(): Unit = {
    csvWriter.close()
    msgNum = 0
    fileNum = fileNum + 1
    csvWriter = createCsvWriter(iterationNumber, fileNum)
  }

  override def postStop(): Unit = {
    if (csvWriter != null) {
      csvWriter.close()
    }
  }

  private def userFriendly(actorRef: ActorRef) = {
    val parent = userFriendlyParent(actorRef)
    (parent, actorRef.path.name)
  }

  private def userFriendlyParent(actorRef: ActorRef) = {
    val parentElements = actorRef.path.parent.elements.dropWhile(e => e != "BeamMobsim.iteration" && e != "temp").toList
    val meaningful = if (parentElements.size <= 1) {
      parentElements
    } else if (parentElements.head == "BeamMobsim.iteration") {
      parentElements.drop(1)
    } else {
      parentElements
    }
    val parent = meaningful.mkString("/")
    parent
  }
}

object MessageLogger {
  val NUM_MESSAGES_PER_FILE = 10000000

  object CloseWriterMsg
  case class BeamMessage(sender: ActorRef, receiver: ActorRef, payload: Any)
  case class BeamFSMMessage(sender: ActorRef, actor: ActorRef, event: Event[_], tick: Int, agentTriggerId: Long)

  case class BeamStateTransition[S](
    sender: ActorRef,
    actor: ActorRef,
    prevState: S,
    newState: S,
    tick: Int,
    triggerId: Long
  )

  def props(iterationNumber: Int, controllerIO: OutputDirectoryHierarchy): Props =
    Props(new MessageLogger(iterationNumber, controllerIO))
}
