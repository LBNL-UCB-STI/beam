package beam.sim

import java.io.{FileOutputStream, FileWriter}
import java.nio.file.{Files, Paths, StandardCopyOption}
import java.util.concurrent.TimeUnit
import java.util.{Properties, Random}

import beam.agentsim.agents.ridehail.{RideHailIterationHistory, RideHailSurgePricingManager}
import beam.agentsim.events.handling.BeamEventsHandling
import beam.analysis.ActivityLocationPlotter
import beam.analysis.plots.{GraphSurgePricing, RideHailRevenueAnalysis}
import beam.replanning._
import beam.replanning.utilitybased.UtilityBasedModeChoice
import beam.router.osm.TollCalculator
import beam.router.r5.{DefaultNetworkCoordinator, FrequencyAdjustingNetworkCoordinator, NetworkCoordinator}
import beam.router.{BeamSkimmer, RouteHistory, TravelTimeObserved}
import beam.scoring.BeamScoringFunctionFactory
import beam.sim.config.{BeamConfig, ConfigModule, MatSimBeamConfigBuilder}
import beam.sim.metrics.Metrics._
import beam.sim.modules.{BeamAgentModule, UtilsModule}
import beam.sim.population.PopulationAdjustment
import beam.utils.scenario.matsim.MatsimScenarioSource
import beam.utils.scenario.urbansim.{CsvScenarioReader, ParquetScenarioReader, UrbanSimScenarioSource}
import beam.utils.scenario.{InputType, ScenarioLoader, ScenarioSource}
import beam.utils.{NetworkHelper, _}
import com.conveyal.r5.transit.TransportNetwork
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}
import com.typesafe.scalalogging.LazyLogging
import kamon.Kamon
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.config.Config
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup
import org.matsim.core.controler._
import org.matsim.core.controler.corelisteners.{ControlerDefaultCoreListenersModule, EventsHandling}
import org.matsim.core.scenario.{MutableScenario, ScenarioByInstanceModule, ScenarioUtils}
import org.matsim.core.trafficmonitoring.TravelTimeCalculator
import org.matsim.households.Household
import org.matsim.utils.objectattributes.AttributeConverter
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await

trait BeamHelper extends LazyLogging {

  protected val beamAsciiArt: String =
    """
    |  ________
    |  ___  __ )__________ _______ ___
    |  __  __  |  _ \  __ `/_  __ `__ \
    |  _  /_/ //  __/ /_/ /_  / / / / /
    |  /_____/ \___/\__,_/ /_/ /_/ /_/
    |
    | _____________________________________
    |
    """.stripMargin

  private val argsParser = new scopt.OptionParser[Arguments]("beam") {
    opt[String]("config")
      .action(
        (value, args) =>
          args.copy(
            config = Some(BeamConfigUtils.parseFileSubstitutingInputDirectory(value)),
            configLocation = Option(value)
        )
      )
      .validate(
        value =>
          if (value.trim.isEmpty) failure("config location cannot be empty")
          else success
      )
      .text("Location of the beam config file")
    opt[String]("cluster-type")
      .action(
        (value, args) =>
          args.copy(clusterType = value.trim.toLowerCase match {
            case "master" => Some(Master)
            case "worker" => Some(Worker)
            case _        => None
          })
      )
      .text("If running as a cluster, specify master or worker")
    opt[String]("node-host")
      .action((value, args) => args.copy(nodeHost = Option(value)))
      .validate(value => if (value.trim.isEmpty) failure("node-host cannot be empty") else success)
      .text("Host used to run the remote actor system")
    opt[String]("node-port")
      .action((value, args) => args.copy(nodePort = Option(value)))
      .validate(value => if (value.trim.isEmpty) failure("node-port cannot be empty") else success)
      .text("Port used to run the remote actor system")
    opt[String]("seed-address")
      .action((value, args) => args.copy(seedAddress = Option(value)))
      .validate(
        value =>
          if (value.trim.isEmpty) failure("seed-address cannot be empty")
          else success
      )
      .text(
        "Comma separated list of initial addresses used for the rest of the cluster to bootstrap"
      )
    opt[Boolean]("use-local-worker")
      .action((value, args) => args.copy(useLocalWorker = Some(value)))
      .text(
        "Boolean determining whether to use a local worker. " +
        "If cluster is NOT enabled this defaults to true and cannot be false. " +
        "If cluster is specified then this defaults to false and must be explicitly set to true. " +
        "NOTE: For cluster, this will ONLY be checked if cluster-type=master"
      )

    checkConfig(
      args =>
        if (args.useCluster && (args.nodeHost.isEmpty || args.nodePort.isEmpty || args.seedAddress.isEmpty))
          failure("If using the cluster then node-host, node-port, and seed-address are required")
        else if (args.useCluster && !args.useLocalWorker.getOrElse(true))
          failure("If using the cluster then use-local-worker MUST be true (or unprovided)")
        else success
    )
  }

