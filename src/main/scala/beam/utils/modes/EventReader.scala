package beam.utils.modes

import beam.utils.modes.network.Link
import cats._
import cats.implicits._

import scala.xml.{XML, Node => XmlNode}

object EventReader {

  type FType = XmlNode => Boolean

  def readEvents(eventFile: String, attributesFilter: List[Filter] = List.empty): Iterator[Event] = {
    val events = XML.loadFile(eventFile)
    val filters = buildFilter(attributesFilter).toFilter

    (events \\ "event").iterator.filter(filters).map(Event.fromXML)
  }

  private def buildFilter(attributesFilter: List[Filter]): Filter = {
    Foldable[List].fold(attributesFilter)
  }

  case class Event(eventType: String,
                   mode: String,
                   links: Array[Int]) {
    def isAnyModeOf(types: String*) = types.contains(mode)

    override def toString: String = s"Event($eventType,$mode,${links.mkString("[",",","]")})"
  }

  object Event{
    def fromXML(node: XmlNode): Event = {
      val eventType = (node \@ "type")
      val mode = (node \@ "mode")
      val links = (node \@ "links").split(",").filter(_.nonEmpty).map(_.toInt)

      Event(eventType, mode, links)
    }
  }

  trait Filter {
    def toFilter: FType
  }

  case class AttributeFilter(name: String, filter: String => Boolean) extends Filter {
    val toFilter: FType = (node: XmlNode) => filter(node \@ name)
  }

  object Filter {
    def or(x: Filter*): Filter = new Filter {
      override def toFilter: FType = (node: XmlNode) => Foldable[List].foldLeft(x.map(_.toFilter(node)).toList, false)(_ || _)
    }

    def and(x: Filter*): Filter = new Filter {
      override def toFilter: FType = (node: XmlNode) => Foldable[List].foldLeft(x.map(_.toFilter(node)).toList, true)(_ && _)
    }

    implicit val FilterSemigroup: Monoid[Filter] = new Monoid[Filter]{
      override def empty: Filter = new Filter {
        override def toFilter: FType = _ => true
      }
      override def combine(x: Filter, y: Filter): Filter = Filter.and(x, y)
    }
  }

}
