package beam.sim

import beam.agentsim.agents.choice.mode.{ModeIncentive, PtFares}
import beam.agentsim.agents.freight.FreightCarrier
import beam.agentsim.agents.freight.input.PayloadPlansConverter
import beam.agentsim.agents.ridehail.{RideHailIterationHistory, RideHailSurgePricingManager}
import beam.agentsim.agents.vehicles.VehicleCategory.MediumDutyPassenger
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.handling.BeamEventsHandling
import beam.agentsim.infrastructure.parking.LinkLevelOperations
import beam.agentsim.infrastructure.taz.{H3TAZ, TAZ, TAZTreeMap}
import beam.analysis.ActivityLocationPlotter
import beam.analysis.plots.{GraphSurgePricing, RideHailRevenueAnalysis}
import beam.matsim.{CustomPlansDumpingImpl, MatsimConfigUpdater}
import beam.replanning._
import beam.replanning.utilitybased.UtilityBasedModeChoice
import beam.router.Modes.BeamMode
import beam.router._
import beam.router.gtfs.{FareCalculator, GTFSUtils}
import beam.router.osm.TollCalculator
import beam.router.r5._
import beam.router.skim.core.{DriveTimeSkimmer, ODSkimmer, TAZSkimmer, TransitCrowdingSkimmer}
import beam.router.skim.{ActivitySimSkimmer, Skims}
import beam.scoring.BeamScoringFunctionFactory
import beam.sim.ArgumentsParser.{Arguments, Worker}
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.config._
import beam.sim.metrics.Metrics._
import beam.sim.metrics.{BeamStaticMetricsWriter, InfluxDbSimulationMetricCollector, SimulationMetricCollector}
import beam.sim.modules.{BeamAgentModule, UtilsModule}
import beam.sim.population.PopulationScaling
import beam.sim.termination.TerminationCriterionProvider
import beam.utils.BeamVehicleUtils.{readBeamVehicleTypeFile, readFuelTypeFile, readVehiclesFile}
import beam.utils._
import beam.utils.csv.readers
import beam.utils.plan.sampling.AvailableModeUtils
import beam.utils.scenario.generic.GenericScenarioSource
import beam.utils.scenario.matsim.BeamScenarioSource
import beam.utils.scenario.urbansim.censusblock.{ScenarioAdjuster, UrbansimReaderV2}
import beam.utils.scenario.urbansim.{CsvScenarioReader, ParquetScenarioReader, UrbanSimScenarioSource}
import beam.utils.scenario.{BeamScenarioLoader, InputType, PreviousRunPlanMerger, UrbanSimScenarioLoader}
import com.conveyal.r5.transit.TransportNetwork
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.google.inject
import com.google.inject.Scopes
import com.google.inject.name.Names
import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}
import com.typesafe.scalalogging.LazyLogging
import kamon.Kamon
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.{Activity, Plan, Population}
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup
import org.matsim.core.config.{Config => MatsimConfig}
import org.matsim.core.controler._
import org.matsim.core.controler.corelisteners.{ControlerDefaultCoreListenersModule, EventsHandling, PlansDumping}
import org.matsim.core.events.ParallelEventsManagerImpl
import org.matsim.core.scenario.{MutableScenario, ScenarioBuilder, ScenarioByInstanceModule, ScenarioUtils}
import org.matsim.core.trafficmonitoring.TravelTimeCalculator
import org.matsim.core.utils.collections.QuadTree
import org.matsim.households.{Household, Households}
import org.matsim.utils.objectattributes.AttributeConverter
import org.matsim.vehicles.Vehicle

import java.io.FileOutputStream
import java.nio.file.{Files, Paths, StandardCopyOption}
import java.time.ZonedDateTime
import java.util.Properties
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.sys.process.Process
import scala.util.{Random, Try}

trait BeamHelper extends LazyLogging {
  //  Kamon.init()

