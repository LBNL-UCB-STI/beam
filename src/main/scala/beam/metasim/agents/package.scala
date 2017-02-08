package beam.metasim

/**
  * Created by sfeygin on 1/24/17.
  */
package object agents {

  trait TickMsg {
    def tick: Long
  }
  case class TickMsgs(msgs: List[TickMsg])

  trait StateChangedMessage extends TickMsg

  case class NoOp(tick: Long) extends StateChangedMessage

  case object Ack

  case class Failure(reason:String)

}