  private def updateConfigForClusterUsing(
    parsedArgs: Arguments,
    config: TypesafeConfig
  ): TypesafeConfig = {
    (for {
      seedAddress <- parsedArgs.seedAddress
      nodeHost    <- parsedArgs.nodeHost
      nodePort    <- parsedArgs.nodePort
    } yield {
      config.withFallback(
        ConfigFactory.parseMap(
          Map(
            "seed.address" -> seedAddress,
            "node.host"    -> nodeHost,
            "node.port"    -> nodePort
          ).asJava
        )
      )
    }).getOrElse(config)
  }

  private def embedSelectArgumentsIntoConfig(
    parsedArgs: Arguments,
    config: TypesafeConfig
  ): TypesafeConfig = {
    config.withFallback(
      ConfigFactory.parseMap(
        (
          Map(
            "beam.cluster.enabled" -> parsedArgs.useCluster,
            "beam.useLocalWorker" -> parsedArgs.useLocalWorker.getOrElse(
              if (parsedArgs.useCluster) false else true
            )
          ) ++ {
            if (parsedArgs.useCluster)
              Map(
                "beam.cluster.clusterType"              -> parsedArgs.clusterType.get.toString,
                "akka.actor.provider"                   -> "akka.cluster.ClusterActorRefProvider",
                "akka.remote.artery.canonical.hostname" -> parsedArgs.nodeHost.get,
                "akka.remote.artery.canonical.port"     -> parsedArgs.nodePort.get,
                "akka.cluster.seed-nodes" -> java.util.Arrays
                  .asList(s"akka://ClusterSystem@${parsedArgs.seedAddress.get}")
              )
            else Map.empty[String, Any]
          }
        ).asJava
      )
    )
  }

