package beam.agentsim.agents

import beam.agentsim.agents.vehicles.VehicleEmissions
import beam.agentsim.agents.vehicles.VehicleEmissions.Emissions.formatName
import beam.agentsim.agents.vehicles.VehicleEmissions.{Emissions, EmissionsProfile}
import beam.agentsim.events.ShiftEvent.{EndShift, StartShift}
import beam.agentsim.events.{LeavingParkingEvent, PathTraversalEvent, ShiftEvent}
import beam.router.skim.CsvSkimReader
import beam.router.skim.core.EmissionsSkimmer.{EmissionsSkimmerInternal, EmissionsSkimmerKey}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import org.matsim.core.controler
import org.matsim.core.controler.AbstractModule
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.MutableScenario
import org.matsim.core.utils.io.IOUtils
import org.scalatest.AppendedClues.convertToClueful
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funspec.AnyFunSpecLike
import org.scalatest.matchers.should.Matchers

import java.nio.file.Paths
import scala.collection.mutable

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
        EmissionsProfile.withName(line("emissionsProcess"))
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

  describe("When BEAM run with emissions generation only for RH") {
    it(
      "expected for emissions be generated for each PTE link and for eny IDLE time between Shift events and PT events"
    ) {
      val rhPTWithEmissions = mutable.ListBuffer[PathTraversalEvent]()

      case class EmissionsTuple(link: Int, hour: Int)

      val lastVehicleShiftEvent = mutable.HashMap.empty[String, ShiftEvent]
      val lastVehiclePTEvent = mutable.HashMap.empty[String, PathTraversalEvent]
      val emissionsProcessLinkHour = mutable.HashMap.empty[EmissionsTuple, Int]

      def putRecords(fromTick: Int, linkId: Option[Int]): Unit = {
        val key = EmissionsTuple(linkId.getOrElse(-1), math.floor(fromTick / 3600).toInt)
        emissionsProcessLinkHour.put(key, emissionsProcessLinkHour.getOrElse(key, 0) + 1)
      }

      val outPath = runWithConfig(
        "test/input/beamville/beam-urbansimv2-emissions.conf",
        {
          case sh: ShiftEvent if sh.shiftEventType == StartShift =>
            lastVehicleShiftEvent(sh.vehicle.id.toString) = sh

          case e: PathTraversalEvent if e.vehicleId.toString.startsWith("rideHail") && e.emissionsProfile.isDefined =>
            rhPTWithEmissions.append(e)
            lastVehicleShiftEvent.remove(e.vehicleId.toString) match {
              case Some(sh) => putRecords(sh.tick.toInt, e.linkIds.headOption)
              case None     => // Empty case with explicit empty comment
            }
            lastVehiclePTEvent.remove(e.vehicleId.toString) match {
              case Some(pte) =>
                putRecords(pte.departureTime, e.linkIds.headOption)
              case None => // Empty case with explicit empty comment
            }
            lastVehiclePTEvent(e.vehicleId.toString) = e

          case sh: ShiftEvent if sh.shiftEventType == EndShift && sh.emissionsProfile.isDefined =>
            lastVehiclePTEvent.remove(sh.vehicle.id.toString) match {
              case Some(pte) =>
                putRecords(pte.departureTime, pte.linkIds.lastOption)
              case None => // Empty case with explicit empty comment
            }
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

      val skimsEmissions: Map[EmissionsSkimmerKey, EmissionsSkimmerInternal] = readSkims(outPath, 0)
      skimsEmissions shouldNot be(empty) withClue "Emissions skims should be generated."

      val notSkimsLinks: Set[Int] = skimsEmissions.keys
        .filter(ek => ek.emissionsProcess != VehicleEmissions.EmissionsProfile.IDLEX)
        .map(_.linkId)
        .toSet

      rhPTWithEmissions
        .flatMap(pte => pte.linkIds)
        .foreach(linkId =>
          assert(
            notSkimsLinks.contains(linkId),
            "All links from RH PathTraversal events should be in skims."
          )
        )

      emissionsProcessLinkHour shouldNot be(empty) withClue "There should be emissions processes of RH vehicles."

      val skimsKeys =
        skimsEmissions.keys
          .map(ek => (ek.linkId, ek.hour))
          .toSet

      emissionsProcessLinkHour.keys.foreach { emissionsTuple =>
        assert(
          skimsKeys.contains((emissionsTuple.link, emissionsTuple.hour)),
          "All emissions processes of RH vehicles should be in skims."
        )
      }
    }
  }
}
