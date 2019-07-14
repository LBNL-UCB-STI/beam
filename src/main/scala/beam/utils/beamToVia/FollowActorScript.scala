package beam.utils.beamToVia

import beam.utils.beamToVia.viaEvent.{EnteredLink, LeftLink, ViaEvent, ViaTraverseLinkEvent}

import scala.collection.mutable

object FollowActorScript {

  def build(
    events: Traversable[ViaEvent],
    frameSizeX: Int,
    frameSizeY: Int,
    deltaTimeAllowed: Int,
    getLinkStart: Int => Option[Point],
    getLinkEnd: Int => Option[Point]
  ): Seq[String] = {
    trait ViaScriptRow {
      def toStr: String
    }

    case class ViaScriptFlyTo(minX: Double, minY: Double, maxX: Double, maxY: Double) extends ViaScriptRow {
      override def toStr: String = {
        // via.view.flyTo(minX, minY, maxX, maxY, number of frames for flying)
        "via.view.flyTo(minFrameX(%f), minFrameY(%f), maxFrameX(%f), maxFrameY(%f), framesToFly)".format(
          minX,
          minY,
          maxX,
          maxY
        )
      }
    }

    case class ViaScriptSetTime(time: Double) extends ViaScriptRow {
      override def toStr: String = "via.setTime(%f)".format(time)

    }

    case class ViaScriptWaitTime(time: Double) extends ViaScriptRow {
      override def toStr: String = "via.sleep(calcSleepTime(%f))".format(time)
    }

    case class ViaScriptString(str: String) extends ViaScriptRow {
      override def toStr: String = str
    }

    val initScriptList =
      mutable.MutableList[ViaScriptRow](
        ViaScriptString("function minFrameX(x){return x - " + frameSizeX / 2 + "}"),
        ViaScriptString("function minFrameY(y){return y - " + frameSizeY / 2 + "}"),
        ViaScriptString("function maxFrameX(x){return x + " + frameSizeX / 2 + "}"),
        ViaScriptString("function maxFrameY(y){return y + " + frameSizeY / 2 + "}"),
        ViaScriptString(""),
        ViaScriptString("speed = 3"),
        ViaScriptString("function calcSleepTime(time){return time * 27.5 / speed}"),
        ViaScriptString("via.setTimeIncrement(speed)"),
        ViaScriptString(""),
        ViaScriptString("framesToFly = 30"),
        ViaScriptString(""),
      )

    case class Frame(minX: Double, minY: Double, maxX: Double, maxY: Double) {
      private val dX = (maxX - minX) / 4
      private val dY = (maxY - minY) / 4

      def isWithin(x: Double, y: Double): Boolean = {
        minX + dX <= x && x <= maxX - dY && minY + dY <= y && y <= maxY - dY
      }
    }

    case class ScriptAccumulator(
      script: mutable.MutableList[ViaScriptRow] = initScriptList,
      var lastTime: Option[Double] = None,
      var frame: Option[Frame] = None,
      var lastScriptRow: Option[ViaScriptRow] = None
    ) {
      def addScript(viaScript: ViaScriptRow): Unit = {
        (lastScriptRow, viaScript) match {
          case (Some(waitTime1: ViaScriptWaitTime), waitTime2: ViaScriptWaitTime) =>
            lastScriptRow = Some(ViaScriptWaitTime(waitTime1.time + waitTime2.time))
          case (Some(scriptRow), newScript) =>
            script += scriptRow
            lastScriptRow = Some(newScript)
          case (_, newScript) =>
            lastScriptRow = Some(newScript)
        }
      }

      def flush(): Unit = lastScriptRow match {
        case Some(scriptRow) =>
          script += scriptRow
          script += ViaScriptString("via.setTimeIncrement(0)")

          lastScriptRow = None
        case _ =>
      }

      def moveTo(nextTime: Double, nextCoordinate: Point): Unit = {
        lastTime match {
          case None =>
            addScript(ViaScriptSetTime(nextTime))

          case Some(prevTime) =>
            val deltaTime = nextTime - prevTime
            if (deltaTime >= deltaTimeAllowed) addScript(ViaScriptSetTime(nextTime))
            else if (deltaTime >= 0.001) addScript(ViaScriptWaitTime(deltaTime))
        }

        lastTime = Some(nextTime)

        def setFrame(): Unit = {
          val f = Frame(
            nextCoordinate.x - frameSizeX / 2,
            nextCoordinate.y - frameSizeY / 2,
            nextCoordinate.x + frameSizeX / 2,
            nextCoordinate.y + frameSizeY / 2
          )

          frame = Some(f)

          addScript(ViaScriptFlyTo(f.minX, f.minY, f.maxX, f.maxY))
        }

        frame match {
          case None                                                       => setFrame()
          case Some(f) if !f.isWithin(nextCoordinate.x, nextCoordinate.y) => setFrame()
          case _                                                          =>
        }
      }
    }

    val accumulator = events.foldLeft(ScriptAccumulator())((acc, event) => {
      event match {
        case ViaTraverseLinkEvent(time, _, EnteredLink, link) =>
          getLinkStart(link) match {
            case Some(point) => acc.moveTo(time, point)
            case _           =>
          }
        case ViaTraverseLinkEvent(time, _, LeftLink, link) =>
          getLinkEnd(link) match {
            case Some(point) => acc.moveTo(time, point)
            case _           =>
          }

        case _ =>
      }

      acc
    })

    accumulator.flush()
    accumulator.script.map(_.toStr)
  }
}
