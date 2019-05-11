package beam.utils.csv.writers

import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Scenario
import collection.JavaConverters._

object NetworkNodeCsvWriter extends ScenarioCsvWriter with StrictLogging {

  private case class NodeEntry(nodeId: String, locationX: String, locationY: String) {
    override def toString: String = {
      Seq(nodeId, locationX, locationY).mkString("", FieldSeparator, LineSeparator)
    }
  }

  override protected val fields: Seq[String] = Seq("nodeId", "locationX", "locationY")

  override def contentIterator(scenario: Scenario): Iterator[String] = {
    scenario.getNetwork.getNodes.values().asScala
      .toIterator
      .map(n => NodeEntry(n.getId.toString, n.getCoord.getX.toString, n.getCoord.getY.toString).toString)
  }

}
