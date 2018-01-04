package beam.integration

import java.io.File

import beam.sim.RunBeam
import beam.sim.config.BeamConfig
import com.typesafe.config.{Config, ConfigValueFactory}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

/**
  * Created by fdariasm on 29/08/2017
  * 
  */

class EventsFileSpec extends FlatSpec with BeforeAndAfterAll with Matchers with RunBeam with
  EventsFileHandlingCommon with IntegrationSpecCommon{

  private val config: Config = baseConfig
    .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml,csv"))
    .withValue("beam.routing.transitOnStreetNetwork", ConfigValueFactory.fromAnyRef("true"))
    .resolve()

  val beamConfig = BeamConfig(config)
  var matsimConfig: org.matsim.core.config.Config = null

  override protected def beforeAll(): Unit = {
    (matsimConfig, _) = runBeamWithConfig(config)
  }

  it should "contain all bus routes" in {
    val listTrips = getListIDsWithTag(new File(s"${beamConfig.beam.inputDirectory}/r5/bus/trips.txt"), "route_id", 2).sorted
    val listValueTagEventFile = new ReadEventsBeam().getListTagsFromFile(getEventsFilePath(matsimConfig , "xml"),Some("vehicle_type", "bus"),"vehicle_id").groupBy(identity)
    listValueTagEventFile.size shouldBe listTrips.size
  }

  it should "contain all train routes" in {
    val listTrips = getListIDsWithTag(new File(s"${beamConfig.beam.inputDirectory}/r5/train/trips.txt"), "route_id", 2).sorted
    val listValueTagEventFile = new ReadEventsBeam().getListTagsFromFile(getEventsFilePath(matsimConfig , "xml"), Some("vehicle_type", "subway"),"vehicle_id").groupBy(identity)
    listValueTagEventFile.size shouldBe listTrips.size
  }

  it should "contain the same bus trips entries" in {
    val listTrips = getListIDsWithTag(new File(s"${beamConfig.beam.inputDirectory}/r5/bus/trips.txt"), "route_id", 2).sorted
    val listValueTagEventFile = new ReadEventsBeam().getListTagsFromFile(getEventsFilePath(matsimConfig , "xml"), Some("vehicle_type", "bus"), "vehicle_id").groupBy(identity).keys.toSeq
    val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
    listTripsEventFile shouldBe listTrips
  }

  it should "contain the same train trips entries" in {
    val listTrips = getListIDsWithTag(new File(s"${beamConfig.beam.inputDirectory}/r5/train/trips.txt"), "route_id", 2).sorted
    val listValueTagEventFile = new ReadEventsBeam().getListTagsFromFile(getEventsFilePath(matsimConfig , "xml"),Some("vehicle_type", "subway"),"vehicle_id").groupBy(identity).keys.toSeq
    val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
    listTripsEventFile shouldBe listTrips
  }

  it should "contain same pathTraversal defined at stop times file for bus input file" ignore {
    val listTrips = getListIDsWithTag(new File(s"${beamConfig.beam.inputDirectory}/r5/bus/stop_times.txt"), "trip_id", 0).sorted
    val grouped = listTrips.groupBy(identity)
    val groupedWithCount = grouped.map{case (k, v) => (k, v.size)}
    val listValueTagEventFile = new ReadEventsBeam().getListTagsFromFile(getEventsFilePath(matsimConfig, "xml"), Some("vehicle_type", "bus"),"vehicle_id", Some("PathTraversal"))
    val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1))
    val groupedXml = listTripsEventFile.groupBy(identity)
    val groupedXmlWithCount = groupedXml.map{case (k,v) => (k, v.size)}
    groupedXmlWithCount should contain theSameElementsAs groupedWithCount
  }

  it should "contain same pathTraversal defined at stop times file for train input file" ignore {
    val listTrips = getListIDsWithTag(new File(s"${beamConfig.beam.inputDirectory}/r5/train/stop_times.txt"), "trip_id", 0).sorted
    val grouped = listTrips.groupBy(identity)
    val groupedWithCount = grouped.map{case (k, v) => (k, v.size)}
    val listValueTagEventFile = new ReadEventsBeam().getListTagsFromFile(getEventsFilePath(matsimConfig , "xml"), Some("vehicle_type", "subway"),"vehicle_id", Some("PathTraversal"))
    val listTripsEventFile = listValueTagEventFile.map(e => e.split(":")(1)).sorted
    val groupedXml = listTripsEventFile.groupBy(identity)
    val groupedXmlWithCount = groupedXml.map{case (k,v) => (k, v.size)}
    groupedXmlWithCount should contain theSameElementsAs groupedWithCount
  }

  it should "also be available as csv file" in {
    assert(getEventsFilePath(matsimConfig, "csv").exists())
  }

}

