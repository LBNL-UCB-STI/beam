package beam.utils.logging

import akka.actor.FSM.Event
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import beam.agentsim.agents.household.HouseholdActor.MobilityStatusInquiry
import beam.agentsim.infrastructure.ParkingInquiry
import beam.router.BeamRouter.{EmbodyWithCurrentTravelTime, RoutingRequest}
import beam.sim.BeamSim.{IterationEndsMessage, IterationStartsMessage}
import beam.utils.csv.CsvWriter
import beam.utils.logging.MessageLogger.{BeamFSMMessage, BeamMessage, NUM_MESSAGES_PER_FILE}
import org.matsim.core.controler.OutputDirectoryHierarchy

/**
  * @author Dmitry Openkov
  */
class MessageLogger(controllerIO: OutputDirectoryHierarchy) extends Actor with ActorLogging {

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[BeamMessage])
    context.system.eventStream.subscribe(self, classOf[BeamFSMMessage])
  }

  private var msgNum = 0
  private var fileNum = 0

  override def receive: Receive = writeMessagesToRootWriter

  private def createCsvWriter(iterationNumber: Int, fileNum: Int) = {
    CsvWriter(
      controllerIO.getIterationFilename(iterationNumber, s"actor_messages_$fileNum.csv.gz"),
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

  def tryExtractingSenderId(payload: Any): String = {
    payload match {
      case x: MobilityStatusInquiry       => x.personId.toString
      case x: RoutingRequest              => x.statisticId
      case x: ParkingInquiry              => x.beamVehicle.map(_.toString).getOrElse("?pi")
      case x: EmbodyWithCurrentTravelTime => x.vehicleId.toString
      case _                              => s"Unknown class: ${payload.getClass.getSimpleName}"
    }
  }

  def writeMessagesTo(csvWriter: CsvWriter, iterationNumber: Int): Receive = {
    def updateMsgNum(): Unit = {
      msgNum = msgNum + 1
      if (msgNum >= NUM_MESSAGES_PER_FILE - 1) {
        csvWriter.flush()
        csvWriter.close()
        msgNum = 0
        fileNum = fileNum + 1
        context.become(writeMessagesTo(createCsvWriter(iterationNumber, fileNum), iterationNumber))
      }
    }
    {
      case BeamMessage(sender, receiver, payload) =>
        val (senderParent, senderName) = userFriendly(sender, payload)
        val (receiverParent, receiverName) = userFriendly(receiver, payload)
        csvWriter.write(senderParent, senderName, receiverParent, receiverName, payload, "", "", "")
        updateMsgNum()
      case BeamFSMMessage(sender, actor, event, tick, triggerId) =>
        val (senderParent, senderName) = userFriendly(sender, event.event)
        val (parent, name) = userFriendly(actor, event.event)
        csvWriter.write(senderParent, senderName, parent, name, event.event, event.stateData, tick, triggerId)
        updateMsgNum()
      case IterationEndsMessage(_) =>
        csvWriter.flush()
        csvWriter.close()
        context.become(writeMessagesToRootWriter)
    }
  }

  def writeMessagesToRootWriter: Receive = {
    case BeamMessage(sender, receiver, payload) =>
      log.debug("{} -> {}, {}", sender, receiver, payload)
    case BeamFSMMessage(_, actor, event, _, _) =>
      log.debug("FSM: actor={}, even={}, {}", actor, event.event, event.stateData)
    case IterationStartsMessage(iterationNumber) =>
      msgNum = 0
      fileNum = 0
      context.become(writeMessagesTo(createCsvWriter(iterationNumber, fileNum), iterationNumber))
  }

  private def userFriendly(actorRef: ActorRef, payload: Any) = {
    val parentElements = actorRef.path.parent.elements.dropWhile(e => e != "BeamMobsim.iteration" && e != "temp").toList
    val meaningful = if (parentElements.size <= 1) {
      parentElements
    } else if (parentElements.head == "BeamMobsim.iteration") {
      parentElements.drop(1)
    } else {
      parentElements
    }
    val parent = meaningful.mkString("/")
    val name = if (parent == "temp") { //means ask pattern
      tryExtractingSenderId(payload)
    } else {
      actorRef.path.name
    }
    (parent, name)
  }

}

object MessageLogger {
  val NUM_MESSAGES_PER_FILE = 10000000

  case class BeamMessage(sender: ActorRef, receiver: ActorRef, payload: Any)
  case class BeamFSMMessage(sender: ActorRef, actor: ActorRef, event: Event[_], tick: Int, triggerId: Long)

  def props(controllerIO: OutputDirectoryHierarchy): Props = Props(new MessageLogger(controllerIO))
}
