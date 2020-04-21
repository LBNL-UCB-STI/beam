package beam.utils.scenario

import org.matsim.utils.objectattributes.ObjectAttributes

trait ObjectAttributesScala {
  def putAttribute[T](objectId: String, attributeName: String, value: T): T
  def getAttribute[T](objectId: String, attributeName: String): Option[T]

  def getAttribute[T](objectId: String, attributeName: String, defaultValue: => T): T =
    getAttribute(objectId, attributeName).getOrElse(defaultValue)
  def removeAttribute(objectId: String, attributeName: String)
  def removeAllAttributes(objectId: String)
  def clear()
}

object ObjectAttributesScala {

  import scala.language.implicitConversions

  implicit def objectAttributesScala(objectAttributes: ObjectAttributes): ObjectAttributesScala =
    new ObjectAttributesScala {
      override def putAttribute[T](objectId: String, attributeName: String, value: T): T =
        objectAttributes.putAttribute(objectId, attributeName, value).asInstanceOf[T]

      override def getAttribute[T](objectId: String, attributeName: String): Option[T] =
        Option(objectAttributes.getAttribute(objectId, attributeName).asInstanceOf[T])

      override def removeAttribute(objectId: String, attributeName: String): Unit =
        objectAttributes.removeAttribute(objectId, attributeName)

      override def clear(): Unit = objectAttributes.clear()

      override def removeAllAttributes(objectId: String): Unit = objectAttributes.removeAllAttributes(objectId)
    }
}