  def module(
    typesafeConfig: TypesafeConfig,
    scenario: Scenario,
    networkCoordinator: NetworkCoordinator,
    networkHelper: NetworkHelper
  ): com.google.inject.Module =
    AbstractModule.`override`(
      ListBuffer(new AbstractModule() {
        override def install(): Unit = {
          // MATSim defaults
          install(new NewControlerModule)
          install(new ScenarioByInstanceModule(scenario))
          install(new ControllerModule)
          install(new ControlerDefaultCoreListenersModule)

          // Beam Inject below:
          install(new ConfigModule(typesafeConfig))
          install(new BeamAgentModule(BeamConfig(typesafeConfig)))
          install(new UtilsModule)
        }
      }).asJava,
      new AbstractModule() {
        private val mapper = new ObjectMapper()
        mapper.registerModule(DefaultScalaModule)

        override def install(): Unit = {
          // This code will be executed 3 times due to this https://github.com/LBNL-UCB-STI/matsim/blob/master/matsim/src/main/java/org/matsim/core/controler/Injector.java#L99:L101
          // createMapBindingsForType is called 3 times. Be careful not to do expensive operations here
          val beamConfig = BeamConfig(typesafeConfig)

          bind(classOf[BeamConfig]).toInstance(beamConfig)
          bind(classOf[PrepareForSim]).to(classOf[BeamPrepareForSim])
          bind(classOf[RideHailSurgePricingManager]).asEagerSingleton()

          addControlerListenerBinding().to(classOf[BeamSim])
          addControlerListenerBinding().to(classOf[BeamScoringFunctionFactory])
          addControlerListenerBinding().to(classOf[RouteHistory])

          addControlerListenerBinding().to(classOf[ActivityLocationPlotter])
          addControlerListenerBinding().to(classOf[GraphSurgePricing])
          bind(classOf[BeamOutputDataDescriptionGenerator])
          addControlerListenerBinding().to(classOf[RideHailRevenueAnalysis])

          bindMobsim().to(classOf[BeamMobsim])
          bind(classOf[EventsHandling]).to(classOf[BeamEventsHandling])
          bindScoringFunctionFactory().to(classOf[BeamScoringFunctionFactory])
          if (getConfig.strategy().getPlanSelectorForRemoval == "tryToKeepOneOfEachClass") {
            bindPlanSelectorForRemoval().to(classOf[TryToKeepOneOfEachClass])
          }
          addPlanStrategyBinding("SelectExpBeta").to(classOf[BeamExpBeta])
          addPlanStrategyBinding("SwitchModalityStyle").to(classOf[SwitchModalityStyle])
          addPlanStrategyBinding("ClearRoutes").to(classOf[ClearRoutes])
          addPlanStrategyBinding("ClearModes").to(classOf[ClearModes])
          addPlanStrategyBinding("TimeMutator").to(classOf[BeamTimeMutator])
          addPlanStrategyBinding(BeamReplanningStrategy.UtilityBasedModeChoice.toString)
            .toProvider(classOf[UtilityBasedModeChoice])
          addAttributeConverterBinding(classOf[MapStringDouble])
            .toInstance(new AttributeConverter[MapStringDouble] {
              override def convertToString(o: scala.Any): String =
                mapper.writeValueAsString(o.asInstanceOf[MapStringDouble].data)

              override def convert(value: String): MapStringDouble =
                MapStringDouble(mapper.readValue(value, classOf[Map[String, Double]]))
            })
          bind(classOf[TransportNetwork]).toInstance(networkCoordinator.transportNetwork)
          bind(classOf[TravelTimeCalculator]).toInstance(
            new FakeTravelTimeCalculator(
              networkCoordinator.network,
              new TravelTimeCalculatorConfigGroup()
            )
          )

          bind(classOf[NetworkHelper]).toInstance(networkHelper)

          bind(classOf[RideHailIterationHistory]).asEagerSingleton()
          bind(classOf[RouteHistory]).asEagerSingleton()
          bind(classOf[BeamSkimmer]).asEagerSingleton()
          bind(classOf[TravelTimeObserved]).asEagerSingleton()
          bind(classOf[TollCalculator]).asEagerSingleton()

          bind(classOf[EventsManager]).to(classOf[LoggingEventsManager]).asEagerSingleton()
        }
      }
    )

  def runBeamUsing(args: Array[String], isConfigArgRequired: Boolean = true): Unit = {
    val (parsedArgs, config) = prepareConfig(args, isConfigArgRequired)

    parsedArgs.clusterType match {
      case Some(Worker) => runClusterWorkerUsing(config) //Only the worker requires a different path
      case _ =>
        val (_, outputDirectory) = runBeamWithConfig(config)
        postRunActivity(parsedArgs.configLocation.get, config, outputDirectory)
    }
  }

  def prepareConfig(args: Array[String], isConfigArgRequired: Boolean): (Arguments, TypesafeConfig) = {
    val parsedArgs = argsParser.parse(args, init = Arguments()) match {
      case Some(pArgs) => pArgs
      case None =>
        throw new IllegalArgumentException(
          "Arguments provided were unable to be parsed. See above for reasoning."
        )
    }
    assert(
      !isConfigArgRequired || (isConfigArgRequired && parsedArgs.config.isDefined),
      "Please provide a valid configuration file."
    )

    ConfigConsistencyComparator.parseBeamTemplateConfFile(parsedArgs.configLocation.get)

    if (parsedArgs.configLocation.get.contains("\\")) {
      throw new RuntimeException("wrong config path, expected:forward slash, found: backward slash")
    }

    val location = ConfigFactory.parseString(s"config=${parsedArgs.configLocation.get}")
    val config = embedSelectArgumentsIntoConfig(parsedArgs, {
      if (parsedArgs.useCluster) updateConfigForClusterUsing(parsedArgs, parsedArgs.config.get)
      else parsedArgs.config.get
    }).withFallback(location).resolve()

    (parsedArgs, config)
  }

