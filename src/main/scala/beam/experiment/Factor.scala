package beam.experiment

import scala.beans.BeanProperty

/**
  * @author dserdiuk on 10/22/17.
  */
case class Factor(
  @BeanProperty var title: String,
  @BeanProperty var levels: java.util.List[Level]
) {
  def this() = this("", new java.util.LinkedList())

  override def toString: String = {
    s"Factor: $title, size: ${levels.size()}"
  }
}

case class Level(
  @BeanProperty var name: String,
  @BeanProperty var params: java.util.Map[String, Any]
) {
  def this() = this("", new java.util.HashMap())

  override def toString: String = {
    s"Level: ${this.name}"
  }
}
