package beam.utils.scripts

import beam.utils.csv.ScenarioCsvWriter
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Scenario

import scala.collection.JavaConverters._

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


/*
<node id="97" x="169369.90979769238" y="4432.722333451986" >
</node>
<node id="98" x="170484.1603938465" y="4421.61412165593" >
</node>
<node id="99" x="170484.16059349035" y="4427.148056740047" >
</node>
</nodes>

<!-- ====================================================================== -->

<links capperiod="01:00:00" effectivecellsize="7.5" effectivelanewidth="3.75">
<link id="0" from="0" to="1" length="11.11" freespeed="22.22222222222222" capacity="2000.0" permlanes="1.0" oneway="1" modes="null,car,walk,bike" >
<attributes>
<attribute name="origid" class="java.lang.String" >1</attribute>
<attribute name="type" class="java.lang.String" >trunk</attribute>
</attributes>
</link>
<link id="1" from="1" to="0" length="11.11" freespeed="22.22222222222222" capacity="2000.0" permlanes="1.0" oneway="1" modes="null,walk" >
<attributes>
<attribute name="origid" class="java.lang.String" >1</attribute>
<attribute name="type" class="java.lang.String" >trunk</attribute>
</attributes>
</link>

 */