package beam.utils.scripts

import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Scenario
import scripts.ScenarioCsvWriter

import scala.collection.JavaConverters._

object NetworkLinkCsvWriter extends ScenarioCsvWriter with StrictLogging {

  private case class LinkEntry(
                                linkId: String, linkFrom: String, linkTo: String,
                                linkLength: String, linkFreespeed: String, linkCapacity: Double,
                                linkPermLanes: String, linkOneWay: String, linkModes: String,
                                attributeOrigId: String, attributeOrigType: String
                              ) {
    override def toString: String = {
      Seq(linkId, linkFrom, linkTo,
        linkLength, linkFreespeed, linkCapacity,
        linkPermLanes, linkOneWay, linkModes,
        attributeOrigId, attributeOrigType
      ).mkString("", FieldSeparator, LineSeparator)
    }
  }

  override protected val fields: Seq[String] = {
    Seq("linkId", "linkFrom", "linkTo", "linkLength", "linkFreeSpeed", "linkCapacity",
      "linkPermLanes", "linkOneWay", "linkModes", "attributeOrigId", "attributeOrigType")
  }

  override def contentIterator(scenario: Scenario): Iterator[String] = {
    // TODO: Fix the fields below:
    //    A: is permLanes the same as numberOfLanes?
    //    B, C, D: where this information can be found?
    scenario.getNetwork.getLinks.values().asScala
      .toIterator
      .map { n =>
        val linkModeAsString =
          n.getAllowedModes.asScala
            .filterNot(_ == "null") // TODO: why null is added to the set?
            .mkString(ArrayStartString, ArrayItemSeparator, ArrayEndString)
        LinkEntry(
          linkId = n.getId.toString,
          linkFrom = n.getFromNode.getId.toString,
          linkTo = n.getToNode.getId.toString,
          linkLength = n.getLength.toString,
          linkFreespeed = n.getFreespeed.toString,
          linkCapacity = n.getCapacity(),
          linkPermLanes = n.getNumberOfLanes.toString, // A
          linkOneWay = "", // B
          linkModes = linkModeAsString,
          attributeOrigId = "", // C
          attributeOrigType = "" // D
        ).toString
      }
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