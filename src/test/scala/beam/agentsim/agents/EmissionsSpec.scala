package beam.agentsim.agents

import beam.agentsim.agents.vehicles.VehicleEmissions.Emissions.formatName
import beam.agentsim.agents.vehicles.VehicleEmissions.{Emissions, EmissionsProfile}
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleEmissions}
import beam.agentsim.events.ShiftEvent.{EndShift, StartShift}
import beam.agentsim.events.{PathTraversalEvent, ShiftEvent}
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
        line("linkId"),
        line("vehicleTypeId"),
        line("hour").toInt,
        line("tazId"),
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
        line("energyInJoule").toDouble,
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

  describe("BeamVehicle function startTimeAndDurationToMultipleIntervals") {
    it("be able to convert start time and duration to multiple intervals") {
      def hr_to_secs(hours: Double): Int = (hours * 3600).toInt

      BeamVehicle.startTimeAndDurationToMultipleIntervals(hr_to_secs(1.1), hr_to_secs(0.7)) should be(
        Seq((hr_to_secs(1.1), hr_to_secs(0.7)))
      )
      BeamVehicle.startTimeAndDurationToMultipleIntervals(hr_to_secs(1.1), hr_to_secs(1.7)) should be(
        Seq((hr_to_secs(1.1), hr_to_secs(0.9)), (hr_to_secs(2.0), hr_to_secs(0.8)))
      )
      BeamVehicle.startTimeAndDurationToMultipleIntervals(hr_to_secs(11.5), hr_to_secs(3.6)) should be(
        Seq(
          (hr_to_secs(11.5), hr_to_secs(0.5)),
          (hr_to_secs(12), hr_to_secs(1)),
          (hr_to_secs(13), hr_to_secs(1)),
          (hr_to_secs(14), hr_to_secs(1)),
          (hr_to_secs(15), hr_to_secs(0.1))
        )
      )
    }
  }

  describe("When BEAM run with emissions generation only for RH") {
    it(
      "expected for emissions be generated for each PTE link and for eny IDLE time between Shift events and PT events"
    ) {
      val rhWithEmissions = mutable.ListBuffer[PathTraversalEvent]()

      val lastVehicleShiftEvent = mutable.HashMap.empty[String, ShiftEvent]
      val lastVehiclePathTraversalEvent = mutable.HashMap.empty[String, PathTraversalEvent]
      val vehicleIdleLinkHour = mutable.HashMap.empty[String, Int]

      def putIDLERecords(fromTick: Int, toTick: Int, linkId: Option[Int]): Unit = {
        if (math.abs(toTick - fromTick) > 10) {
          val startHr = math.floor(fromTick / 3600).toInt
          val maxHr = math.ceil(toTick / 3600).toInt
          (startHr to math.min(23, maxHr)).foreach { hr =>
            vehicleIdleLinkHour(linkId.map(_.toString).getOrElse("")) = hr
          }
        }
      }

      val outPath = runWithConfig(
        "test/input/beamville/beam-urbansimv2-emissions.conf",
        {
          case sh: ShiftEvent if sh.shiftEventType == StartShift => lastVehicleShiftEvent(sh.vehicle.id.toString) = sh
          case e: PathTraversalEvent if e.vehicleType == "RH_Car" && e.emissionsProfile.isDefined =>
            rhWithEmissions.append(e)
            lastVehicleShiftEvent.remove(e.vehicleId.toString) match {
              case Some(sh) => putIDLERecords(sh.tick.toInt, e.departureTime, e.linkIds.headOption)
              case None     =>
            }
            lastVehiclePathTraversalEvent.remove(e.vehicleId.toString) match {
              case Some(pte) => putIDLERecords(pte.arrivalTime, e.departureTime, e.linkIds.headOption)
              case None      =>
            }
            lastVehiclePathTraversalEvent(e.vehicleId.toString) = e

          case sh: ShiftEvent if sh.shiftEventType == EndShift && sh.emissionsProfile.isDefined =>
            lastVehiclePathTraversalEvent.remove(sh.vehicle.id.toString) match {
              case Some(pte) => putIDLERecords(pte.arrivalTime, sh.tick.toInt, pte.linkIds.lastOption)
              case None      =>
            }
            lastVehicleShiftEvent(sh.vehicle.id.toString) = sh

          case e: PathTraversalEvent if e.vehicleType == "RH_Car" =>
            throw new RuntimeException("There should NOT be any RH PT events without emissions.")
          case sh: ShiftEvent if sh.shiftEventType == EndShift =>
            throw new RuntimeException("There should NOT be any ShiftEnd events without emissions.")
          case _ =>
        }
      )

      rhWithEmissions.count(p =>
        p.numberOfPassengers > 0
      ) should be > 0 withClue "There should be RH PT events with emissions with passengers"

      val skimsEmissions: Map[EmissionsSkimmerKey, EmissionsSkimmerInternal] = readSkims(outPath, 0)
      skimsEmissions shouldNot be(empty) withClue "Emissions skims should be generated."

      val notIDLESkimsLinks: Set[String] = skimsEmissions.keys
        .filter(ek => ek.emissionsProcess != VehicleEmissions.EmissionsProfile.IDLEX)
        .map(_.linkId)
        .toSet

      rhWithEmissions
        .flatMap(pte => pte.linkIds)
        .foreach(linkId =>
          assert(
            notIDLESkimsLinks.contains(linkId.toString),
            "All links from RH PathTraversal events should be in skims."
          )
        )

      vehicleIdleLinkHour shouldNot be(empty) withClue "There should be IDLE time of RH vehicles."

      val skimsIDLEKeys =
        skimsEmissions.keys
          .filter(ek => ek.emissionsProcess == VehicleEmissions.EmissionsProfile.IDLEX)
          .map(ek => (ek.linkId, ek.hour))
          .toSet

      vehicleIdleLinkHour.foreach { case (linkId, hr) =>
        assert(skimsIDLEKeys.contains((linkId, hr)), "All IDLE time of RH vehicles should be in skims.")
      }
    }
  }
}