  private def postRunActivity(configLocation: String, config: TypesafeConfig, outputDirectory: String) = {
    val props = new Properties()
    props.setProperty("commitHash", BashUtils.getCommitHash)
    props.setProperty("configFile", configLocation)
    val out = new FileOutputStream(Paths.get(outputDirectory, "beam.properties").toFile)
    props.store(out, "Simulation out put props.")
    val beamConfig = BeamConfig(config)
    if (beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass
          .equalsIgnoreCase("ModeChoiceLCCM")) {
      Files.copy(
        Paths.get(beamConfig.beam.agentsim.agents.modalBehaviors.lccm.filePath),
        Paths.get(
          outputDirectory,
          Paths
            .get(beamConfig.beam.agentsim.agents.modalBehaviors.lccm.filePath)
            .getFileName
            .toString
        )
      )
    }
    Files.copy(
      Paths.get(configLocation),
      Paths.get(outputDirectory, "beam.conf"),
      StandardCopyOption.REPLACE_EXISTING
    )
  }

  def runClusterWorkerUsing(config: TypesafeConfig): Unit = {
    val clusterConfig = ConfigFactory
      .parseString(s"""
                      |akka.cluster.roles = [compute]
                      |akka.actor.deployment {
                      |      /statsService/singleton/workerRouter {
                      |        router = round-robin-pool
                      |        cluster {
                      |          enabled = on
                      |          max-nr-of-instances-per-node = 1
                      |          allow-local-routees = on
                      |          use-roles = ["compute"]
                      |        }
                      |      }
                      |    }
          """.stripMargin)
      .withFallback(config)

    if (isMetricsEnable) Kamon.start(clusterConfig.withFallback(ConfigFactory.defaultReference()))

    import akka.actor.{ActorSystem, DeadLetter, PoisonPill, Props}
    import akka.cluster.singleton.{
      ClusterSingletonManager,
      ClusterSingletonManagerSettings,
      ClusterSingletonProxy,
      ClusterSingletonProxySettings
    }
    import beam.router.ClusterWorkerRouter
    import beam.sim.monitoring.DeadLetterReplayer

    val system = ActorSystem("ClusterSystem", clusterConfig)
    system.actorOf(
      ClusterSingletonManager.props(
        singletonProps = Props(classOf[ClusterWorkerRouter], clusterConfig),
        terminationMessage = PoisonPill,
        settings = ClusterSingletonManagerSettings(system).withRole("compute")
      ),
      name = "statsService"
    )
    system.actorOf(
      ClusterSingletonProxy.props(
        singletonManagerPath = "/user/statsService",
        settings = ClusterSingletonProxySettings(system).withRole("compute")
      ),
      name = "statsServiceProxy"
    )
    val replayer = system.actorOf(DeadLetterReplayer.props())
    system.eventStream.subscribe(replayer, classOf[DeadLetter])

    import scala.concurrent.ExecutionContext.Implicits.global
    Await.ready(system.whenTerminated.map(_ => {
      if (isMetricsEnable) Kamon.shutdown()
      logger.info("Exiting BEAM")
    }), scala.concurrent.duration.Duration.Inf)
  }

