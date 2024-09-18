package beam.agentsim.agents

import akka.actor.ActorSystem
import beam.agentsim.agents.vehicles.VehicleEmissions.Emissions.formatName
import beam.agentsim.agents.vehicles.VehicleEmissions.{Emissions, EmissionsProfile}
import beam.agentsim.events.PathTraversalEvent
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

  describe("When BEAM run with emissions generation for RH") {
    ignore("All links from RH PathTraversal events should be in skims")  {
      val rhWithEmissions = mutable.ListBuffer[PathTraversalEvent]()

      val outPath = runWithConfig(
        "test/input/beamville/beam-urbansimv2-emissions.conf",
        {
          case e: PathTraversalEvent if e.vehicleType == "RH_Car" && e.emissionsProfile.isDefined =>
            rhWithEmissions.append(e)
          case e: PathTraversalEvent if e.vehicleType == "RH_Car" =>
            throw new RuntimeException("There should NOT be any RH PT events without emissions.")
          case _ =>
        }
      )

      rhWithEmissions.count(p =>
        p.numberOfPassengers > 0
      ) should be > 0 withClue "There should be RH PT events with emissions with passengers"

      val skimsEmissions: Map[EmissionsSkimmerKey, EmissionsSkimmerInternal] = readSkims(outPath, 0)
      skimsEmissions shouldNot be(empty) withClue "Emissions skims should be generated."

      val skimsLinks: Set[String] = skimsEmissions.keys.map(_.linkId).toSet
      val rhPTLinks: Set[String] = rhWithEmissions.flatMap(pte => pte.linkIds).map(_.toString).toSet
      (rhPTLinks -- skimsLinks) should be(empty) withClue "All links from RH PathTraversal events should be in skims."
    }
  }
}