  private val originalConfigLocationPath = "originalConfigLocation"

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
    beamConfig: BeamConfig,
    scenario: Scenario,
    beamScenario: BeamScenario,
    abstractModule: Option[AbstractModule] = None
  ): com.google.inject.Module = {

    val updatedAbstractModule = abstractModule.getOrElse(RunBeam.configureDefaultAPI)
    AbstractModule.`override`(
      ListBuffer(
        new AbstractModule() {
          override def install(): Unit = {
            // MATSim defaults
            install(new NewControlerModule)
            install(new ScenarioByInstanceModule(scenario))
            install(new ControllerModule)
            install(new ControlerDefaultCoreListenersModule)

            // Beam Inject below:
            install(new ConfigModule(typesafeConfig, beamConfig))
            install(new BeamAgentModule(beamConfig))
            install(new UtilsModule)
          }
        },
        updatedAbstractModule
      ).asJava,
      new AbstractModule() {
        private val mapper = new ObjectMapper()
        mapper.registerModule(DefaultScalaModule)

        override def install(): Unit = {
          // This code will be executed 3 times due to this https://github.com/LBNL-UCB-STI/matsim/blob/master/matsim/src/main/java/org/matsim/core/controler/Injector.java#L99:L101
          // createMapBindingsForType is called 3 times. Be careful not to do expensive operations here
          bind(classOf[BeamConfigHolder])

          val maybeConfigLocation = if (typesafeConfig.hasPath(originalConfigLocationPath)) {
            Some(typesafeConfig.getString(originalConfigLocationPath))
          } else {
            None
          }
          val beamConfigChangesObservable = new BeamConfigChangesObservable(beamConfig, maybeConfigLocation)

          bind(classOf[MatsimConfigUpdater]).asEagerSingleton()

          bind(classOf[PlansDumping]).to(classOf[CustomPlansDumpingImpl])

          bind(classOf[BeamConfigChangesObservable]).toInstance(beamConfigChangesObservable)

          bind(classOf[TerminationCriterion]).toProvider(classOf[TerminationCriterionProvider])

          bind(classOf[PrepareForSim]).to(classOf[BeamPrepareForSim])
          bind(classOf[RideHailSurgePricingManager]).asEagerSingleton()

          addControlerListenerBinding().to(classOf[BeamSim])
          addControlerListenerBinding().to(classOf[BeamScoringFunctionFactory])
          addControlerListenerBinding().to(classOf[RouteHistory])

          addControlerListenerBinding().to(classOf[ActivityLocationPlotter])
          addControlerListenerBinding().to(classOf[GraphSurgePricing])
          bind(classOf[BeamOutputDataDescriptionGenerator])
          addControlerListenerBinding().to(classOf[RideHailRevenueAnalysis])
          bind(classOf[ModeIterationPlanCleaner])

          bindMobsim().to(classOf[BeamMobsim])
          bind(classOf[EventsHandling]).to(classOf[BeamEventsHandling])
          bindScoringFunctionFactory().to(classOf[BeamScoringFunctionFactory])
          if (getConfig.strategy().getPlanSelectorForRemoval == "tryToKeepOneOfEachClass") {
            bindPlanSelectorForRemoval().to(classOf[TryToKeepOneOfEachClass])
          }
          addPlanStrategyBinding("SelectExpBeta").to(classOf[BeamExpBeta])
          addPlanStrategyBinding("SwitchModalityStyle").to(classOf[SwitchModalityStyle])
          addPlanStrategyBinding("AddSupplementaryTrips").to(classOf[AddSupplementaryTrips])
          addPlanStrategyBinding("ClearRoutes").to(classOf[ClearRoutes])
          addPlanStrategyBinding("ClearModes").to(classOf[ClearModes])
          addPlanStrategyBinding("TimeMutator").to(classOf[BeamTimeMutator])
          addPlanStrategyBinding("StaticModes").to(classOf[StaticModes])
          addPlanStrategyBinding(BeamReplanningStrategy.UtilityBasedModeChoice.toString)
            .toProvider(classOf[UtilityBasedModeChoice])
          addAttributeConverterBinding(classOf[MapStringDouble])
            .toInstance(new AttributeConverter[MapStringDouble] {
              override def convertToString(o: scala.Any): String =
                mapper.writeValueAsString(o.asInstanceOf[MapStringDouble].data)

              override def convert(value: String): MapStringDouble =
                MapStringDouble(mapper.readValue(value, classOf[Map[String, Double]]))
            })
          bind(classOf[BeamScenario]).toInstance(beamScenario)
          bind(classOf[TransportNetwork]).toInstance(beamScenario.transportNetwork)
          bind(classOf[TravelTimeCalculator]).toInstance(
            new FakeTravelTimeCalculator(
              beamScenario.network,
              new TravelTimeCalculatorConfigGroup()
            )
          )
          bind(classOf[beam.router.r5.BikeLanesData]).toInstance(BikeLanesData(beamConfig))
          bind(classOf[BikeLanesAdjustment]).in(Scopes.SINGLETON)
          bind(classOf[NetworkHelper]).to(classOf[NetworkHelperImpl]).asEagerSingleton()
          bind(classOf[RideHailIterationHistory]).asEagerSingleton()
          bind(classOf[RouteHistory]).asEagerSingleton()
          bind(classOf[FareCalculator]).asEagerSingleton()
          bind(classOf[TollCalculator]).asEagerSingleton()
          bind(classOf[ODSkimmer]).asEagerSingleton()
          bind(classOf[TAZSkimmer]).asEagerSingleton()
          bind(classOf[DriveTimeSkimmer]).asEagerSingleton()
          bind(classOf[TransitCrowdingSkimmer]).asEagerSingleton()
          bind(classOf[ActivitySimSkimmer]).asEagerSingleton()
          bind(classOf[Skims]).asEagerSingleton()

          // We cannot bind the RideHailFleetInitializer directly (e.g., with
          // bind(classOf[RideHailFleetInitializer]).toProvider(classOf[RideHailFleetInitializerProvider])
          // .asEagerSingleton()) because we get a circular dependency.
          bind(classOf[RideHailFleetInitializerProvider]).asEagerSingleton()

          bind(classOf[EventsManager]).to(classOf[LoggingEventsManager]).asEagerSingleton()
          bind(classOf[EventsManager]).annotatedWith(Names.named("ParallelEM")).to(classOf[ParallelEventsManagerImpl])
          bind(classOf[SimulationMetricCollector]).to(classOf[InfluxDbSimulationMetricCollector]).asEagerSingleton()

        }
      }
    )
  }

  def loadScenario(beamConfig: BeamConfig): BeamScenario = {
    val vehicleTypes = maybeScaleTransit(beamConfig, readBeamVehicleTypeFile(beamConfig))
    val vehicleCsvReader = new VehicleCsvReader(beamConfig)
    val baseFilePath = Paths.get(beamConfig.beam.agentsim.agents.vehicles.vehicleTypesFilePath).getParent

    val consumptionRateFilterStore =
      new ConsumptionRateFilterStoreImpl(
        vehicleCsvReader.getVehicleEnergyRecordsUsing,
        Option(baseFilePath.toString),
        primaryConsumptionRateFilePathsByVehicleType =
          vehicleTypes.values.map(x => (x, x.primaryVehicleEnergyFile)).toIndexedSeq,
        secondaryConsumptionRateFilePathsByVehicleType =
          vehicleTypes.values.map(x => (x, x.secondaryVehicleEnergyFile)).toIndexedSeq
      )

    val dates = DateUtils(
      ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,
      ZonedDateTime.parse(beamConfig.beam.routing.baseDate)
    )

    val networkCoordinator = buildNetworkCoordinator(beamConfig)
    val gtfs = GTFSUtils.loadGTFS(beamConfig.beam.routing.r5.directory)
    val trainStopQuadTree = GTFSUtils.toQuadTree(GTFSUtils.trainStations(gtfs), new GeoUtilsImpl(beamConfig))
    val tazMap = TAZTreeMap.getTazTreeMap(beamConfig.beam.agentsim.taz.filePath)
    val exchangeGeo = beamConfig.beam.exchange.output.geo.filePath.map(TAZTreeMap.getTazTreeMap)
    val linkQuadTree: QuadTree[Link] =
      LinkLevelOperations.getLinkTreeMap(networkCoordinator.network.getLinks.values().asScala.toSeq)
    val linkIdMapping: Map[Id[Link], Link] = LinkLevelOperations.getLinkIdMapping(networkCoordinator.network)
    val linkToTAZMapping: Map[Link, TAZ] = LinkLevelOperations.getLinkToTazMapping(networkCoordinator.network, tazMap)
    val freightCarriers = if (beamConfig.beam.agentsim.agents.freight.enabled) {
      val rand: Random = new Random(beamConfig.matsim.modules.global.randomSeed)
      val tours = PayloadPlansConverter.readFreightTours(beamConfig.beam.agentsim.agents.freight.toursFilePath)
      val plans =
        PayloadPlansConverter.readPayloadPlans(beamConfig.beam.agentsim.agents.freight.plansFilePath, tazMap, rand)
      PayloadPlansConverter.readFreightCarriers(
        beamConfig.beam.agentsim.agents.freight.carriersFilePath,
        tours,
        plans,
        vehicleTypes,
        tazMap,
        rand
      )
    } else {
      IndexedSeq.empty[FreightCarrier]
    }

    val fixedActivitiesDurationsFromConfig = {
      val maybeFixedDurationsList = beamConfig.beam.agentsim.agents.activities.activityTypeToFixedDurationMap
      BeamConfigUtils
        .parseListToMap(maybeFixedDurationsList.getOrElse(List.empty[String]))
        .map { case (activityType, stringDuration) => activityType -> stringDuration.toDouble }
    }

    BeamScenario(
      readFuelTypeFile(beamConfig.beam.agentsim.agents.vehicles.fuelTypesFilePath).toMap,
      vehicleTypes,
      privateVehicles(beamConfig, vehicleTypes) ++ freightCarriers.flatMap(_.fleet),
      new VehicleEnergy(consumptionRateFilterStore, vehicleCsvReader.getLinkToGradeRecordsUsing),
      beamConfig,
      dates,
      PtFares(beamConfig.beam.agentsim.agents.ptFare.filePath),
      networkCoordinator.transportNetwork,
      networkCoordinator.network,
      trainStopQuadTree,
      tazMap,
      exchangeGeo,
      linkQuadTree,
      linkIdMapping,
      linkToTAZMapping,
      ModeIncentive(beamConfig.beam.agentsim.agents.modeIncentive.filePath),
      H3TAZ(networkCoordinator.network, tazMap, beamConfig),
      freightCarriers,
      fixedActivitiesDurations = fixedActivitiesDurationsFromConfig
    )
  }

  def vehicleEnergy(beamConfig: BeamConfig, vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType]): VehicleEnergy = {
    val baseFilePath = Paths.get(beamConfig.beam.agentsim.agents.vehicles.vehicleTypesFilePath).getParent
    val vehicleCsvReader = new VehicleCsvReader(beamConfig)
    val consumptionRateFilterStore =
      new ConsumptionRateFilterStoreImpl(
        vehicleCsvReader.getVehicleEnergyRecordsUsing,
        Option(baseFilePath.toString),
        primaryConsumptionRateFilePathsByVehicleType =
          vehicleTypes.values.map(x => (x, x.primaryVehicleEnergyFile)).toIndexedSeq,
        secondaryConsumptionRateFilePathsByVehicleType =
          vehicleTypes.values.map(x => (x, x.secondaryVehicleEnergyFile)).toIndexedSeq
      )
    // TODO Fix me once `TrieMap` is removed
    new VehicleEnergy(
      consumptionRateFilterStore,
      vehicleCsvReader.getLinkToGradeRecordsUsing
    )
  }

  def privateVehicles(
    beamConfig: BeamConfig,
    vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType]
  ): TrieMap[Id[BeamVehicle], BeamVehicle] =
    if (beamConfig.beam.agentsim.agents.population.useVehicleSampling) {
      TrieMap[Id[BeamVehicle], BeamVehicle]()
    } else {
      TrieMap(
        readVehiclesFile(
          beamConfig.beam.agentsim.agents.vehicles.vehiclesFilePath,
          vehicleTypes,
          beamConfig.matsim.modules.global.randomSeed,
          VehicleManager.AnyManager.managerId
        ).toSeq: _*
      )
    }

  // Note that this assumes standing room is only available on transit vehicles. Not sure of any counterexamples modulo
  // say, a yacht or personal bus, but I think this will be fine for now.
  // New Feb-2020: Switched over to MediumDutyPassenger -> Transit to solve issue with AV shuttles
  private def maybeScaleTransit(beamConfig: BeamConfig, vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType]) = {
    beamConfig.beam.agentsim.tuning.transitCapacity match {
      case Some(scalingFactor) =>
        vehicleTypes.map { case (id, bvt) =>
          id -> (if (bvt.vehicleCategory == MediumDutyPassenger)
                   bvt.copy(
                     seatingCapacity = Math.ceil(bvt.seatingCapacity.toDouble * scalingFactor).toInt,
                     standingRoomCapacity = Math.ceil(bvt.standingRoomCapacity.toDouble * scalingFactor).toInt
                   )
                 else
                   bvt)
        }
      case None => vehicleTypes
    }
  }

  def runBeamUsing(
    args: Array[String],
    abstractModule: Option[AbstractModule],
    isConfigArgRequired: Boolean = true
  ): Unit = {
    val (parsedArgs, config) = prepareConfig(args, isConfigArgRequired)

    parsedArgs.clusterType match {
      case Some(Worker) => runClusterWorkerUsing(config) //Only the worker requires a different path
      case _ =>
        val (_, outputDirectory, _) =
          runBeamWithConfig(config, Some(abstractModule.getOrElse(RunBeam.configureDefaultAPI)))
        postRunActivity(parsedArgs.configLocation.get, config, outputDirectory)
    }
  }

  def prepareConfig(args: Array[String], isConfigArgRequired: Boolean): (Arguments, TypesafeConfig) = {
    val parsedArgs = ArgumentsParser.parseArguments(args) match {
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

    val originalConfigFileLocation = parsedArgs.configLocation.get
    ConfigConsistencyComparator.parseBeamTemplateConfFile(originalConfigFileLocation)

    if (originalConfigFileLocation.contains("\\")) {
      throw new RuntimeException("wrong config path, expected:forward slash, found: backward slash")
    }

    val location: TypesafeConfig = ConfigFactory.parseString(s"""config="$originalConfigFileLocation"""")

    // need this for BeamConfigChangesObservable.
    // We can't use 'config' key for that because for many tests it is usually pointing to the beamville config
    val originalConfigLocation: TypesafeConfig =
      ConfigFactory.parseString(s"""$originalConfigLocationPath="$originalConfigFileLocation"""")

    val configFromArgs =
      if (parsedArgs.useCluster) updateConfigForClusterUsing(parsedArgs, parsedArgs.config.get)
      else parsedArgs.config.get

    val config = embedSelectArgumentsIntoConfig(parsedArgs, configFromArgs)
      .withFallback(location)
      .withFallback(originalConfigLocation)
      .resolve()

    checkDockerIsInstalledForCCHPhysSim(config)

    (parsedArgs, config)
  }

  private def checkDockerIsInstalledForCCHPhysSim(config: TypesafeConfig): Unit = {
    val physsimName = Try(config.getString("beam.physsim.name")).getOrElse("")
    if (physsimName.isEmpty) {
      logger.info("beam.physsim.name is not set in config")
    }
    if (physsimName == "CCHRoutingAssignment") {
      // Exception will be thrown if docker is not available on device
      if (Try(Process("docker version").!!).isFailure) {
        throw new RuntimeException("Docker is required to run CCHRoutingAssignment physsim simulation")
      }
    }
  }

  private def postRunActivity(configLocation: String, config: TypesafeConfig, outputDirectory: String) = {
    val props = new Properties()
    props.setProperty("commitHash", BashUtils.getCommitHash)
    props.setProperty("configFile", configLocation)
    val out = new FileOutputStream(Paths.get(outputDirectory, "beam.properties").toFile)
    props.store(out, "Simulation out put props.")
    val beamConfig = BeamConfig(config)
    if (
      beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass
        .equalsIgnoreCase("ModeChoiceLCCM")
    ) {
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
      .parseString("""
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
    Await.ready(
      system.whenTerminated.map(_ => {
        logger.info("Exiting BEAM")
      }),
      scala.concurrent.duration.Duration.Inf
    )
  }

  def runBeamWithConfig(
    config: TypesafeConfig,
    abstractModule: Option[AbstractModule] = None
  ): (MatsimConfig, String, BeamServices) = {
    val (
      beamExecutionConfig: BeamExecutionConfig,
      scenario: MutableScenario,
      beamScenario: BeamScenario,
      services: BeamServices,
      plansMerged: Boolean
    ) = prepareBeamService(config, abstractModule)

    runBeam(
      services,
      scenario,
      beamScenario,
      beamExecutionConfig.outputDirectory,
      plansMerged
    )
    (scenario.getConfig, beamExecutionConfig.outputDirectory, services)
  }

  def prepareBeamService(
    config: TypesafeConfig,
    abstractModule: Option[AbstractModule]
  ): (BeamExecutionConfig, MutableScenario, BeamScenario, BeamServices, Boolean) = {
    val beamExecutionConfig = updateConfigWithWarmStart(setupBeamWithConfig(config))
    val (scenario, beamScenario, plansMerged) = buildBeamServicesAndScenario(
      beamExecutionConfig.beamConfig,
      beamExecutionConfig.matsimConfig
    )
    logger.info(s"Java version: ${System.getProperty("java.version")}")
    logger.info(
      "JVM args: " + java.lang.management.ManagementFactory.getRuntimeMXBean.getInputArguments.asScala.toList.toString()
    )
    logger.info(s"Heap size: ${MathUtils.formatBytes(Runtime.getRuntime.totalMemory())}")
    logger.info(s"Heap max memory: ${MathUtils.formatBytes(Runtime.getRuntime.maxMemory())}")
    logger.info(s"Heap free memory: ${MathUtils.formatBytes(Runtime.getRuntime.freeMemory())}")

    val logStart = {
      val populationSize = scenario.getPopulation.getPersons.size()
      val vehiclesSize = scenario.getVehicles.getVehicles.size()
      val lanesSize = scenario.getLanes.getLanesToLinkAssignments.size()

      val logHHsize = scenario.getHouseholds.getHouseholds.size()
      val logBeamPrivateVehiclesSize = beamScenario.privateVehicles.size
      val logVehicleTypeSize = beamScenario.vehicleTypes.size
      val modIncentivesSize = beamScenario.modeIncentives.modeIncentives.size
      s"""
         |Scenario population size: $populationSize
         |Scenario vehicles size: $vehiclesSize
         |Scenario lanes size: $lanesSize
         |BeamScenario households size: $logHHsize
         |BeamScenario privateVehicles size: $logBeamPrivateVehiclesSize
         |BeamScenario vehicleTypes size: $logVehicleTypeSize
         |BeamScenario modIncentives size $modIncentivesSize
         |""".stripMargin
    }
    logger.warn(logStart)

    val injector: inject.Injector =
      buildInjector(config, beamExecutionConfig.beamConfig, scenario, beamScenario, abstractModule)

    val services = injector.getInstance(classOf[BeamServices])
    (beamExecutionConfig, scenario, beamScenario, services, plansMerged)
  }

  def fixDanglingPersons(result: MutableScenario): Unit = {
    val peopleViaHousehold = result.getHouseholds.getHouseholds
      .values()
      .asScala
      .flatMap { x =>
        x.getMemberIds.asScala
      }
      .toSet
    val danglingPeople = result.getPopulation.getPersons
      .values()
      .asScala
      .filter(person => !peopleViaHousehold.contains(person.getId))
    if (danglingPeople.nonEmpty) {
      logger.error(s"There are ${danglingPeople.size} persons not connected to household, removing them")
      danglingPeople.foreach { p =>
        result.getPopulation.removePerson(p.getId)
      }
    }
  }

  protected def buildScenarioFromMatsimConfig(
    matsimConfig: MatsimConfig,
    beamScenario: BeamScenario
  ): MutableScenario = {
    val result = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    fixDanglingPersons(result)
    result.setNetwork(beamScenario.network)
    result
  }

  def buildBeamServices(injector: inject.Injector): BeamServices = {
    val result = injector.getInstance(classOf[BeamServices])
    result
  }

  protected def buildInjector(
    config: TypesafeConfig,
    beamConfig: BeamConfig,
    scenario: MutableScenario,
    beamScenario: BeamScenario,
    abstractModule: Option[AbstractModule] = None
  ): inject.Injector = {
    org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      module(config, beamConfig, scenario, beamScenario, abstractModule)
    )
  }

  def runBeam(
    beamServices: BeamServices,
    scenario: MutableScenario,
    beamScenario: BeamScenario,
    outputDir: String,
    plansMerged: Boolean
  ): Unit = {
    if (!beamScenario.beamConfig.beam.agentsim.fractionOfPlansWithSingleActivity.equals(0d)) {
      applyFractionOfPlansWithSingleActivity(scenario, beamServices.beamConfig, scenario.getConfig)
    }

    if (!plansMerged) {
      PopulationScaling.samplePopulation(scenario, beamScenario, beamServices.beamConfig, beamServices, outputDir)
    }

    // write static metrics, such as population size, vehicles fleet size, etc.
    // necessary to be called after population sampling
    BeamStaticMetricsWriter.writeSimulationParameters(
      scenario,
      beamScenario,
      beamServices,
      beamServices.beamConfig
    )

    val houseHoldVehiclesInScenario: Iterable[Id[Vehicle]] = scenario.getHouseholds.getHouseholds
      .values()
      .asScala
      .flatMap(_.getVehicleIds.asScala)

    val vehiclesGroupedByType = houseHoldVehiclesInScenario.groupBy(v =>
      beamScenario.privateVehicles.get(v).map(_.beamVehicleType.id.toString).getOrElse("")
    )
    val vehicleInfo = vehiclesGroupedByType.map { case (vehicleType, groupedValues) =>
      s"$vehicleType (${groupedValues.size})"
    } mkString " , "
    logger.info(s"Vehicles assigned to households : $vehicleInfo")

    run(beamServices)
  }

  private def applyFractionOfPlansWithSingleActivity(
    scenario: MutableScenario,
    beamConfig: BeamConfig,
    matSimConf: MatsimConfig
  ): Unit = {
    val random = new Random(matSimConf.global().getRandomSeed)

    val people = random.shuffle(scenario.getPopulation.getPersons.values().asScala)

    val peopleForRemovingWorkActivities =
      (people.size * beamConfig.beam.agentsim.fractionOfPlansWithSingleActivity).toInt

    if (!beamConfig.beam.agentsim.agents.tripBehaviors.mulitnomialLogit.generate_secondary_activities) {
      people
        .take(peopleForRemovingWorkActivities)
        .map(_.getId)
        .foreach(scenario.getPopulation.removePerson)
    } else {
      people
        .take(peopleForRemovingWorkActivities)
        .flatMap(p => p.getPlans.asScala.toSeq)
        .filter(_.getPlanElements.size() > 1)
        .foreach { plan =>
          val planElements = plan.getPlanElements
          val firstActivity = planElements.get(0)
          firstActivity.asInstanceOf[Activity].setEndTime(Double.NegativeInfinity)
          planElements.clear()
          planElements.add(firstActivity)
        }

      people
        .groupBy(
          _.getSelectedPlan.getPlanElements.asScala
            .collect { case activity: Activity => activity.getType }
            .mkString("->")
        )
        .filter(_._2.size > 1) // too many of them and we don't need such unique data in logs
        .toSeq
        .sortBy(_._2.size)(Ordering[Int].reverse)
        .foreach { case (planKey, people) =>
          logger.info("There are {} people with plan `{}`", people.size, planKey)
        }
    }
  }

  protected def buildBeamServicesAndScenario(
    beamConfig: BeamConfig,
    matsimConfig: MatsimConfig
  ): (MutableScenario, BeamScenario, Boolean) = {
    val scenarioConfig = beamConfig.beam.exchange.scenario

    val src = scenarioConfig.source.toLowerCase

    val fileFormat = scenarioConfig.fileFormat

    val geoUtils = new GeoUtilsImpl(beamConfig)
    val (scenario, beamScenario, plansMerged) =
      ProfilingUtils.timed(s"Load scenario using $src/$fileFormat", x => logger.info(x)) {
        if (src == "urbansim" || src == "urbansim_v2" || src == "generic") {
          val beamScenario = loadScenario(beamConfig)
          val emptyScenario = ScenarioBuilder(matsimConfig, beamScenario.network).build
          val geoUtils = new GeoUtilsImpl(beamConfig)
          val (scenario, plansMerged) = {
            val source = src match {
              case "urbansim" => buildUrbansimScenarioSource(geoUtils, beamConfig)
              case "urbansim_v2" => {
                val pathToHouseholds = s"${beamConfig.beam.exchange.scenario.folder}/households.csv.gz"
                val pathToPersonFile = s"${beamConfig.beam.exchange.scenario.folder}/persons.csv.gz"
                val pathToPlans = s"${beamConfig.beam.exchange.scenario.folder}/plans.csv.gz"
                val pathToTrips = s"${beamConfig.beam.exchange.scenario.folder}/trips.csv.gz"
                val pathToBlocks = s"${beamConfig.beam.exchange.scenario.folder}/blocks.csv.gz"
                new UrbansimReaderV2(
                  inputPersonPath = pathToPersonFile,
                  inputPlanPath = pathToPlans,
                  inputHouseholdPath = pathToHouseholds,
                  inputTripsPath = pathToTrips,
                  inputBlockPath = pathToBlocks,
                  geoUtils,
                  shouldConvertWgs2Utm = beamConfig.beam.exchange.scenario.convertWgs2Utm,
                  modeMap = BeamConfigUtils.parseListToMap(
                    beamConfig.beam.exchange.scenario.modeMap
                      .getOrElse(throw new RuntimeException("beam.exchange.scenario.modeMap must be set"))
                  )
                )
              }
              case "generic" => {
                val pathToHouseholds = s"${beamConfig.beam.exchange.scenario.folder}/households.csv.gz"
                val pathToPersonFile = s"${beamConfig.beam.exchange.scenario.folder}/persons.csv.gz"
                val pathToPlans = s"${beamConfig.beam.exchange.scenario.folder}/plans.csv.gz"
                new GenericScenarioSource(
                  pathToHouseholds = pathToHouseholds,
                  pathToPersonFile = pathToPersonFile,
                  pathToPlans = pathToPlans,
                  geoUtils,
                  shouldConvertWgs2Utm = beamConfig.beam.exchange.scenario.convertWgs2Utm
                )
              }
            }
            val merger = new PreviousRunPlanMerger(
              beamConfig.beam.agentsim.agents.plans.merge.fraction,
              beamConfig.beam.agentsim.agentSampleSizeAsFractionOfPopulation,
              Paths.get(beamConfig.beam.input.lastBaseOutputDir),
              beamConfig.beam.input.simulationPrefix,
              new Random(),
              planElement =>
                planElement.activityEndTime
                  .map(time => planElement.copy(activityEndTime = Some(time / 3600)))
                  .getOrElse(planElement)
            )
            val (scenario, plansMerged) =
              new UrbanSimScenarioLoader(
                emptyScenario,
                beamScenario,
                source,
                new GeoUtilsImpl(beamConfig),
                Some(merger)
              ).loadScenario()
            if (src == "urbansim_v2") {
              new ScenarioAdjuster(
                beamConfig.beam.urbansim,
                scenario.getPopulation,
                beamConfig.matsim.modules.global.randomSeed
              ).adjust()
            }
            (scenario.asInstanceOf[MutableScenario], plansMerged)
          }
          (scenario, beamScenario, plansMerged)
        } else if (src == "beam") {
          fileFormat match {
            case "csv" =>
              val beamScenario = loadScenario(beamConfig)
              val scenario = {
                val source = new BeamScenarioSource(
                  beamConfig,
                  rdr = readers.BeamCsvScenarioReader
                )
                val scenarioBuilder = ScenarioBuilder(matsimConfig, beamScenario.network)
                new BeamScenarioLoader(scenarioBuilder, beamScenario, source, new GeoUtilsImpl(beamConfig))
                  .loadScenario()
              }.asInstanceOf[MutableScenario]
              (scenario, beamScenario, false)
            case "xml" =>
              val beamScenario = loadScenario(beamConfig)
              val scenario = {
                val result = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
                fixDanglingPersons(result)
                result
              }
              (scenario, beamScenario, false)
            case unknown =>
              throw new IllegalArgumentException(s"Beam does not support [$unknown] file type")
          }
        } else {
          throw new NotImplementedError(s"ScenarioSource '$src' is not yet implemented")
        }
      }
    generatePopulationForPayloadPlans(
      beamConfig,
      geoUtils,
      beamScenario,
      scenario.getPopulation,
      scenario.getHouseholds
    )
    (scenario, beamScenario, plansMerged)
  }

  def generatePopulationForPayloadPlans(
    beamConfig: BeamConfig,
    geoUtils: GeoUtils,
    beamScenario: BeamScenario,
    population: Population,
    households: Households
  ): Unit = {
    if (beamConfig.beam.agentsim.agents.freight.enabled) {
      val convertWgs2Utm = beamConfig.beam.exchange.scenario.convertWgs2Utm
      val plans: IndexedSeq[(Household, Plan)] = PayloadPlansConverter.generatePopulation(
        beamScenario.freightCarriers,
        population.getFactory,
        households.getFactory,
        if (convertWgs2Utm) Some(geoUtils) else None
      )

      val allowedModes = Seq(BeamMode.CAR.value)
      plans.foreach { case (household, plan) =>
        households.getHouseholds.put(household.getId, household)
        population.addPerson(plan.getPerson)
        AvailableModeUtils.setAvailableModesForPerson_v2(
          beamScenario,
          plan.getPerson,
          household,
          population,
          allowedModes
        )
        val freightVehicle = beamScenario.privateVehicles(household.getVehicleIds.get(0))
        households.getHouseholdAttributes
          .putAttribute(household.getId.toString, "homecoordx", freightVehicle.spaceTime.loc.getX)
        households.getHouseholdAttributes
          .putAttribute(household.getId.toString, "homecoordy", freightVehicle.spaceTime.loc.getY)
      }
    }
  }

  def setupBeamWithConfig(
    config: TypesafeConfig
  ): BeamExecutionConfig = {
    val beamConfig = BeamConfig(config)
    val outputDirectory = FileUtils.getConfigOutputFile(
      beamConfig.beam.outputs.baseOutputDirectory,
      beamConfig.beam.agentsim.simulationName,
      beamConfig.beam.outputs.addTimestampToOutputDirectory
    )
    LoggingUtil.initLogger(outputDirectory, beamConfig.beam.logger.keepConsoleAppenderOn)
    logger.debug(s"Beam output directory is: $outputDirectory")
    logger.info(ConfigConsistencyComparator.getMessage.getOrElse(""))

    val errors = InputConsistencyCheck.checkConsistency(beamConfig)
    if (errors.nonEmpty) {
      logger.error("Input consistency check failed:\n" + errors.mkString("\n"))
    }

    level = beamConfig.beam.metrics.level
    runName = beamConfig.beam.agentsim.simulationName
    if (isMetricsEnable) {
      Kamon.init(config.withFallback(ConfigFactory.load()))
    }

    logger.info("Starting beam on branch {} at commit {}.", BashUtils.getBranch, BashUtils.getCommitHash)

    logger.info(
      s"Maximum Memory (-Xmx): ${math.round(10.0 * Runtime.getRuntime.maxMemory() / Math.pow(1000, 3)) / 10.0} (GB)"
    )

    prepareDirectories(config, beamConfig, outputDirectory)

    ConfigHelper.writeFullConfigs(config, outputDirectory)

    val matsimConfig: MatsimConfig = buildMatsimConfig(config, beamConfig, outputDirectory)

    BeamExecutionConfig(beamConfig, matsimConfig, outputDirectory)
  }

  protected def buildNetworkCoordinator(beamConfig: BeamConfig): NetworkCoordinator = {
    val result = if (Files.isRegularFile(Paths.get(beamConfig.beam.agentsim.scenarios.frequencyAdjustmentFile))) {
      FrequencyAdjustingNetworkCoordinator(beamConfig)
    } else {
      DefaultNetworkCoordinator(beamConfig)
    }
    result.init()
    result
  }

  private def updateConfigWithWarmStart(beamExecutionConfig: BeamExecutionConfig): BeamExecutionConfig = {
    BeamWarmStart.updateExecutionConfig(beamExecutionConfig)
  }

  private def prepareDirectories(config: TypesafeConfig, beamConfig: BeamConfig, outputDirectory: String): Unit = {
    new java.io.File(outputDirectory).mkdirs
    val location = config.getString("config")

    val confNameToPath = BeamConfigUtils.getFileNameToPath(location)

    logger.info("Processing configs for [{}] simulation.", beamConfig.beam.agentsim.simulationName)
    confNameToPath.foreach { case (fileName, filePath) =>
      val outFile = Paths.get(outputDirectory, fileName)
      Files.copy(Paths.get(filePath), outFile, StandardCopyOption.REPLACE_EXISTING)
      logger.info("Config '{}' copied to '{}'.", filePath, outFile)
    }
  }

  def buildMatsimConfig(
    config: TypesafeConfig,
    beamConfig: BeamConfig,
    outputDirectory: String
  ): MatsimConfig = {
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val result = configBuilder.buildMatSimConf()
    if (!beamConfig.beam.outputs.writeGraphs) {
      result.counts.setOutputFormat("txt")
      result.controler.setCreateGraphs(false)
    }
    result.planCalcScore().setMemorizingExperiencedPlans(true)
    result.controler.setOutputDirectory(outputDirectory)
    result.controler().setWritePlansInterval(beamConfig.beam.outputs.writePlansInterval)
    result
  }

  def run(beamServices: BeamServices) {
    beamServices.controler.run()
  }

  private def buildUrbansimScenarioSource(
    geo: GeoUtils,
    beamConfig: BeamConfig
  ): UrbanSimScenarioSource = {
    val fileFormat: InputType = Option(beamConfig.beam.exchange.scenario.fileFormat)
      .map(str => InputType(str.toLowerCase))
      .getOrElse(
        throw new IllegalStateException(
          "`beamConfig.beam.exchange.scenario.fileFormat` is null or empty!"
        )
      )
    val scenarioReader = fileFormat match {
      case InputType.CSV     => CsvScenarioReader
      case InputType.Parquet => ParquetScenarioReader
    }

    new UrbanSimScenarioSource(
      scenarioSrc = beamConfig.beam.exchange.scenario.folder,
      rdr = scenarioReader,
      geoUtils = geo,
      shouldConvertWgs2Utm = beamConfig.beam.exchange.scenario.convertWgs2Utm
    )
  }
}

case class MapStringDouble(data: Map[String, Double])