  def writeScenarioPrivateVehicles(scenario: MutableScenario, beamServices: BeamServices, outputDir: String): Unit = {
    val csvWriter: FileWriter = new FileWriter(outputDir + "/householdVehicles.csv", true)
    try {
      csvWriter.write("vehicleId,vehicleType,householdId\n")
      scenario.getHouseholds.getHouseholds.values.asScala.foreach { householdId =>
        householdId.getVehicleIds.asScala.foreach { vehicle =>
          beamServices.privateVehicles
            .get(vehicle)
            .map(
              v => v.id.toString + "," + v.beamVehicleType.id.toString + "," + householdId.getId.toString + "\n"
            )
            .foreach(csvWriter.write)
        }
      }
    } finally {
      csvWriter.close()
    }
  }

  def runBeamWithConfig(config: TypesafeConfig): (Config, String) = {
    val (scenario, outputDir, networkCoordinator) = setupBeamWithConfig(config)

    // beam.utils.scenario.CsvScenarioWriter.write(scenario, "c:/temp/csv_scenario_1k/")

    runBeam(config, scenario, networkCoordinator, outputDir)
    (scenario.getConfig, outputDir)
  }

  def runBeam(
    config: TypesafeConfig,
    scenario: MutableScenario,
    networkCoordinator: NetworkCoordinator,
    outputDir: String
  ): Unit = {
    val networkHelper: NetworkHelper = new NetworkHelperImpl(networkCoordinator.network)

    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      module(config, scenario, networkCoordinator, networkHelper)
    )

    networkCoordinator.convertFrequenciesToTrips()

    scenario.setNetwork(networkCoordinator.network)

    val beamServices = injector.getInstance(classOf[BeamServices])

    beamServices.setTransitFleetSizes(networkCoordinator.tripFleetSizeMap)

    val beamConfig = beamServices.beamConfig
    val useExternalDataForScenario: Boolean =
      Option(beamConfig.beam.exchange.scenario.folder).exists(!_.isEmpty)
    if (useExternalDataForScenario) {
      val scenarioSource = getScenarioSource(beamServices, beamConfig)
      ProfilingUtils.timed(s"Load scenario using ${scenarioSource.getClass}", x => logger.info(x)) {
        new ScenarioLoader(scenario, beamServices, scenarioSource).loadScenario()
      }
    }

    samplePopulation(scenario, beamServices.beamConfig, scenario.getConfig, beamServices, outputDir)

    val houseHoldVehiclesInScenario: Iterable[Id[Vehicle]] = scenario.getHouseholds.getHouseholds
      .values()
      .asScala
      .flatMap(_.getVehicleIds.asScala)

    val vehiclesGroupedByType = houseHoldVehiclesInScenario.groupBy(
      v => beamServices.privateVehicles.get(v).map(_.beamVehicleType.id.toString).getOrElse("")
    )
    val vehicleInfo = vehiclesGroupedByType.map {
      case (vehicleType, groupedValues) =>
        s"$vehicleType (${groupedValues.size})"
    } mkString " , "
    logger.info(s"Vehicles assigned to households : $vehicleInfo")

