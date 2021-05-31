package beam.utils.beam_to_matsim

import beam.utils.beam_to_matsim.utils.{LinkCoordinate, Point}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

class LinkCoordinateTest extends AnyFlatSpecLike with Matchers {
  "parser" should "parse attributes" in {
    val linkxml = (<links>
        <link id="91712" to="28921" from="34052.5465465"/>
      </links> \ "link").head

    LinkCoordinate.intAttributeValue(linkxml, "id") shouldBe Some(91712)
    LinkCoordinate.doubleAttributeValue(linkxml, "from") shouldBe Some(34052.5465465)
  }

  "parser" should "parse whole network" in {
    val xml =
      <network>
        <nodes>
          <node id="34052" x="551370.8722547909" y="4183680.3650971777" />
          <node id="28921" x="551296.6152890497" y="4183668.1740714703" />
          <node id="34053" x="551296.1232134345" y="5466765.1740714703" />
          <node id="34050" x="551296.6575243242" y="8645341.1740714703" />
        </nodes>
        <links capperiod="01:00:00" effectivecellsize="7.5" effectivelanewidth="3.75">
          <link id="91712" to="28921" from="34052" />
          <link modes="car,walk,bike" oneway="1" permlanes="1.0" capacity="300.0" freespeed="2.7777777777777777" length="5.21" to="34050" from="34053" id="91716"/>
        </links>
      </network>

    val errors = mutable.MutableList.empty[String]

    val map = LinkCoordinate.parseNetwork(xml)
    map shouldBe Map(
      91712 -> LinkCoordinate(
        Point(551370.8722547909, 4183680.3650971777),
        Point(551296.6152890497, 4183668.1740714703)
      ),
      91716 -> LinkCoordinate(
        Point(551296.1232134345, 5466765.1740714703),
        Point(551296.6575243242, 8645341.1740714703)
      )
    )

  }
}
