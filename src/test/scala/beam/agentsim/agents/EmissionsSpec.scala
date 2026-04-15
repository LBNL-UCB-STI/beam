package beam.agentsim.agents

import beam.agentsim.agents.vehicles.BeamVehicle.VehicleActivityData
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.FuelType.Diesel
import beam.agentsim.agents.vehicles.VehicleCategory.Class78Tractor
import beam.agentsim.agents.vehicles.VehicleUse.Freight
import beam.agentsim.agents.vehicles.VehicleEmissions
import beam.agentsim.agents.vehicles.VehicleEmissions.Emissions.formatName
import beam.agentsim.agents.vehicles.VehicleEmissions.{Emissions, EmissionsProfile}
import beam.agentsim.infrastructure.ParkingInquiry.ParkingActivityType.Idling
import beam.agentsim.events.ShiftEvent.{EndShift, StartShift}
import beam.agentsim.events.{LeavingParkingEvent, PathTraversalEvent, ShiftEvent}
import beam.router.skim.event.EmissionsSkimmerEvent
import beam.router.skim.CsvSkimReader
import beam.router.skim.core.EmissionsSkimmer.{EmissionsSkimmerInternal, EmissionsSkimmerKey}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigValueFactory
import org.matsim.core.controler
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.MatsimServices
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.MutableScenario
import org.matsim.core.utils.io.IOUtils
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.utils.objectattributes.attributable.Attributes
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{doAnswer, mock, when}
import org.scalatest.AppendedClues.convertToClueful
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funspec.AnyFunSpecLike
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters._

class EmissionsSpec extends AnyFunSpecLike with Matchers with BeamHelper with BeforeAndAfterAll {

