package beam.utils.beamToVia

import beam.utils.beamToVia.viaEvent.{EnteredLink, LeftLink, ViaEvent, ViaTraverseLinkEvent}

import scala.collection.mutable

object FollowActorScript {

  def build(
    events: Traversable[ViaEvent],
    getLinkStart: Int => Option[Point],
    getLinkEnd: Int => Option[Point]
  ): Seq[String] = {
    trait ViaScriptRow {
      def toStr: String
    }

    case class ViaScriptFlyTo(time: Double, from: Point, to: Point) extends ViaScriptRow {
      override def toStr: String = {
        val minX = Math.min(from.x, to.x)
        val minY = Math.min(from.y, to.y)
        val maxX = Math.max(from.x, to.x)
        val maxY = Math.max(from.y, to.y)

        // via.view.flyTo(minX, minY, maxX, maxY, number of frames for flying)
        "via.view.flyTo(minFrameX(%f), minFrameY(%f), maxFrameX(%f), maxFrameY(%f), timeToFrames(%f))".format(
          minX,
          minY,
          maxX,
          maxY,
          time
        )
      }
    }

    case class ViaScriptSetTime(time: Double) extends ViaScriptRow {
      override def toStr: String = {
        "via.setTime(%f)".format(time)
      }
    }

    case class ViaScriptString(str: String) extends ViaScriptRow {
      override def toStr: String = str
    }

    val initScriptList =
      mutable.MutableList[ViaScriptRow](
        ViaScriptString("function minFrameX(x){return x - 1500}"),
        ViaScriptString("function minFrameY(y){return y - 1500}"),
        ViaScriptString("function maxFrameX(x){return x + 1500}"),
        ViaScriptString("function maxFrameY(y){return y + 1500}"),
        ViaScriptString("function timeToFrames(t){return t * 7}"),
        ViaScriptString(""),
        ViaScriptString("via.setTimeIncrement(0.1)"),
        ViaScriptString(""),
      )

    case class Accumulator(
      script: mutable.MutableList[ViaScriptRow] = initScriptList,
      var fromCoord: Option[Point] = None,
      var fromTime: Option[Double] = None,
      var settedTime: Option[Double] = None
    ) {

      def enterLink(time: Double, coord: Point): Unit = {
        fromTime = Some(time)
        fromCoord = Some(coord)

        def setTime(t: Double): Unit = {
          script += ViaScriptSetTime(t)
          settedTime = Some(t)
        }

        settedTime match {
          case Some(prevValue) if time - prevValue > 2 => setTime(time)
          case None                                    => setTime(time)
          case _                                       =>
        }
      }

      def leftLink(leftTime: Double, toCoord: Point): Unit = {
        (fromCoord, fromTime) match {
          case (Some(coord), Some(time)) if leftTime - time > 0 =>
            script += ViaScriptFlyTo(leftTime - time, coord, toCoord)
          case _ =>
        }
      }
    }

    val accumulator = events.foldLeft(Accumulator())((acc, event) => {
      event match {
        case ViaTraverseLinkEvent(time, _, EnteredLink, link) =>
          getLinkStart(link) match {
            case Some(point) => acc.enterLink(event.time, point)
            case _           =>
          }
        case ViaTraverseLinkEvent(time, _, LeftLink, link) =>
          getLinkEnd(link) match {
            case Some(point) => acc.leftLink(event.time, point)
            case _           =>
          }

        case _ =>
      }

      acc
    })

    accumulator.script.map(_.toStr)
  }
}
