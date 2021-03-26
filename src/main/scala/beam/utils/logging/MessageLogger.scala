package beam.utils.logging

import akka.actor.FSM.Event
import akka.actor.Status.Success
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import beam.agentsim.agents.household.HouseholdActor.MobilityStatusInquiry
import beam.agentsim.infrastructure.ParkingInquiry
import beam.agentsim.scheduler.HasTriggerId
import beam.router.BeamRouter.{EmbodyWithCurrentTravelTime, RoutingRequest}
import beam.sim.BeamSim.{IterationEndsMessage, IterationStartsMessage}
import beam.utils.csv.CsvWriter
import beam.utils.logging.MessageLogger.{BeamFSMMessage, BeamMessage, BeamStateTransition, NUM_MESSAGES_PER_FILE}
import org.matsim.core.controler.OutputDirectoryHierarchy

/**
  * @author Dmitry Openkov
  */
class MessageLogger(controllerIO: OutputDirectoryHierarchy) extends Actor with ActorLogging {

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[BeamMessage])
    context.system.eventStream.subscribe(self, classOf[BeamFSMMessage])
    context.system.eventStream.subscribe(self, classOf[BeamStateTransition[Any]])
    context.system.eventStream.subscribe(self, classOf[IterationEndsMessage])
    context.system.eventStream.subscribe(self, classOf[IterationStartsMessage])
  }

  private var msgNum = 0
  private var fileNum = 0
  private var csvWriter: CsvWriter = _

  override def receive: Receive = logMessages

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
      "triggerId",
    )
  }

  def extractTriggerId(payload: Any): Long = {
    payload match {
      case hasTriggerId: HasTriggerId                   => hasTriggerId.triggerId
      case Success(status) if status.isInstanceOf[Long] => status.asInstanceOf[Long]
      case _                                            => -9998
    }
  }

  def writeMessagesToCsv(iterationNumber: Int): Receive = {
    def updateMsgNum(): Unit = {
      msgNum = msgNum + 1
      if (msgNum >= NUM_MESSAGES_PER_FILE - 1) {
        csvWriter.close()
        msgNum = 0
        fileNum = fileNum + 1
        csvWriter = createCsvWriter(iterationNumber, fileNum)
        context.become(writeMessagesToCsv(iterationNumber))
      }
    }
    {
      case BeamMessage(sender, receiver, payload) =>
        //do not process a temp sender (it's ask pattern which is published with the correct sender at LoggingAskSupport)
        if (sender.path.parent.name != "temp") {
          val (senderParent, senderName) = userFriendly(sender)
          val (receiverParent, receiverName) = userFriendly(receiver)
          val triggerId: Long = extractTriggerId(payload)
          csvWriter.write("message", senderParent, senderName, receiverParent, receiverName, payload, "", "", triggerId)
          updateMsgNum()
        }
      case BeamFSMMessage(sender, actor, event, tick, triggerId) =>
        val (senderParent, senderName) = userFriendly(sender)
        val (parent, name) = userFriendly(actor)
        csvWriter.write("event", senderParent, senderName, parent, name, event.event, event.stateData, tick, triggerId)
        updateMsgNum()
      case BeamStateTransition(sender, actor, prevState, newState, tick, triggerId) =>
        val (senderParent, senderName) = userFriendly(sender)
        val (parent, name) = userFriendly(actor)
        csvWriter.write("transition", senderParent, senderName, parent, name, prevState, newState, tick, triggerId)
        updateMsgNum()
      case IterationEndsMessage(_) =>
        csvWriter.close()
        context.become(logMessages)
    }
  }

  def logMessages: Receive = {
    case BeamMessage(sender, receiver, payload) =>
      log.debug("{} -> {}, {}", sender, receiver, payload)
    case BeamFSMMessage(_, actor, event, _, _) =>
      log.debug("FSM: actor={}, even={}, {}", actor, event.event, event.stateData)
    case BeamStateTransition(_, actor, prevState, newState, _, _) =>
      log.debug("State: actor={}, {} -> {}", actor, prevState, newState)
    case IterationStartsMessage(iterationNumber) =>
      msgNum = 0
      fileNum = 0
      csvWriter = createCsvWriter(iterationNumber, fileNum)
      context.become(writeMessagesToCsv(iterationNumber))
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

  case class BeamMessage(sender: ActorRef, receiver: ActorRef, payload: Any)
  case class BeamFSMMessage(sender: ActorRef, actor: ActorRef, event: Event[_], tick: Int, triggerId: Long)
  case class BeamStateTransition[S](
    sender: ActorRef,
    actor: ActorRef,
    prevState: S,
    newState: S,
    tick: Int,
    triggerId: Long
  )

  def props(controllerIO: OutputDirectoryHierarchy): Props = Props(new MessageLogger(controllerIO))
}