    run(beamServices)
  }

  def setupBeamWithConfig(config: TypesafeConfig): (MutableScenario, String, NetworkCoordinator) = {
    val beamConfig = BeamConfig(config)
    val outputDirectory = FileUtils.getConfigOutputFile(
      beamConfig.beam.outputs.baseOutputDirectory,
      beamConfig.beam.agentsim.simulationName,
      beamConfig.beam.outputs.addTimestampToOutputDirectory
    )
    LoggingUtil.initLogger(outputDirectory, beamConfig.beam.logger.keepConsoleAppenderOn)

    level = beamConfig.beam.metrics.level
    runName = beamConfig.beam.agentsim.simulationName
    if (isMetricsEnable) Kamon.start(config.withFallback(ConfigFactory.defaultReference()))

    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    if (!beamConfig.beam.outputs.writeGraphs) {
      matsimConfig.counts.setOutputFormat("txt")
      matsimConfig.controler.setCreateGraphs(false)
    }
    matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)

    matsimConfig.controler.setOutputDirectory(outputDirectory)
    matsimConfig.controler().setWritePlansInterval(beamConfig.beam.outputs.writePlansInterval)

    logger.info("Starting beam on branch {} at commit {}.", BashUtils.getBranch, BashUtils.getCommitHash)
    new java.io.File(outputDirectory).mkdirs
    val outConf = Paths.get(outputDirectory, "beam.conf")
    val location = config.getString("config")

    Files.copy(Paths.get(location), outConf, StandardCopyOption.REPLACE_EXISTING)
    logger.info("Config [{}] copied to {}.", beamConfig.beam.agentsim.simulationName, outConf)

    val networkCoordinator: NetworkCoordinator =
      if (Files.exists(Paths.get(beamConfig.beam.agentsim.scenarios.frequencyAdjustmentFile))) {
        FrequencyAdjustingNetworkCoordinator(beamConfig)
      } else {
        DefaultNetworkCoordinator(beamConfig)
      }
    networkCoordinator.init()

    val maxHour = TimeUnit.SECONDS.toHours(matsimConfig.travelTimeCalculator().getMaxTime).toInt
    val beamWarmStart = BeamWarmStart(beamConfig, maxHour)
    beamWarmStart.warmStartPopulation(matsimConfig)

    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]

    (scenario, outputDirectory, networkCoordinator)
  }

  def run(beamServices: BeamServices) {
    beamServices.controler.run()
    if (isMetricsEnable) Kamon.shutdown()
  }

  // sample population (beamConfig.beam.agentsim.numAgents - round to nearest full household)
  def samplePopulation(
    scenario: MutableScenario,
    beamConfig: BeamConfig,
    matsimConfig: Config,
    beamServices: BeamServices,
    outputDir: String
  ): Unit = {
    if (beamConfig.beam.agentsim.agentSampleSizeAsFractionOfPopulation < 1) {
      val numAgents = math.round(
        beamConfig.beam.agentsim.agentSampleSizeAsFractionOfPopulation * scenario.getPopulation.getPersons.size()
      )
      val rand = new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)
      val notSelectedHouseholdIds = mutable.Set[Id[Household]]()
      val notSelectedVehicleIds = mutable.Set[Id[Vehicle]]()
      val notSelectedPersonIds = mutable.Set[Id[Person]]()

      // We add all households, vehicles and persons to the sets
      scenario.getHouseholds.getHouseholds.values().asScala.foreach { hh =>
        hh.getVehicleIds.forEach(vehicleId => notSelectedVehicleIds.add(vehicleId))
      }
      scenario.getHouseholds.getHouseholds
        .keySet()
        .forEach(householdId => notSelectedHouseholdIds.add(householdId))
      scenario.getPopulation.getPersons
        .keySet()
        .forEach(personId => notSelectedPersonIds.add(personId))

      logger.info(s"""Before sampling:
          |Number of households: ${notSelectedHouseholdIds.size}
          |Number of vehicles: ${getVehicleGroupingStringUsing(notSelectedVehicleIds.toIndexedSeq, beamServices)}
          |Number of persons: ${notSelectedPersonIds.size}""".stripMargin)

      val iterHouseholds = RandomUtils.shuffle(scenario.getHouseholds.getHouseholds.values().asScala, rand).iterator
      var numberOfAgents = 0
      // We start from the first household and remove its vehicles and persons from the sets to clean
      while (numberOfAgents < numAgents && iterHouseholds.hasNext) {

        val household = iterHouseholds.next()
        numberOfAgents += household.getMemberIds.size()
        household.getVehicleIds.forEach(vehicleId => notSelectedVehicleIds.remove(vehicleId))
        notSelectedHouseholdIds.remove(household.getId)
        household.getMemberIds.forEach(persondId => notSelectedPersonIds.remove(persondId))
      }

      // Remove not selected vehicles
      notSelectedVehicleIds.foreach { vehicleId =>
        scenario.getVehicles.removeVehicle(vehicleId)
        beamServices.privateVehicles.remove(vehicleId)
      }

      // Remove not selected households
      notSelectedHouseholdIds.foreach { housholdId =>
        scenario.getHouseholds.getHouseholds.remove(housholdId)
        scenario.getHouseholds.getHouseholdAttributes.removeAllAttributes(housholdId.toString)
      }

      // Remove not selected persons
      notSelectedPersonIds.foreach { personId =>
        scenario.getPopulation.removePerson(personId)
      }

      writeScenarioPrivateVehicles(scenario, beamServices, outputDir)

      val numOfHouseholds = scenario.getHouseholds.getHouseholds.values().size
      val vehicles = scenario.getHouseholds.getHouseholds.values.asScala.flatMap(hh => hh.getVehicleIds.asScala)
      val numOfPersons = scenario.getPopulation.getPersons.size()

      logger.info(s"""After sampling:
           |Number of households: $numOfHouseholds. Removed: ${notSelectedHouseholdIds.size}
           |Number of vehicles: ${getVehicleGroupingStringUsing(vehicles.toIndexedSeq, beamServices)}. Removed: ${getVehicleGroupingStringUsing(
                       notSelectedVehicleIds.toIndexedSeq,
                       beamServices
                     )}
           |Number of persons: $numOfPersons. Removed: ${notSelectedPersonIds.size}""".stripMargin)

      beamServices.personHouseholds = scenario.getHouseholds.getHouseholds
        .values()
        .asScala
        .flatMap(h => h.getMemberIds.asScala.map(_ -> h))
        .toMap

      val populationAdjustment = PopulationAdjustment.getPopulationAdjustment(beamServices)
      populationAdjustment.update(scenario)
    } else {
      val populationAdjustment = PopulationAdjustment.getPopulationAdjustment(beamServices)
      populationAdjustment.update(scenario)
      beamServices.personHouseholds = scenario.getHouseholds.getHouseholds
        .values()
        .asScala
        .flatMap(h => h.getMemberIds.asScala.map(_ -> h))
        .toMap
    }
  }

  private def getVehicleGroupingStringUsing(vehicleIds: IndexedSeq[Id[Vehicle]], beamServices: BeamServices): String = {
    vehicleIds
      .groupBy(
        vehicleId => beamServices.privateVehicles.get(vehicleId).map(_.beamVehicleType.id.toString).getOrElse("")
      )
      .map {
        case (vehicleType, ids) => s"$vehicleType (${ids.size})"
      }
      .mkString(" , ")
  }

  def getScenarioSource(beamServices: BeamServices, beamConfig: BeamConfig): ScenarioSource = {
    val src = beamConfig.beam.exchange.scenario.source.toLowerCase
    if (src == "urbansim") {
      val fileFormat: InputType = Option(beamConfig.beam.exchange.scenario.fileFormat)
        .map(str => InputType(str.toLowerCase))
        .getOrElse(
          throw new IllegalStateException(
            s"`beamConfig.beam.exchange.scenario.fileFormat` is null or empty!"
          )
        )
      val scenarioReader = fileFormat match {
        case InputType.CSV     => CsvScenarioReader
        case InputType.Parquet => ParquetScenarioReader
      }
      new UrbanSimScenarioSource(
        scenarioFolder = beamConfig.beam.exchange.scenario.folder,
        rdr = scenarioReader,
        geoUtils = beamServices.geo,
        shouldConvertWgs2Utm = beamConfig.beam.exchange.scenario.convertWgs2Utm
      )
    } else if (src == "matsim") {
      new MatsimScenarioSource(
        scenarioFolder = beamConfig.beam.exchange.scenario.folder,
        rdr = beam.utils.scenario.matsim.CsvScenarioReader
      )
    } else throw new NotImplementedError(s"ScenarioSource '$src' is not yet implemented")
  }
}

case class MapStringDouble(data: Map[String, Double])
case class Arguments(
  configLocation: Option[String] = None,
  config: Option[TypesafeConfig] = None,
  clusterType: Option[ClusterType] = None,
  nodeHost: Option[String] = None,
  nodePort: Option[String] = None,
  seedAddress: Option[String] = None,
  useLocalWorker: Option[Boolean] = None
) {
  val useCluster: Boolean = clusterType.isDefined
}

sealed trait ClusterType
case object Master extends ClusterType {
  override def toString = "master"
}
case object Worker extends ClusterType {
  override def toString = "worker"
}