  def runWithConfig(configPath: String, eventHandler: BasicEventHandler): String = {
    val config = testConfig(configPath).resolve()
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    val beamConfig = BeamConfig(config)
    val outPath = FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val (scenarioBuilt, beamScenario, _) = buildBeamServicesAndScenario(beamConfig, matsimConfig)
    val scenario: MutableScenario = scenarioBuilt

    val injector = controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, beamConfig, scenario, beamScenario))
          addEventHandlerBinding().toInstance(eventHandler)
        }
      }
    )
    // implicit val actorSystem: ActorSystem = injector.getInstance(classOf[ActorSystem])
    val beamServices: BeamServices = buildBeamServices(injector)
    beamServices.controler.run()
    outPath
  }

  def fromCsv(
    line: scala.collection.Map[String, String]
  ): (EmissionsSkimmerKey, EmissionsSkimmerInternal) = {
    (
      EmissionsSkimmerKey(
        line("linkId").toInt,
        line("vehicleTypeId"),
        line("hour").toInt,
        EmissionsProfile.withName(line("process"))
      ),
      EmissionsSkimmerInternal(
        Emissions(
          Emissions.values.flatMap { emissionType =>
            line.get(formatName(emissionType)).flatMap { value =>
              try {
                Some(emissionType -> value.toDouble)
              } catch {
                case _: NumberFormatException => None
              }
            }
          }.toMap
        ),
        line("travelTimeInSecond").toDouble,
        line("parkingDurationInSecond").toDouble,
        line("observations").toInt,
        line("iterations").toInt
      )
    )
  }

  def readSkims(simOutputPath: String, iteration: Int): Map[EmissionsSkimmerKey, EmissionsSkimmerInternal] = {
    val skimsPath = Paths.get(simOutputPath, f"/ITERS/it.$iteration/$iteration.skimsEmissions.csv.gz").toString
    val reader = IOUtils.getBufferedReader(skimsPath)
    val skims: Map[EmissionsSkimmerKey, EmissionsSkimmerInternal] =
      new CsvSkimReader(skimsPath, fromCsv, logger).readSkims(reader)
    reader.close()
    skims
  }

  private def skimsPath(simOutputPath: String, iteration: Int): Path =
    Paths.get(simOutputPath, "ITERS", s"it.$iteration", s"$iteration.skimsEmissions.csv.gz")

  describe("When BEAM run with emissions generation only for RH") {
    it(
      "should complete a BEAM run with ridehail emissions and produce a skims emissions artifact"
    ) {
      val rhPTWithEmissions = mutable.ListBuffer[PathTraversalEvent]()

      val lastVehicleShiftEvent = mutable.HashMap.empty[String, ShiftEvent]
      val lastVehiclePTEvent = mutable.HashMap.empty[String, PathTraversalEvent]
      val rideHailEndShiftWithEmissions = mutable.ListBuffer.empty[ShiftEvent]

      val outPath = runWithConfig(
        "test/input/beamville/beam-urbansimv2-emissions.conf",
        {
          case sh: ShiftEvent if sh.shiftEventType == StartShift =>
            lastVehicleShiftEvent(sh.vehicle.id.toString) = sh

          case e: PathTraversalEvent if e.vehicleId.toString.startsWith("rideHail") && e.emissionsProfile.isDefined =>
            rhPTWithEmissions.append(e)
            lastVehicleShiftEvent.remove(e.vehicleId.toString)
            lastVehiclePTEvent.remove(e.vehicleId.toString)
            lastVehiclePTEvent(e.vehicleId.toString) = e

          case sh: ShiftEvent if sh.shiftEventType == EndShift && sh.emissionsProfile.isDefined =>
            rideHailEndShiftWithEmissions += sh
            lastVehiclePTEvent.remove(sh.vehicle.id.toString)
            lastVehicleShiftEvent(sh.vehicle.id.toString) = sh

          case e: LeavingParkingEvent if e.vehicleId.toString.startsWith("rideHail") =>
            throw new RuntimeException("There should NOT be any RH PT events without emissions.")

          case sh: ShiftEvent if sh.shiftEventType == EndShift =>
            throw new RuntimeException("There should NOT be any ShiftEnd events without emissions.")

          case _ =>
        }
      )

      rhPTWithEmissions.count(p =>
        p.numberOfPassengers > 0
      ) should be > 0 withClue "There should be RH PT events with emissions with passengers"
      rideHailEndShiftWithEmissions should not be empty

      val emissionsSkimsPath = skimsPath(outPath, 0)
      Files.exists(emissionsSkimsPath) shouldBe true
      Files.size(emissionsSkimsPath) should be > 0L

      noException should be thrownBy readSkims(outPath, 0)
    }
  }

  describe("VehicleEmissions") {
    it("should emit skimmer events with all pollutants across all supported processes") {
      VehicleEmissions.Emissions.filter = None
      val tempDir = Files.createTempDirectory("vehicle-emissions-runtime-spec")

      try {
        val relativeRatesFile = "rates/all-processes.parquet"
        val absoluteRatesFile = tempDir.resolve(relativeRatesFile)
        Files.createDirectories(absoluteRatesFile.getParent)
        writeParquet(absoluteRatesFile, allProcessRows)

        val beamConfig = BeamConfig(
          testConfig("test/input/beamville/beam-urbansimv2-emissions.conf")
            .withValue("beam.agentsim.agents.vehicles.emissions.skims", ConfigValueFactory.fromAnyRef(true))
            .resolve()
        )

        val eventsManager = mock(classOf[EventsManager])
        val matsimServices = mock(classOf[MatsimServices])
        when(matsimServices.getEvents).thenReturn(eventsManager)
        when(matsimServices.getIterationNumber).thenReturn(0)

        val link = mock(classOf[Link])
        val linkAttributes = new Attributes()
        linkAttributes.putAttribute("type", "motorway")
        when(link.getAttributes).thenReturn(linkAttributes)

        val networkHelper = mock(classOf[beam.utils.NetworkHelper])
        when(networkHelper.getLink(101)).thenReturn(Some(link))

        val beamServices = mock(classOf[BeamServices])
        when(beamServices.beamConfig).thenReturn(beamConfig)
        when(beamServices.matsimServices).thenReturn(matsimServices)
        when(beamServices.networkHelper).thenReturn(networkHelper)

        val emittedEvents = mutable.ListBuffer.empty[EmissionsSkimmerEvent]
        doAnswer(invocation => {
          emittedEvents += invocation.getArgument(0).asInstanceOf[EmissionsSkimmerEvent]
          null
        }).when(eventsManager).processEvent(any(classOf[Event]))

        val vehicleTypeId = Id.create("freight-type", classOf[BeamVehicleType])
        val vehicleType = BeamVehicleType(
          id = vehicleTypeId,
          seatingCapacity = 1,
          standingRoomCapacity = 0,
          lengthInMeter = 8.0,
          curbWeightInKg = 8000.0,
          primaryFuelType = Diesel,
          primaryFuelConsumptionInJoulePerMeter = 100.0,
          primaryFuelCapacityInJoule = 1e9,
          vehicleCategory = Class78Tractor,
          emissionsRatesFile = Some(relativeRatesFile),
          vehicleUse = Freight
        )

        val vehicleEmissions = new VehicleEmissions(
          vehicleTypesBasePaths = IndexedSeq(tempDir.toString),
          vehicleTypes = Map(vehicleTypeId -> vehicleType),
          pollutantsFilter = Emissions.values.map(formatName).mkString(","),
          ratesFilter = beamConfig.beam.agentsim.agents.vehicles.emissions.ratesFilter
        )

        val vehicleId = Id.create("freightVehicle-1", classOf[beam.agentsim.agents.vehicles.BeamVehicle])
        val traversalData = VehicleActivityData(
          activityStartTime = 0.0,
          linkStartTime = 600.0,
          linkId = 101,
          vehicleId = vehicleId,
          vehicleType = vehicleType,
          payloadInKg = Some(1000.0),
          linkNumberOfLanes = Some(2),
          linkLength = Some(1609.344),
          averageSpeed = Some(25.0 / 2.2369362920544),
          linkTravelTime = Some(600.0)
        )
        val parkingData = VehicleActivityData(
          activityStartTime = 0.0,
          linkStartTime = 3600.0,
          linkId = 101,
          vehicleId = vehicleId,
          vehicleType = vehicleType,
          payloadInKg = None,
          linkNumberOfLanes = Some(2),
          linkLength = Some(0.0),
          averageSpeed = None,
          parkingDuration = Some(1800.0),
          parkingActivityType = Some(Idling),
          linkTravelTime = None
        )

        val traversalProfile =
          vehicleEmissions.getEmissionsProfileInGram(IndexedSeq(traversalData), classOf[PathTraversalEvent], beamServices)
        val parkingProfile =
          vehicleEmissions.getEmissionsProfileInGram(IndexedSeq(parkingData), classOf[LeavingParkingEvent], beamServices)

        traversalProfile.map(_.values.keySet).getOrElse(Set.empty) shouldBe Set(
          EmissionsProfile.RUNEX,
          EmissionsProfile.RUNLOSS,
          EmissionsProfile.PMTW,
          EmissionsProfile.PMBW,
          EmissionsProfile.PRDUST,
          EmissionsProfile.PTOEX
        )
        parkingProfile.map(_.values.keySet).getOrElse(Set.empty) shouldBe Set(
          EmissionsProfile.IDLEX,
          EmissionsProfile.STREX,
          EmissionsProfile.HOTSOAK,
          EmissionsProfile.DIURN,
          EmissionsProfile.RUNLOSS
        )
        traversalProfile.toSeq.flatMap(_.values.values).foreach { emissions =>
          emissions.values.keySet shouldBe Emissions.values.toSet
          all(emissions.values.values) should be > 0.0
        }
        parkingProfile.toSeq.flatMap(_.values.values).foreach { emissions =>
          emissions.values.keySet shouldBe Emissions.values.toSet
          all(emissions.values.values) should be > 0.0
        }

        val emittedProcesses = emittedEvents.map(_.emissionsProcess).toSet
        emittedProcesses shouldBe EmissionsProfile.values.toSet
        emittedEvents.size shouldBe 11

        emittedEvents.foreach { event =>
          event.emissions.values.keySet shouldBe Emissions.values.toSet
          all(event.emissions.values.values) should be > 0.0
        }
      } finally {
        VehicleEmissions.Emissions.filter = None
        deleteRecursively(tempDir)
      }
    }
  }

  private val allPollutants: Map[String, Double] = Map(
    "ch4_gram"  -> 1.0,
    "co_gram"   -> 2.0,
    "co2_gram"  -> 3.0,
    "hc_gram"   -> 4.0,
    "nh3_gram"  -> 5.0,
    "n2o_gram"  -> 6.0,
    "nox_gram"  -> 7.0,
    "pm_gram"   -> 8.0,
    "pm10_gram" -> 9.0,
    "pm25_gram" -> 10.0,
    "rog_gram"  -> 11.0,
    "sox_gram"  -> 12.0,
    "tog_gram"  -> 13.0,
    "bc_gram"   -> 14.0
  )

  private val allProcessRows: IndexedSeq[Map[String, Any]] = IndexedSeq(
    EmissionsProfile.RUNEX   -> 25.0,
    EmissionsProfile.IDLEX   -> 0.0,
    EmissionsProfile.STREX   -> 30.0,
    EmissionsProfile.HOTSOAK -> 30.0,
    EmissionsProfile.DIURN   -> 30.0,
    EmissionsProfile.RUNLOSS -> 30.0,
    EmissionsProfile.PMTW    -> 25.0,
    EmissionsProfile.PMBW    -> 25.0,
    EmissionsProfile.PRDUST  -> 25.0,
    EmissionsProfile.PTOEX   -> 25.0
  ).map { case (process, activityValue) =>
    Map(
      "speedMph_timeMin" -> activityValue,
      "county"           -> "",
      "roadCategory"     -> "motorway",
      "process"          -> process.toString
    ) ++ allPollutants
  }

  private def writeParquet(path: Path, rows: IndexedSeq[Map[String, Any]]): Unit = {
    val schemaFields = rows.head.toIndexedSeq.map { case (name, value) =>
      val schema = value match {
        case _: java.lang.Number => org.apache.avro.Schema.create(org.apache.avro.Schema.Type.DOUBLE)
        case _                   => org.apache.avro.Schema.create(org.apache.avro.Schema.Type.STRING)
      }
      new org.apache.avro.Schema.Field(name, schema, "", null)
    }
    val schema = org.apache.avro.Schema.createRecord(
      "VehicleEmissionsRuntimeRow",
      "",
      "beam.agentsim.agents",
      false,
      schemaFields.asJava
    )
    val outputFile =
      org.apache.parquet.hadoop.util.HadoopOutputFile.fromPath(
        new org.apache.hadoop.fs.Path(path.toString),
        new org.apache.hadoop.conf.Configuration()
      )
    val writer = org.apache.parquet.avro.AvroParquetWriter
      .builder[org.apache.avro.generic.GenericData.Record](outputFile)
      .withSchema(schema)
      .withCompressionCodec(org.apache.parquet.hadoop.metadata.CompressionCodecName.SNAPPY)
      .build()

    try rows.foreach { row =>
      val record = new org.apache.avro.generic.GenericData.Record(schema)
      row.foreach { case (name, value) =>
        value match {
          case n: java.lang.Number => record.put(name, n.doubleValue())
          case other               => record.put(name, other.toString)
        }
      }
      writer.write(record)
    }
    finally writer.close()
  }

  private def deleteRecursively(path: Path): Unit = {
    if (path == null || !Files.exists(path)) return
    import scala.jdk.CollectionConverters._
    Files.walk(path).iterator().asScala.toSeq.reverse.foreach(Files.deleteIfExists)
  }
}
