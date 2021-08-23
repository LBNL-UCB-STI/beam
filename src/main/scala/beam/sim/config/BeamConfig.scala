// source: src/main/resources/beam-template.conf

package beam.sim.config

case class BeamConfig(
  beam: BeamConfig.Beam,
  matsim: BeamConfig.Matsim
)

object BeamConfig {

  case class Beam(
    actorSystemName: java.lang.String,
    agentsim: BeamConfig.Beam.Agentsim,
    calibration: BeamConfig.Beam.Calibration,
    cluster: BeamConfig.Beam.Cluster,
    debug: BeamConfig.Beam.Debug,
    exchange: BeamConfig.Beam.Exchange,
    experimental: BeamConfig.Beam.Experimental,
    input: BeamConfig.Beam.Input,
    inputDirectory: java.lang.String,
    logger: BeamConfig.Beam.Logger,
    metrics: BeamConfig.Beam.Metrics,
    outputs: BeamConfig.Beam.Outputs,
    physsim: BeamConfig.Beam.Physsim,
    replanning: BeamConfig.Beam.Replanning,
    router: BeamConfig.Beam.Router,
    routing: BeamConfig.Beam.Routing,
    sim: BeamConfig.Beam.Sim,
    spatial: BeamConfig.Beam.Spatial,
    urbansim: BeamConfig.Beam.Urbansim,
    useLocalWorker: scala.Boolean,
    warmStart: BeamConfig.Beam.WarmStart
  )

  object Beam {

    case class Agentsim(
      agentSampleSizeAsFractionOfPopulation: scala.Double,
      agents: BeamConfig.Beam.Agentsim.Agents,
      chargingNetworkManager: BeamConfig.Beam.Agentsim.ChargingNetworkManager,
      endTime: java.lang.String,
      firstIteration: scala.Int,
      fractionOfPlansWithSingleActivity: scala.Double,
      h3taz: BeamConfig.Beam.Agentsim.H3taz,
      lastIteration: scala.Int,
      populationAdjustment: java.lang.String,
      scenarios: BeamConfig.Beam.Agentsim.Scenarios,
      scheduleMonitorTask: BeamConfig.Beam.Agentsim.ScheduleMonitorTask,
      schedulerParallelismWindow: scala.Int,
      simulationName: java.lang.String,
      taz: BeamConfig.Beam.Agentsim.Taz,
      thresholdForMakingParkingChoiceInMeters: scala.Int,
      thresholdForWalkingInMeters: scala.Int,
      timeBinSize: scala.Int,
      toll: BeamConfig.Beam.Agentsim.Toll,
      tuning: BeamConfig.Beam.Agentsim.Tuning
    )

    object Agentsim {

      case class Agents(
        bodyType: java.lang.String,
        freight: BeamConfig.Beam.Agentsim.Agents.Freight,
        households: BeamConfig.Beam.Agentsim.Agents.Households,
        modalBehaviors: BeamConfig.Beam.Agentsim.Agents.ModalBehaviors,
        modeIncentive: BeamConfig.Beam.Agentsim.Agents.ModeIncentive,
        parking: BeamConfig.Beam.Agentsim.Agents.Parking,
        plans: BeamConfig.Beam.Agentsim.Agents.Plans,
        population: BeamConfig.Beam.Agentsim.Agents.Population,
        ptFare: BeamConfig.Beam.Agentsim.Agents.PtFare,
        rideHail: BeamConfig.Beam.Agentsim.Agents.RideHail,
        rideHailTransit: BeamConfig.Beam.Agentsim.Agents.RideHailTransit,
        tripBehaviors: BeamConfig.Beam.Agentsim.Agents.TripBehaviors,
        vehicles: BeamConfig.Beam.Agentsim.Agents.Vehicles
      )

      object Agents {

        case class Freight(
          carrierParkingFilePath: scala.Option[java.lang.String],
          carriersFilePath: java.lang.String,
          enabled: scala.Boolean,
          name: java.lang.String,
          plansFilePath: java.lang.String,
          replanning: BeamConfig.Beam.Agentsim.Agents.Freight.Replanning,
          toursFilePath: java.lang.String
        )

        object Freight {

          case class Replanning(
            departureTime: scala.Int,
            disableAfterIteration: scala.Int,
            strategy: java.lang.String
          )

          object Replanning {

            def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.Freight.Replanning = {
              BeamConfig.Beam.Agentsim.Agents.Freight.Replanning(
                departureTime = if (c.hasPathOrNull("departureTime")) c.getInt("departureTime") else 28800,
                disableAfterIteration =
                  if (c.hasPathOrNull("disableAfterIteration")) c.getInt("disableAfterIteration") else -1,
                strategy = if (c.hasPathOrNull("strategy")) c.getString("strategy") else "singleTour"
              )
            }
          }

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.Freight = {
            BeamConfig.Beam.Agentsim.Agents.Freight(
              carrierParkingFilePath =
                if (c.hasPathOrNull("carrierParkingFilePath")) Some(c.getString("carrierParkingFilePath")) else None,
              carriersFilePath =
                if (c.hasPathOrNull("carriersFilePath")) c.getString("carriersFilePath")
                else "/test/input/beamville/freight/freight-carriers.csv",
              enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled"),
              name = if (c.hasPathOrNull("name")) c.getString("name") else "Freight",
              plansFilePath =
                if (c.hasPathOrNull("plansFilePath")) c.getString("plansFilePath")
                else "/test/input/beamville/freight/payload-plans.csv",
              replanning = BeamConfig.Beam.Agentsim.Agents.Freight.Replanning(
                if (c.hasPathOrNull("replanning")) c.getConfig("replanning")
                else com.typesafe.config.ConfigFactory.parseString("replanning{}")
              ),
              toursFilePath =
                if (c.hasPathOrNull("toursFilePath")) c.getString("toursFilePath")
                else "/test/input/beamville/freight/freight-tours.csv"
            )
          }
        }

        case class Households(
          inputFilePath: java.lang.String,
          inputHouseholdAttributesFilePath: java.lang.String
        )

        object Households {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.Households = {
            BeamConfig.Beam.Agentsim.Agents.Households(
              inputFilePath =
                if (c.hasPathOrNull("inputFilePath")) c.getString("inputFilePath")
                else "/test/input/beamville/households.xml.gz",
              inputHouseholdAttributesFilePath =
                if (c.hasPathOrNull("inputHouseholdAttributesFilePath")) c.getString("inputHouseholdAttributesFilePath")
                else "/test/input/beamville/householdAttributes.xml.gz"
            )
          }
        }

        case class ModalBehaviors(
          defaultValueOfTime: scala.Double,
          highTimeSensitivity: BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity,
          lccm: BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.Lccm,
          lowTimeSensitivity: BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity,
          maximumNumberOfReplanningAttempts: scala.Int,
          minimumValueOfTime: scala.Double,
          modeChoiceClass: java.lang.String,
          modeVotMultiplier: BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.ModeVotMultiplier,
          mulitnomialLogit: BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.MulitnomialLogit,
          overrideAutomationForVOTT: scala.Boolean,
          overrideAutomationLevel: scala.Int,
          poolingMultiplier: BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.PoolingMultiplier
        )

        object ModalBehaviors {

          case class HighTimeSensitivity(
            highCongestion: BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.HighCongestion,
            lowCongestion: BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.LowCongestion
          )

          object HighTimeSensitivity {

            case class HighCongestion(
              highwayFactor: BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.HighCongestion.HighwayFactor,
              nonHighwayFactor: BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.HighCongestion.NonHighwayFactor
            )

            object HighCongestion {

              case class HighwayFactor(
                Level3: scala.Double,
                Level4: scala.Double,
                Level5: scala.Double,
                LevelLE2: scala.Double
              )

              object HighwayFactor {

                def apply(
                  c: com.typesafe.config.Config
                ): BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.HighCongestion.HighwayFactor = {
                  BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.HighCongestion.HighwayFactor(
                    Level3 = if (c.hasPathOrNull("Level3")) c.getDouble("Level3") else 1.0,
                    Level4 = if (c.hasPathOrNull("Level4")) c.getDouble("Level4") else 1.0,
                    Level5 = if (c.hasPathOrNull("Level5")) c.getDouble("Level5") else 1.0,
                    LevelLE2 = if (c.hasPathOrNull("LevelLE2")) c.getDouble("LevelLE2") else 1.0
                  )
                }
              }

              case class NonHighwayFactor(
                Level3: scala.Double,
                Level4: scala.Double,
                Level5: scala.Double,
                LevelLE2: scala.Double
              )

              object NonHighwayFactor {

                def apply(
                  c: com.typesafe.config.Config
                ): BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.HighCongestion.NonHighwayFactor = {
                  BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.HighCongestion.NonHighwayFactor(
                    Level3 = if (c.hasPathOrNull("Level3")) c.getDouble("Level3") else 1.0,
                    Level4 = if (c.hasPathOrNull("Level4")) c.getDouble("Level4") else 1.0,
                    Level5 = if (c.hasPathOrNull("Level5")) c.getDouble("Level5") else 1.0,
                    LevelLE2 = if (c.hasPathOrNull("LevelLE2")) c.getDouble("LevelLE2") else 1.0
                  )
                }
              }

              def apply(
                c: com.typesafe.config.Config
              ): BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.HighCongestion = {
                BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.HighCongestion(
                  highwayFactor =
                    BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.HighCongestion.HighwayFactor(
                      if (c.hasPathOrNull("highwayFactor")) c.getConfig("highwayFactor")
                      else com.typesafe.config.ConfigFactory.parseString("highwayFactor{}")
                    ),
                  nonHighwayFactor =
                    BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.HighCongestion.NonHighwayFactor(
                      if (c.hasPathOrNull("nonHighwayFactor")) c.getConfig("nonHighwayFactor")
                      else com.typesafe.config.ConfigFactory.parseString("nonHighwayFactor{}")
                    )
                )
              }
            }

            case class LowCongestion(
              highwayFactor: BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.LowCongestion.HighwayFactor,
              nonHighwayFactor: BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.LowCongestion.NonHighwayFactor
            )

            object LowCongestion {

              case class HighwayFactor(
                Level3: scala.Double,
                Level4: scala.Double,
                Level5: scala.Double,
                LevelLE2: scala.Double
              )

              object HighwayFactor {

                def apply(
                  c: com.typesafe.config.Config
                ): BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.LowCongestion.HighwayFactor = {
                  BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.LowCongestion.HighwayFactor(
                    Level3 = if (c.hasPathOrNull("Level3")) c.getDouble("Level3") else 1.0,
                    Level4 = if (c.hasPathOrNull("Level4")) c.getDouble("Level4") else 1.0,
                    Level5 = if (c.hasPathOrNull("Level5")) c.getDouble("Level5") else 1.0,
                    LevelLE2 = if (c.hasPathOrNull("LevelLE2")) c.getDouble("LevelLE2") else 1.0
                  )
                }
              }

              case class NonHighwayFactor(
                Level3: scala.Double,
                Level4: scala.Double,
                Level5: scala.Double,
                LevelLE2: scala.Double
              )

              object NonHighwayFactor {

                def apply(
                  c: com.typesafe.config.Config
                ): BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.LowCongestion.NonHighwayFactor = {
                  BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.LowCongestion.NonHighwayFactor(
                    Level3 = if (c.hasPathOrNull("Level3")) c.getDouble("Level3") else 1.0,
                    Level4 = if (c.hasPathOrNull("Level4")) c.getDouble("Level4") else 1.0,
                    Level5 = if (c.hasPathOrNull("Level5")) c.getDouble("Level5") else 1.0,
                    LevelLE2 = if (c.hasPathOrNull("LevelLE2")) c.getDouble("LevelLE2") else 1.0
                  )
                }
              }

              def apply(
                c: com.typesafe.config.Config
              ): BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.LowCongestion = {
                BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.LowCongestion(
                  highwayFactor =
                    BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.LowCongestion.HighwayFactor(
                      if (c.hasPathOrNull("highwayFactor")) c.getConfig("highwayFactor")
                      else com.typesafe.config.ConfigFactory.parseString("highwayFactor{}")
                    ),
                  nonHighwayFactor =
                    BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.LowCongestion.NonHighwayFactor(
                      if (c.hasPathOrNull("nonHighwayFactor")) c.getConfig("nonHighwayFactor")
                      else com.typesafe.config.ConfigFactory.parseString("nonHighwayFactor{}")
                    )
                )
              }
            }

            def apply(
              c: com.typesafe.config.Config
            ): BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity = {
              BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity(
                highCongestion = BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.HighCongestion(
                  if (c.hasPathOrNull("highCongestion")) c.getConfig("highCongestion")
                  else com.typesafe.config.ConfigFactory.parseString("highCongestion{}")
                ),
                lowCongestion = BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity.LowCongestion(
                  if (c.hasPathOrNull("lowCongestion")) c.getConfig("lowCongestion")
                  else com.typesafe.config.ConfigFactory.parseString("lowCongestion{}")
                )
              )
            }
          }

          case class Lccm(
            filePath: java.lang.String
          )

          object Lccm {

            def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.Lccm = {
              BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.Lccm(
                filePath =
                  if (c.hasPathOrNull("filePath")) c.getString("filePath") else "/test/input/beamville/lccm-long.csv"
              )
            }
          }

          case class LowTimeSensitivity(
            highCongestion: BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.HighCongestion,
            lowCongestion: BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.LowCongestion
          )

          object LowTimeSensitivity {

            case class HighCongestion(
              highwayFactor: BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.HighCongestion.HighwayFactor,
              nonHighwayFactor: BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.HighCongestion.NonHighwayFactor
            )

            object HighCongestion {

              case class HighwayFactor(
                Level3: scala.Double,
                Level4: scala.Double,
                Level5: scala.Double,
                LevelLE2: scala.Double
              )

              object HighwayFactor {

                def apply(
                  c: com.typesafe.config.Config
                ): BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.HighCongestion.HighwayFactor = {
                  BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.HighCongestion.HighwayFactor(
                    Level3 = if (c.hasPathOrNull("Level3")) c.getDouble("Level3") else 1.0,
                    Level4 = if (c.hasPathOrNull("Level4")) c.getDouble("Level4") else 1.0,
                    Level5 = if (c.hasPathOrNull("Level5")) c.getDouble("Level5") else 1.0,
                    LevelLE2 = if (c.hasPathOrNull("LevelLE2")) c.getDouble("LevelLE2") else 1.0
                  )
                }
              }

              case class NonHighwayFactor(
                Level3: scala.Double,
                Level4: scala.Double,
                Level5: scala.Double,
                LevelLE2: scala.Double
              )

              object NonHighwayFactor {

                def apply(
                  c: com.typesafe.config.Config
                ): BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.HighCongestion.NonHighwayFactor = {
                  BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.HighCongestion.NonHighwayFactor(
                    Level3 = if (c.hasPathOrNull("Level3")) c.getDouble("Level3") else 1.0,
                    Level4 = if (c.hasPathOrNull("Level4")) c.getDouble("Level4") else 1.0,
                    Level5 = if (c.hasPathOrNull("Level5")) c.getDouble("Level5") else 1.0,
                    LevelLE2 = if (c.hasPathOrNull("LevelLE2")) c.getDouble("LevelLE2") else 1.0
                  )
                }
              }

              def apply(
                c: com.typesafe.config.Config
              ): BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.HighCongestion = {
                BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.HighCongestion(
                  highwayFactor =
                    BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.HighCongestion.HighwayFactor(
                      if (c.hasPathOrNull("highwayFactor")) c.getConfig("highwayFactor")
                      else com.typesafe.config.ConfigFactory.parseString("highwayFactor{}")
                    ),
                  nonHighwayFactor =
                    BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.HighCongestion.NonHighwayFactor(
                      if (c.hasPathOrNull("nonHighwayFactor")) c.getConfig("nonHighwayFactor")
                      else com.typesafe.config.ConfigFactory.parseString("nonHighwayFactor{}")
                    )
                )
              }
            }

            case class LowCongestion(
              highwayFactor: BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.LowCongestion.HighwayFactor,
              nonHighwayFactor: BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.LowCongestion.NonHighwayFactor
            )

            object LowCongestion {

              case class HighwayFactor(
                Level3: scala.Double,
                Level4: scala.Double,
                Level5: scala.Double,
                LevelLE2: scala.Double
              )

              object HighwayFactor {

                def apply(
                  c: com.typesafe.config.Config
                ): BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.LowCongestion.HighwayFactor = {
                  BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.LowCongestion.HighwayFactor(
                    Level3 = if (c.hasPathOrNull("Level3")) c.getDouble("Level3") else 1.0,
                    Level4 = if (c.hasPathOrNull("Level4")) c.getDouble("Level4") else 1.0,
                    Level5 = if (c.hasPathOrNull("Level5")) c.getDouble("Level5") else 1.0,
                    LevelLE2 = if (c.hasPathOrNull("LevelLE2")) c.getDouble("LevelLE2") else 1.0
                  )
                }
              }

              case class NonHighwayFactor(
                Level3: scala.Double,
                Level4: scala.Double,
                Level5: scala.Double,
                LevelLE2: scala.Double
              )

              object NonHighwayFactor {

                def apply(
                  c: com.typesafe.config.Config
                ): BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.LowCongestion.NonHighwayFactor = {
                  BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.LowCongestion.NonHighwayFactor(
                    Level3 = if (c.hasPathOrNull("Level3")) c.getDouble("Level3") else 1.0,
                    Level4 = if (c.hasPathOrNull("Level4")) c.getDouble("Level4") else 1.0,
                    Level5 = if (c.hasPathOrNull("Level5")) c.getDouble("Level5") else 1.0,
                    LevelLE2 = if (c.hasPathOrNull("LevelLE2")) c.getDouble("LevelLE2") else 1.0
                  )
                }
              }

              def apply(
                c: com.typesafe.config.Config
              ): BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.LowCongestion = {
                BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.LowCongestion(
                  highwayFactor =
                    BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.LowCongestion.HighwayFactor(
                      if (c.hasPathOrNull("highwayFactor")) c.getConfig("highwayFactor")
                      else com.typesafe.config.ConfigFactory.parseString("highwayFactor{}")
                    ),
                  nonHighwayFactor =
                    BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.LowCongestion.NonHighwayFactor(
                      if (c.hasPathOrNull("nonHighwayFactor")) c.getConfig("nonHighwayFactor")
                      else com.typesafe.config.ConfigFactory.parseString("nonHighwayFactor{}")
                    )
                )
              }
            }

            def apply(
              c: com.typesafe.config.Config
            ): BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity = {
              BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity(
                highCongestion = BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.HighCongestion(
                  if (c.hasPathOrNull("highCongestion")) c.getConfig("highCongestion")
                  else com.typesafe.config.ConfigFactory.parseString("highCongestion{}")
                ),
                lowCongestion = BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity.LowCongestion(
                  if (c.hasPathOrNull("lowCongestion")) c.getConfig("lowCongestion")
                  else com.typesafe.config.ConfigFactory.parseString("lowCongestion{}")
                )
              )
            }
          }

          case class ModeVotMultiplier(
            CAV: scala.Double,
            bike: scala.Double,
            drive: scala.Double,
            rideHail: scala.Double,
            rideHailPooled: scala.Double,
            rideHailTransit: scala.Double,
            transit: scala.Double,
            waiting: scala.Double,
            walk: scala.Double
          )

          object ModeVotMultiplier {

            def apply(
              c: com.typesafe.config.Config
            ): BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.ModeVotMultiplier = {
              BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.ModeVotMultiplier(
                CAV = if (c.hasPathOrNull("CAV")) c.getDouble("CAV") else 1.0,
                bike = if (c.hasPathOrNull("bike")) c.getDouble("bike") else 1.0,
                drive = if (c.hasPathOrNull("drive")) c.getDouble("drive") else 1.0,
                rideHail = if (c.hasPathOrNull("rideHail")) c.getDouble("rideHail") else 1.0,
                rideHailPooled = if (c.hasPathOrNull("rideHailPooled")) c.getDouble("rideHailPooled") else 1.0,
                rideHailTransit = if (c.hasPathOrNull("rideHailTransit")) c.getDouble("rideHailTransit") else 1.0,
                transit = if (c.hasPathOrNull("transit")) c.getDouble("transit") else 1.0,
                waiting = if (c.hasPathOrNull("waiting")) c.getDouble("waiting") else 1.0,
                walk = if (c.hasPathOrNull("walk")) c.getDouble("walk") else 1.0
              )
            }
          }

          case class MulitnomialLogit(
            params: BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.MulitnomialLogit.Params,
            utility_scale_factor: scala.Double
          )

          object MulitnomialLogit {

            case class Params(
              bike_intercept: scala.Double,
              bike_transit_intercept: scala.Double,
              car_intercept: scala.Double,
              cav_intercept: scala.Double,
              drive_transit_intercept: scala.Double,
              ride_hail_intercept: scala.Double,
              ride_hail_pooled_intercept: scala.Double,
              ride_hail_transit_intercept: scala.Double,
              transfer: scala.Double,
              transit_crowding: scala.Double,
              transit_crowding_percentile: scala.Double,
              walk_intercept: scala.Double,
              walk_transit_intercept: scala.Double
            )

            object Params {

              def apply(
                c: com.typesafe.config.Config
              ): BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.MulitnomialLogit.Params = {
                BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.MulitnomialLogit.Params(
                  bike_intercept = if (c.hasPathOrNull("bike_intercept")) c.getDouble("bike_intercept") else 0.0,
                  bike_transit_intercept =
                    if (c.hasPathOrNull("bike_transit_intercept")) c.getDouble("bike_transit_intercept") else 0.0,
                  car_intercept = if (c.hasPathOrNull("car_intercept")) c.getDouble("car_intercept") else 0.0,
                  cav_intercept = if (c.hasPathOrNull("cav_intercept")) c.getDouble("cav_intercept") else 0.0,
                  drive_transit_intercept =
                    if (c.hasPathOrNull("drive_transit_intercept")) c.getDouble("drive_transit_intercept") else 0.0,
                  ride_hail_intercept =
                    if (c.hasPathOrNull("ride_hail_intercept")) c.getDouble("ride_hail_intercept") else 0.0,
                  ride_hail_pooled_intercept =
                    if (c.hasPathOrNull("ride_hail_pooled_intercept")) c.getDouble("ride_hail_pooled_intercept")
                    else 0.0,
                  ride_hail_transit_intercept =
                    if (c.hasPathOrNull("ride_hail_transit_intercept")) c.getDouble("ride_hail_transit_intercept")
                    else 0.0,
                  transfer = if (c.hasPathOrNull("transfer")) c.getDouble("transfer") else -1.4,
                  transit_crowding = if (c.hasPathOrNull("transit_crowding")) c.getDouble("transit_crowding") else 0.0,
                  transit_crowding_percentile =
                    if (c.hasPathOrNull("transit_crowding_percentile")) c.getDouble("transit_crowding_percentile")
                    else 90.0,
                  walk_intercept = if (c.hasPathOrNull("walk_intercept")) c.getDouble("walk_intercept") else 0.0,
                  walk_transit_intercept =
                    if (c.hasPathOrNull("walk_transit_intercept")) c.getDouble("walk_transit_intercept") else 0.0
                )
              }
            }

            def apply(
              c: com.typesafe.config.Config
            ): BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.MulitnomialLogit = {
              BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.MulitnomialLogit(
                params = BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.MulitnomialLogit.Params(
                  if (c.hasPathOrNull("params")) c.getConfig("params")
                  else com.typesafe.config.ConfigFactory.parseString("params{}")
                ),
                utility_scale_factor =
                  if (c.hasPathOrNull("utility_scale_factor")) c.getDouble("utility_scale_factor") else 1.0
              )
            }
          }

          case class PoolingMultiplier(
            Level3: scala.Double,
            Level4: scala.Double,
            Level5: scala.Double,
            LevelLE2: scala.Double
          )

          object PoolingMultiplier {

            def apply(
              c: com.typesafe.config.Config
            ): BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.PoolingMultiplier = {
              BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.PoolingMultiplier(
                Level3 = if (c.hasPathOrNull("Level3")) c.getDouble("Level3") else 1.0,
                Level4 = if (c.hasPathOrNull("Level4")) c.getDouble("Level4") else 1.0,
                Level5 = if (c.hasPathOrNull("Level5")) c.getDouble("Level5") else 1.0,
                LevelLE2 = if (c.hasPathOrNull("LevelLE2")) c.getDouble("LevelLE2") else 1.0
              )
            }
          }

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.ModalBehaviors = {
            BeamConfig.Beam.Agentsim.Agents.ModalBehaviors(
              defaultValueOfTime =
                if (c.hasPathOrNull("defaultValueOfTime")) c.getDouble("defaultValueOfTime") else 8.0,
              highTimeSensitivity = BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.HighTimeSensitivity(
                if (c.hasPathOrNull("highTimeSensitivity")) c.getConfig("highTimeSensitivity")
                else com.typesafe.config.ConfigFactory.parseString("highTimeSensitivity{}")
              ),
              lccm = BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.Lccm(
                if (c.hasPathOrNull("lccm")) c.getConfig("lccm")
                else com.typesafe.config.ConfigFactory.parseString("lccm{}")
              ),
              lowTimeSensitivity = BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.LowTimeSensitivity(
                if (c.hasPathOrNull("lowTimeSensitivity")) c.getConfig("lowTimeSensitivity")
                else com.typesafe.config.ConfigFactory.parseString("lowTimeSensitivity{}")
              ),
              maximumNumberOfReplanningAttempts =
                if (c.hasPathOrNull("maximumNumberOfReplanningAttempts")) c.getInt("maximumNumberOfReplanningAttempts")
                else 3,
              minimumValueOfTime =
                if (c.hasPathOrNull("minimumValueOfTime")) c.getDouble("minimumValueOfTime") else 7.25,
              modeChoiceClass =
                if (c.hasPathOrNull("modeChoiceClass")) c.getString("modeChoiceClass")
                else "ModeChoiceMultinomialLogit",
              modeVotMultiplier = BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.ModeVotMultiplier(
                if (c.hasPathOrNull("modeVotMultiplier")) c.getConfig("modeVotMultiplier")
                else com.typesafe.config.ConfigFactory.parseString("modeVotMultiplier{}")
              ),
              mulitnomialLogit = BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.MulitnomialLogit(
                if (c.hasPathOrNull("mulitnomialLogit")) c.getConfig("mulitnomialLogit")
                else com.typesafe.config.ConfigFactory.parseString("mulitnomialLogit{}")
              ),
              overrideAutomationForVOTT =
                c.hasPathOrNull("overrideAutomationForVOTT") && c.getBoolean("overrideAutomationForVOTT"),
              overrideAutomationLevel =
                if (c.hasPathOrNull("overrideAutomationLevel")) c.getInt("overrideAutomationLevel") else 1,
              poolingMultiplier = BeamConfig.Beam.Agentsim.Agents.ModalBehaviors.PoolingMultiplier(
                if (c.hasPathOrNull("poolingMultiplier")) c.getConfig("poolingMultiplier")
                else com.typesafe.config.ConfigFactory.parseString("poolingMultiplier{}")
              )
            )
          }
        }

        case class ModeIncentive(
          filePath: java.lang.String
        )

        object ModeIncentive {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.ModeIncentive = {
            BeamConfig.Beam.Agentsim.Agents.ModeIncentive(
              filePath = if (c.hasPathOrNull("filePath")) c.getString("filePath") else ""
            )
          }
        }

        case class Parking(
          maxSearchRadius: scala.Double,
          minSearchRadius: scala.Double,
          mulitnomialLogit: BeamConfig.Beam.Agentsim.Agents.Parking.MulitnomialLogit,
          rangeAnxietyBuffer: scala.Double
        )

        object Parking {

          case class MulitnomialLogit(
            params: BeamConfig.Beam.Agentsim.Agents.Parking.MulitnomialLogit.Params
          )

          object MulitnomialLogit {

            case class Params(
              distanceMultiplier: scala.Double,
              homeActivityPrefersResidentialParkingMultiplier: scala.Double,
              parkingPriceMultiplier: scala.Double,
              rangeAnxietyMultiplier: scala.Double
            )

            object Params {

              def apply(
                c: com.typesafe.config.Config
              ): BeamConfig.Beam.Agentsim.Agents.Parking.MulitnomialLogit.Params = {
                BeamConfig.Beam.Agentsim.Agents.Parking.MulitnomialLogit.Params(
                  distanceMultiplier =
                    if (c.hasPathOrNull("distanceMultiplier")) c.getDouble("distanceMultiplier") else -0.086,
                  homeActivityPrefersResidentialParkingMultiplier =
                    if (c.hasPathOrNull("homeActivityPrefersResidentialParkingMultiplier"))
                      c.getDouble("homeActivityPrefersResidentialParkingMultiplier")
                    else 1.0,
                  parkingPriceMultiplier =
                    if (c.hasPathOrNull("parkingPriceMultiplier")) c.getDouble("parkingPriceMultiplier") else -0.005,
                  rangeAnxietyMultiplier =
                    if (c.hasPathOrNull("rangeAnxietyMultiplier")) c.getDouble("rangeAnxietyMultiplier") else -0.5
                )
              }
            }

            def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.Parking.MulitnomialLogit = {
              BeamConfig.Beam.Agentsim.Agents.Parking.MulitnomialLogit(
                params = BeamConfig.Beam.Agentsim.Agents.Parking.MulitnomialLogit.Params(
                  if (c.hasPathOrNull("params")) c.getConfig("params")
                  else com.typesafe.config.ConfigFactory.parseString("params{}")
                )
              )
            }
          }

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.Parking = {
            BeamConfig.Beam.Agentsim.Agents.Parking(
              maxSearchRadius = if (c.hasPathOrNull("maxSearchRadius")) c.getDouble("maxSearchRadius") else 8046.72,
              minSearchRadius = if (c.hasPathOrNull("minSearchRadius")) c.getDouble("minSearchRadius") else 250.00,
              mulitnomialLogit = BeamConfig.Beam.Agentsim.Agents.Parking.MulitnomialLogit(
                if (c.hasPathOrNull("mulitnomialLogit")) c.getConfig("mulitnomialLogit")
                else com.typesafe.config.ConfigFactory.parseString("mulitnomialLogit{}")
              ),
              rangeAnxietyBuffer =
                if (c.hasPathOrNull("rangeAnxietyBuffer")) c.getDouble("rangeAnxietyBuffer") else 20000.0
            )
          }
        }

        case class Plans(
          inputPersonAttributesFilePath: java.lang.String,
          inputPlansFilePath: java.lang.String,
          merge: BeamConfig.Beam.Agentsim.Agents.Plans.Merge
        )

        object Plans {

          case class Merge(
            fraction: scala.Double
          )

          object Merge {

            def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.Plans.Merge = {
              BeamConfig.Beam.Agentsim.Agents.Plans.Merge(
                fraction = if (c.hasPathOrNull("fraction")) c.getDouble("fraction") else 0.0
              )
            }
          }

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.Plans = {
            BeamConfig.Beam.Agentsim.Agents.Plans(
              inputPersonAttributesFilePath =
                if (c.hasPathOrNull("inputPersonAttributesFilePath")) c.getString("inputPersonAttributesFilePath")
                else "/test/input/beamville/populationAttributes.xml.gz",
              inputPlansFilePath =
                if (c.hasPathOrNull("inputPlansFilePath")) c.getString("inputPlansFilePath")
                else "/test/input/beamville/population.xml.gz",
              merge = BeamConfig.Beam.Agentsim.Agents.Plans.Merge(
                if (c.hasPathOrNull("merge")) c.getConfig("merge")
                else com.typesafe.config.ConfigFactory.parseString("merge{}")
              )
            )
          }
        }

        case class Population(
          useVehicleSampling: scala.Boolean
        )

        object Population {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.Population = {
            BeamConfig.Beam.Agentsim.Agents.Population(
              useVehicleSampling = c.hasPathOrNull("useVehicleSampling") && c.getBoolean("useVehicleSampling")
            )
          }
        }

        case class PtFare(
          filePath: java.lang.String
        )

        object PtFare {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.PtFare = {
            BeamConfig.Beam.Agentsim.Agents.PtFare(
              filePath = if (c.hasPathOrNull("filePath")) c.getString("filePath") else ""
            )
          }
        }

        case class RideHail(
          allocationManager: BeamConfig.Beam.Agentsim.Agents.RideHail.AllocationManager,
          cav: BeamConfig.Beam.Agentsim.Agents.RideHail.Cav,
          charging: BeamConfig.Beam.Agentsim.Agents.RideHail.Charging,
          defaultBaseCost: scala.Double,
          defaultCostPerMile: scala.Double,
          defaultCostPerMinute: scala.Double,
          human: BeamConfig.Beam.Agentsim.Agents.RideHail.Human,
          initialization: BeamConfig.Beam.Agentsim.Agents.RideHail.Initialization,
          iterationStats: BeamConfig.Beam.Agentsim.Agents.RideHail.IterationStats,
          linkFleetStateAcrossIterations: scala.Boolean,
          name: java.lang.String,
          pooledBaseCost: scala.Double,
          pooledCostPerMile: scala.Double,
          pooledCostPerMinute: scala.Double,
          pooledToRegularRideCostRatio: scala.Double,
          rangeBufferForDispatchInMeters: scala.Int,
          refuelLocationType: java.lang.String,
          refuelThresholdInMeters: scala.Double,
          repositioningManager: BeamConfig.Beam.Agentsim.Agents.RideHail.RepositioningManager,
          rideHailManager: BeamConfig.Beam.Agentsim.Agents.RideHail.RideHailManager,
          surgePricing: BeamConfig.Beam.Agentsim.Agents.RideHail.SurgePricing
        )

        object RideHail {

          case class AllocationManager(
            alonsoMora: BeamConfig.Beam.Agentsim.Agents.RideHail.AllocationManager.AlonsoMora,
            matchingAlgorithm: java.lang.String,
            maxExcessRideTime: scala.Double,
            maxWaitingTimeInSec: scala.Int,
            name: java.lang.String,
            pooledRideHailIntervalAsMultipleOfSoloRideHail: scala.Int,
            repositionLowWaitingTimes: BeamConfig.Beam.Agentsim.Agents.RideHail.AllocationManager.RepositionLowWaitingTimes,
            requestBufferTimeoutInSeconds: scala.Int
          )

          object AllocationManager {

            case class AlonsoMora(
              maxRequestsPerVehicle: scala.Int
            )

            object AlonsoMora {

              def apply(
                c: com.typesafe.config.Config
              ): BeamConfig.Beam.Agentsim.Agents.RideHail.AllocationManager.AlonsoMora = {
                BeamConfig.Beam.Agentsim.Agents.RideHail.AllocationManager.AlonsoMora(
                  maxRequestsPerVehicle =
                    if (c.hasPathOrNull("maxRequestsPerVehicle")) c.getInt("maxRequestsPerVehicle") else 5
                )
              }
            }

            case class RepositionLowWaitingTimes(
              allowIncreasingRadiusIfDemandInRadiusLow: scala.Boolean,
              demandWeight: scala.Double,
              distanceWeight: scala.Double,
              keepMaxTopNScores: scala.Int,
              minDemandPercentageInRadius: scala.Double,
              minScoreThresholdForRepositioning: scala.Double,
              minimumNumberOfIdlingVehiclesThresholdForRepositioning: scala.Int,
              percentageOfVehiclesToReposition: scala.Double,
              produceDebugImages: scala.Boolean,
              repositionCircleRadiusInMeters: scala.Double,
              repositioningMethod: java.lang.String,
              timeWindowSizeInSecForDecidingAboutRepositioning: scala.Double,
              waitingTimeWeight: scala.Double
            )

            object RepositionLowWaitingTimes {

              def apply(
                c: com.typesafe.config.Config
              ): BeamConfig.Beam.Agentsim.Agents.RideHail.AllocationManager.RepositionLowWaitingTimes = {
                BeamConfig.Beam.Agentsim.Agents.RideHail.AllocationManager.RepositionLowWaitingTimes(
                  allowIncreasingRadiusIfDemandInRadiusLow = !c.hasPathOrNull(
                    "allowIncreasingRadiusIfDemandInRadiusLow"
                  ) || c.getBoolean("allowIncreasingRadiusIfDemandInRadiusLow"),
                  demandWeight = if (c.hasPathOrNull("demandWeight")) c.getDouble("demandWeight") else 4.0,
                  distanceWeight = if (c.hasPathOrNull("distanceWeight")) c.getDouble("distanceWeight") else 0.01,
                  keepMaxTopNScores = if (c.hasPathOrNull("keepMaxTopNScores")) c.getInt("keepMaxTopNScores") else 1,
                  minDemandPercentageInRadius =
                    if (c.hasPathOrNull("minDemandPercentageInRadius")) c.getDouble("minDemandPercentageInRadius")
                    else 0.1,
                  minScoreThresholdForRepositioning =
                    if (c.hasPathOrNull("minScoreThresholdForRepositioning"))
                      c.getDouble("minScoreThresholdForRepositioning")
                    else 0.1,
                  minimumNumberOfIdlingVehiclesThresholdForRepositioning =
                    if (c.hasPathOrNull("minimumNumberOfIdlingVehiclesThresholdForRepositioning"))
                      c.getInt("minimumNumberOfIdlingVehiclesThresholdForRepositioning")
                    else 1,
                  percentageOfVehiclesToReposition =
                    if (c.hasPathOrNull("percentageOfVehiclesToReposition"))
                      c.getDouble("percentageOfVehiclesToReposition")
                    else 0.01,
                  produceDebugImages = !c.hasPathOrNull("produceDebugImages") || c.getBoolean("produceDebugImages"),
                  repositionCircleRadiusInMeters =
                    if (c.hasPathOrNull("repositionCircleRadiusInMeters")) c.getDouble("repositionCircleRadiusInMeters")
                    else 3000,
                  repositioningMethod =
                    if (c.hasPathOrNull("repositioningMethod")) c.getString("repositioningMethod") else "TOP_SCORES",
                  timeWindowSizeInSecForDecidingAboutRepositioning =
                    if (c.hasPathOrNull("timeWindowSizeInSecForDecidingAboutRepositioning"))
                      c.getDouble("timeWindowSizeInSecForDecidingAboutRepositioning")
                    else 1200,
                  waitingTimeWeight =
                    if (c.hasPathOrNull("waitingTimeWeight")) c.getDouble("waitingTimeWeight") else 4.0
                )
              }
            }

            def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.RideHail.AllocationManager = {
              BeamConfig.Beam.Agentsim.Agents.RideHail.AllocationManager(
                alonsoMora = BeamConfig.Beam.Agentsim.Agents.RideHail.AllocationManager.AlonsoMora(
                  if (c.hasPathOrNull("alonsoMora")) c.getConfig("alonsoMora")
                  else com.typesafe.config.ConfigFactory.parseString("alonsoMora{}")
                ),
                matchingAlgorithm =
                  if (c.hasPathOrNull("matchingAlgorithm")) c.getString("matchingAlgorithm")
                  else "ALONSO_MORA_MATCHING_WITH_ASYNC_GREEDY_ASSIGNMENT",
                maxExcessRideTime = if (c.hasPathOrNull("maxExcessRideTime")) c.getDouble("maxExcessRideTime") else 0.5,
                maxWaitingTimeInSec =
                  if (c.hasPathOrNull("maxWaitingTimeInSec")) c.getInt("maxWaitingTimeInSec") else 900,
                name = if (c.hasPathOrNull("name")) c.getString("name") else "DEFAULT_MANAGER",
                pooledRideHailIntervalAsMultipleOfSoloRideHail =
                  if (c.hasPathOrNull("pooledRideHailIntervalAsMultipleOfSoloRideHail"))
                    c.getInt("pooledRideHailIntervalAsMultipleOfSoloRideHail")
                  else 1,
                repositionLowWaitingTimes =
                  BeamConfig.Beam.Agentsim.Agents.RideHail.AllocationManager.RepositionLowWaitingTimes(
                    if (c.hasPathOrNull("repositionLowWaitingTimes")) c.getConfig("repositionLowWaitingTimes")
                    else com.typesafe.config.ConfigFactory.parseString("repositionLowWaitingTimes{}")
                  ),
                requestBufferTimeoutInSeconds =
                  if (c.hasPathOrNull("requestBufferTimeoutInSeconds")) c.getInt("requestBufferTimeoutInSeconds") else 0
              )
            }
          }

          case class Cav(
            noRefuelThresholdInMeters: scala.Int,
            refuelRequiredThresholdInMeters: scala.Int,
            valueOfTime: scala.Int
          )

          object Cav {

            def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.RideHail.Cav = {
              BeamConfig.Beam.Agentsim.Agents.RideHail.Cav(
                noRefuelThresholdInMeters =
                  if (c.hasPathOrNull("noRefuelThresholdInMeters")) c.getInt("noRefuelThresholdInMeters") else 96540,
                refuelRequiredThresholdInMeters =
                  if (c.hasPathOrNull("refuelRequiredThresholdInMeters")) c.getInt("refuelRequiredThresholdInMeters")
                  else 16090,
                valueOfTime = if (c.hasPathOrNull("valueOfTime")) c.getInt("valueOfTime") else 1
              )
            }
          }

          case class Charging(
            vehicleChargingManager: BeamConfig.Beam.Agentsim.Agents.RideHail.Charging.VehicleChargingManager
          )

          object Charging {

            case class VehicleChargingManager(
              defaultVehicleChargingManager: BeamConfig.Beam.Agentsim.Agents.RideHail.Charging.VehicleChargingManager.DefaultVehicleChargingManager,
              name: java.lang.String
            )

            object VehicleChargingManager {

              case class DefaultVehicleChargingManager(
                fractionAvailableThresholdToFavorFasterCharging: scala.Double,
                mulitnomialLogit: BeamConfig.Beam.Agentsim.Agents.RideHail.Charging.VehicleChargingManager.DefaultVehicleChargingManager.MulitnomialLogit,
                noChargingThresholdExpirationTimeInS: scala.Int
              )

              object DefaultVehicleChargingManager {

                case class MulitnomialLogit(
                  params: BeamConfig.Beam.Agentsim.Agents.RideHail.Charging.VehicleChargingManager.DefaultVehicleChargingManager.MulitnomialLogit.Params
                )

                object MulitnomialLogit {

                  case class Params(
                    chargingTimeMultiplier: scala.Double,
                    drivingTimeMultiplier: scala.Double,
                    insufficientRangeMultiplier: scala.Double,
                    queueingTimeMultiplier: scala.Double
                  )

                  object Params {

                    def apply(
                      c: com.typesafe.config.Config
                    ): BeamConfig.Beam.Agentsim.Agents.RideHail.Charging.VehicleChargingManager.DefaultVehicleChargingManager.MulitnomialLogit.Params = {
                      BeamConfig.Beam.Agentsim.Agents.RideHail.Charging.VehicleChargingManager.DefaultVehicleChargingManager.MulitnomialLogit
                        .Params(
                          chargingTimeMultiplier =
                            if (c.hasPathOrNull("chargingTimeMultiplier")) c.getDouble("chargingTimeMultiplier")
                            else -0.01666667,
                          drivingTimeMultiplier =
                            if (c.hasPathOrNull("drivingTimeMultiplier")) c.getDouble("drivingTimeMultiplier")
                            else -0.01666667,
                          insufficientRangeMultiplier =
                            if (c.hasPathOrNull("insufficientRangeMultiplier"))
                              c.getDouble("insufficientRangeMultiplier")
                            else -60.0,
                          queueingTimeMultiplier =
                            if (c.hasPathOrNull("queueingTimeMultiplier")) c.getDouble("queueingTimeMultiplier")
                            else -0.01666667
                        )
                    }
                  }

                  def apply(
                    c: com.typesafe.config.Config
                  ): BeamConfig.Beam.Agentsim.Agents.RideHail.Charging.VehicleChargingManager.DefaultVehicleChargingManager.MulitnomialLogit = {
                    BeamConfig.Beam.Agentsim.Agents.RideHail.Charging.VehicleChargingManager.DefaultVehicleChargingManager
                      .MulitnomialLogit(
                        params =
                          BeamConfig.Beam.Agentsim.Agents.RideHail.Charging.VehicleChargingManager.DefaultVehicleChargingManager.MulitnomialLogit
                            .Params(
                              if (c.hasPathOrNull("params")) c.getConfig("params")
                              else com.typesafe.config.ConfigFactory.parseString("params{}")
                            )
                      )
                  }
                }

                def apply(
                  c: com.typesafe.config.Config
                ): BeamConfig.Beam.Agentsim.Agents.RideHail.Charging.VehicleChargingManager.DefaultVehicleChargingManager = {
                  BeamConfig.Beam.Agentsim.Agents.RideHail.Charging.VehicleChargingManager
                    .DefaultVehicleChargingManager(
                      fractionAvailableThresholdToFavorFasterCharging =
                        if (c.hasPathOrNull("fractionAvailableThresholdToFavorFasterCharging"))
                          c.getDouble("fractionAvailableThresholdToFavorFasterCharging")
                        else 1.01,
                      mulitnomialLogit =
                        BeamConfig.Beam.Agentsim.Agents.RideHail.Charging.VehicleChargingManager.DefaultVehicleChargingManager
                          .MulitnomialLogit(
                            if (c.hasPathOrNull("mulitnomialLogit")) c.getConfig("mulitnomialLogit")
                            else com.typesafe.config.ConfigFactory.parseString("mulitnomialLogit{}")
                          ),
                      noChargingThresholdExpirationTimeInS =
                        if (c.hasPathOrNull("noChargingThresholdExpirationTimeInS"))
                          c.getInt("noChargingThresholdExpirationTimeInS")
                        else 0
                    )
                }
              }

              def apply(
                c: com.typesafe.config.Config
              ): BeamConfig.Beam.Agentsim.Agents.RideHail.Charging.VehicleChargingManager = {
                BeamConfig.Beam.Agentsim.Agents.RideHail.Charging.VehicleChargingManager(
                  defaultVehicleChargingManager =
                    BeamConfig.Beam.Agentsim.Agents.RideHail.Charging.VehicleChargingManager
                      .DefaultVehicleChargingManager(
                        if (c.hasPathOrNull("defaultVehicleChargingManager"))
                          c.getConfig("defaultVehicleChargingManager")
                        else com.typesafe.config.ConfigFactory.parseString("defaultVehicleChargingManager{}")
                      ),
                  name = if (c.hasPathOrNull("name")) c.getString("name") else "DefaultVehicleChargingManager"
                )
              }
            }

            def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.RideHail.Charging = {
              BeamConfig.Beam.Agentsim.Agents.RideHail.Charging(
                vehicleChargingManager = BeamConfig.Beam.Agentsim.Agents.RideHail.Charging.VehicleChargingManager(
                  if (c.hasPathOrNull("vehicleChargingManager")) c.getConfig("vehicleChargingManager")
                  else com.typesafe.config.ConfigFactory.parseString("vehicleChargingManager{}")
                )
              )
            }
          }

          case class Human(
            noRefuelThresholdInMeters: scala.Int,
            refuelRequiredThresholdInMeters: scala.Int,
            valueOfTime: scala.Double
          )

          object Human {

            def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.RideHail.Human = {
              BeamConfig.Beam.Agentsim.Agents.RideHail.Human(
                noRefuelThresholdInMeters =
                  if (c.hasPathOrNull("noRefuelThresholdInMeters")) c.getInt("noRefuelThresholdInMeters") else 128720,
                refuelRequiredThresholdInMeters =
                  if (c.hasPathOrNull("refuelRequiredThresholdInMeters")) c.getInt("refuelRequiredThresholdInMeters")
                  else 32180,
                valueOfTime = if (c.hasPathOrNull("valueOfTime")) c.getDouble("valueOfTime") else 22.9
              )
            }
          }

          case class Initialization(
            filePath: java.lang.String,
            initType: java.lang.String,
            parking: BeamConfig.Beam.Agentsim.Agents.RideHail.Initialization.Parking,
            procedural: BeamConfig.Beam.Agentsim.Agents.RideHail.Initialization.Procedural
          )

          object Initialization {

            case class Parking(
              filePath: java.lang.String
            )

            object Parking {

              def apply(
                c: com.typesafe.config.Config
              ): BeamConfig.Beam.Agentsim.Agents.RideHail.Initialization.Parking = {
                BeamConfig.Beam.Agentsim.Agents.RideHail.Initialization.Parking(
                  filePath = if (c.hasPathOrNull("filePath")) c.getString("filePath") else ""
                )
              }
            }

            case class Procedural(
              fractionOfInitialVehicleFleet: scala.Double,
              initialLocation: BeamConfig.Beam.Agentsim.Agents.RideHail.Initialization.Procedural.InitialLocation,
              vehicleTypeId: java.lang.String,
              vehicleTypePrefix: java.lang.String
            )

            object Procedural {

              case class InitialLocation(
                home: BeamConfig.Beam.Agentsim.Agents.RideHail.Initialization.Procedural.InitialLocation.Home,
                name: java.lang.String
              )

              object InitialLocation {

                case class Home(
                  radiusInMeters: scala.Double
                )

                object Home {

                  def apply(
                    c: com.typesafe.config.Config
                  ): BeamConfig.Beam.Agentsim.Agents.RideHail.Initialization.Procedural.InitialLocation.Home = {
                    BeamConfig.Beam.Agentsim.Agents.RideHail.Initialization.Procedural.InitialLocation.Home(
                      radiusInMeters = if (c.hasPathOrNull("radiusInMeters")) c.getDouble("radiusInMeters") else 10000
                    )
                  }
                }

                def apply(
                  c: com.typesafe.config.Config
                ): BeamConfig.Beam.Agentsim.Agents.RideHail.Initialization.Procedural.InitialLocation = {
                  BeamConfig.Beam.Agentsim.Agents.RideHail.Initialization.Procedural.InitialLocation(
                    home = BeamConfig.Beam.Agentsim.Agents.RideHail.Initialization.Procedural.InitialLocation.Home(
                      if (c.hasPathOrNull("home")) c.getConfig("home")
                      else com.typesafe.config.ConfigFactory.parseString("home{}")
                    ),
                    name = if (c.hasPathOrNull("name")) c.getString("name") else "HOME"
                  )
                }
              }

              def apply(
                c: com.typesafe.config.Config
              ): BeamConfig.Beam.Agentsim.Agents.RideHail.Initialization.Procedural = {
                BeamConfig.Beam.Agentsim.Agents.RideHail.Initialization.Procedural(
                  fractionOfInitialVehicleFleet =
                    if (c.hasPathOrNull("fractionOfInitialVehicleFleet")) c.getDouble("fractionOfInitialVehicleFleet")
                    else 0.1,
                  initialLocation = BeamConfig.Beam.Agentsim.Agents.RideHail.Initialization.Procedural.InitialLocation(
                    if (c.hasPathOrNull("initialLocation")) c.getConfig("initialLocation")
                    else com.typesafe.config.ConfigFactory.parseString("initialLocation{}")
                  ),
                  vehicleTypeId = if (c.hasPathOrNull("vehicleTypeId")) c.getString("vehicleTypeId") else "Car",
                  vehicleTypePrefix =
                    if (c.hasPathOrNull("vehicleTypePrefix")) c.getString("vehicleTypePrefix") else "RH"
                )
              }
            }

            def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.RideHail.Initialization = {
              BeamConfig.Beam.Agentsim.Agents.RideHail.Initialization(
                filePath = if (c.hasPathOrNull("filePath")) c.getString("filePath") else "",
                initType = if (c.hasPathOrNull("initType")) c.getString("initType") else "PROCEDURAL",
                parking = BeamConfig.Beam.Agentsim.Agents.RideHail.Initialization.Parking(
                  if (c.hasPathOrNull("parking")) c.getConfig("parking")
                  else com.typesafe.config.ConfigFactory.parseString("parking{}")
                ),
                procedural = BeamConfig.Beam.Agentsim.Agents.RideHail.Initialization.Procedural(
                  if (c.hasPathOrNull("procedural")) c.getConfig("procedural")
                  else com.typesafe.config.ConfigFactory.parseString("procedural{}")
                )
              )
            }
          }

          case class IterationStats(
            timeBinSizeInSec: scala.Double
          )

          object IterationStats {

            def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.RideHail.IterationStats = {
              BeamConfig.Beam.Agentsim.Agents.RideHail.IterationStats(
                timeBinSizeInSec = if (c.hasPathOrNull("timeBinSizeInSec")) c.getDouble("timeBinSizeInSec") else 3600.0
              )
            }
          }

          case class RepositioningManager(
            demandFollowingRepositioningManager: BeamConfig.Beam.Agentsim.Agents.RideHail.RepositioningManager.DemandFollowingRepositioningManager,
            inverseSquareDistanceRepositioningFactor: BeamConfig.Beam.Agentsim.Agents.RideHail.RepositioningManager.InverseSquareDistanceRepositioningFactor,
            name: java.lang.String,
            timeout: scala.Int
          )

          object RepositioningManager {

            case class DemandFollowingRepositioningManager(
              fractionOfClosestClustersToConsider: scala.Double,
              horizon: scala.Int,
              numberOfClustersForDemand: scala.Int,
              sensitivityOfRepositioningToDemand: scala.Double,
              sensitivityOfRepositioningToDemandForCAVs: scala.Double
            )

            object DemandFollowingRepositioningManager {

              def apply(
                c: com.typesafe.config.Config
              ): BeamConfig.Beam.Agentsim.Agents.RideHail.RepositioningManager.DemandFollowingRepositioningManager = {
                BeamConfig.Beam.Agentsim.Agents.RideHail.RepositioningManager.DemandFollowingRepositioningManager(
                  fractionOfClosestClustersToConsider =
                    if (c.hasPathOrNull("fractionOfClosestClustersToConsider"))
                      c.getDouble("fractionOfClosestClustersToConsider")
                    else 0.2,
                  horizon = if (c.hasPathOrNull("horizon")) c.getInt("horizon") else 1200,
                  numberOfClustersForDemand =
                    if (c.hasPathOrNull("numberOfClustersForDemand")) c.getInt("numberOfClustersForDemand") else 30,
                  sensitivityOfRepositioningToDemand =
                    if (c.hasPathOrNull("sensitivityOfRepositioningToDemand"))
                      c.getDouble("sensitivityOfRepositioningToDemand")
                    else 1,
                  sensitivityOfRepositioningToDemandForCAVs =
                    if (c.hasPathOrNull("sensitivityOfRepositioningToDemandForCAVs"))
                      c.getDouble("sensitivityOfRepositioningToDemandForCAVs")
                    else 1
                )
              }
            }

            case class InverseSquareDistanceRepositioningFactor(
              predictionHorizon: scala.Int,
              sensitivityOfRepositioningToDemand: scala.Double,
              sensitivityOfRepositioningToDistance: scala.Double
            )

            object InverseSquareDistanceRepositioningFactor {

              def apply(
                c: com.typesafe.config.Config
              ): BeamConfig.Beam.Agentsim.Agents.RideHail.RepositioningManager.InverseSquareDistanceRepositioningFactor = {
                BeamConfig.Beam.Agentsim.Agents.RideHail.RepositioningManager.InverseSquareDistanceRepositioningFactor(
                  predictionHorizon = if (c.hasPathOrNull("predictionHorizon")) c.getInt("predictionHorizon") else 3600,
                  sensitivityOfRepositioningToDemand =
                    if (c.hasPathOrNull("sensitivityOfRepositioningToDemand"))
                      c.getDouble("sensitivityOfRepositioningToDemand")
                    else 0.4,
                  sensitivityOfRepositioningToDistance =
                    if (c.hasPathOrNull("sensitivityOfRepositioningToDistance"))
                      c.getDouble("sensitivityOfRepositioningToDistance")
                    else 0.9
                )
              }
            }

            def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.RideHail.RepositioningManager = {
              BeamConfig.Beam.Agentsim.Agents.RideHail.RepositioningManager(
                demandFollowingRepositioningManager =
                  BeamConfig.Beam.Agentsim.Agents.RideHail.RepositioningManager.DemandFollowingRepositioningManager(
                    if (c.hasPathOrNull("demandFollowingRepositioningManager"))
                      c.getConfig("demandFollowingRepositioningManager")
                    else com.typesafe.config.ConfigFactory.parseString("demandFollowingRepositioningManager{}")
                  ),
                inverseSquareDistanceRepositioningFactor = BeamConfig.Beam.Agentsim.Agents.RideHail.RepositioningManager
                  .InverseSquareDistanceRepositioningFactor(
                    if (c.hasPathOrNull("inverseSquareDistanceRepositioningFactor"))
                      c.getConfig("inverseSquareDistanceRepositioningFactor")
                    else com.typesafe.config.ConfigFactory.parseString("inverseSquareDistanceRepositioningFactor{}")
                  ),
                name = if (c.hasPathOrNull("name")) c.getString("name") else "DEFAULT_REPOSITIONING_MANAGER",
                timeout = if (c.hasPathOrNull("timeout")) c.getInt("timeout") else 0
              )
            }
          }

          case class RideHailManager(
            radiusInMeters: scala.Double
          )

          object RideHailManager {

            def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.RideHail.RideHailManager = {
              BeamConfig.Beam.Agentsim.Agents.RideHail.RideHailManager(
                radiusInMeters = if (c.hasPathOrNull("radiusInMeters")) c.getDouble("radiusInMeters") else 5000
              )
            }
          }

          case class SurgePricing(
            minimumSurgeLevel: scala.Double,
            numberOfCategories: scala.Int,
            priceAdjustmentStrategy: java.lang.String,
            surgeLevelAdaptionStep: scala.Double
          )

          object SurgePricing {

            def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.RideHail.SurgePricing = {
              BeamConfig.Beam.Agentsim.Agents.RideHail.SurgePricing(
                minimumSurgeLevel = if (c.hasPathOrNull("minimumSurgeLevel")) c.getDouble("minimumSurgeLevel") else 0.1,
                numberOfCategories = if (c.hasPathOrNull("numberOfCategories")) c.getInt("numberOfCategories") else 6,
                priceAdjustmentStrategy =
                  if (c.hasPathOrNull("priceAdjustmentStrategy")) c.getString("priceAdjustmentStrategy")
                  else "KEEP_PRICE_LEVEL_FIXED_AT_ONE",
                surgeLevelAdaptionStep =
                  if (c.hasPathOrNull("surgeLevelAdaptionStep")) c.getDouble("surgeLevelAdaptionStep") else 0.1
              )
            }
          }

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.RideHail = {
            BeamConfig.Beam.Agentsim.Agents.RideHail(
              allocationManager = BeamConfig.Beam.Agentsim.Agents.RideHail.AllocationManager(
                if (c.hasPathOrNull("allocationManager")) c.getConfig("allocationManager")
                else com.typesafe.config.ConfigFactory.parseString("allocationManager{}")
              ),
              cav = BeamConfig.Beam.Agentsim.Agents.RideHail.Cav(
                if (c.hasPathOrNull("cav")) c.getConfig("cav")
                else com.typesafe.config.ConfigFactory.parseString("cav{}")
              ),
              charging = BeamConfig.Beam.Agentsim.Agents.RideHail.Charging(
                if (c.hasPathOrNull("charging")) c.getConfig("charging")
                else com.typesafe.config.ConfigFactory.parseString("charging{}")
              ),
              defaultBaseCost = if (c.hasPathOrNull("defaultBaseCost")) c.getDouble("defaultBaseCost") else 1.8,
              defaultCostPerMile =
                if (c.hasPathOrNull("defaultCostPerMile")) c.getDouble("defaultCostPerMile") else 0.91,
              defaultCostPerMinute =
                if (c.hasPathOrNull("defaultCostPerMinute")) c.getDouble("defaultCostPerMinute") else 0.28,
              human = BeamConfig.Beam.Agentsim.Agents.RideHail.Human(
                if (c.hasPathOrNull("human")) c.getConfig("human")
                else com.typesafe.config.ConfigFactory.parseString("human{}")
              ),
              initialization = BeamConfig.Beam.Agentsim.Agents.RideHail.Initialization(
                if (c.hasPathOrNull("initialization")) c.getConfig("initialization")
                else com.typesafe.config.ConfigFactory.parseString("initialization{}")
              ),
              iterationStats = BeamConfig.Beam.Agentsim.Agents.RideHail.IterationStats(
                if (c.hasPathOrNull("iterationStats")) c.getConfig("iterationStats")
                else com.typesafe.config.ConfigFactory.parseString("iterationStats{}")
              ),
              linkFleetStateAcrossIterations =
                c.hasPathOrNull("linkFleetStateAcrossIterations") && c.getBoolean("linkFleetStateAcrossIterations"),
              name = if (c.hasPathOrNull("name")) c.getString("name") else "RideHail",
              pooledBaseCost = if (c.hasPathOrNull("pooledBaseCost")) c.getDouble("pooledBaseCost") else 1.89,
              pooledCostPerMile = if (c.hasPathOrNull("pooledCostPerMile")) c.getDouble("pooledCostPerMile") else 1.11,
              pooledCostPerMinute =
                if (c.hasPathOrNull("pooledCostPerMinute")) c.getDouble("pooledCostPerMinute") else 0.07,
              pooledToRegularRideCostRatio =
                if (c.hasPathOrNull("pooledToRegularRideCostRatio")) c.getDouble("pooledToRegularRideCostRatio")
                else 0.6,
              rangeBufferForDispatchInMeters =
                if (c.hasPathOrNull("rangeBufferForDispatchInMeters")) c.getInt("rangeBufferForDispatchInMeters")
                else 10000,
              refuelLocationType =
                if (c.hasPathOrNull("refuelLocationType")) c.getString("refuelLocationType") else "AtTAZCenter",
              refuelThresholdInMeters =
                if (c.hasPathOrNull("refuelThresholdInMeters")) c.getDouble("refuelThresholdInMeters") else 5000.0,
              repositioningManager = BeamConfig.Beam.Agentsim.Agents.RideHail.RepositioningManager(
                if (c.hasPathOrNull("repositioningManager")) c.getConfig("repositioningManager")
                else com.typesafe.config.ConfigFactory.parseString("repositioningManager{}")
              ),
              rideHailManager = BeamConfig.Beam.Agentsim.Agents.RideHail.RideHailManager(
                if (c.hasPathOrNull("rideHailManager")) c.getConfig("rideHailManager")
                else com.typesafe.config.ConfigFactory.parseString("rideHailManager{}")
              ),
              surgePricing = BeamConfig.Beam.Agentsim.Agents.RideHail.SurgePricing(
                if (c.hasPathOrNull("surgePricing")) c.getConfig("surgePricing")
                else com.typesafe.config.ConfigFactory.parseString("surgePricing{}")
              )
            )
          }
        }

        case class RideHailTransit(
          modesToConsider: java.lang.String
        )

        object RideHailTransit {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.RideHailTransit = {
            BeamConfig.Beam.Agentsim.Agents.RideHailTransit(
              modesToConsider = if (c.hasPathOrNull("modesToConsider")) c.getString("modesToConsider") else "MASS"
            )
          }
        }

        case class TripBehaviors(
          mulitnomialLogit: BeamConfig.Beam.Agentsim.Agents.TripBehaviors.MulitnomialLogit
        )

        object TripBehaviors {

          case class MulitnomialLogit(
            activity_file_path: java.lang.String,
            additional_trip_utility: scala.Double,
            destination_nest_scale_factor: scala.Double,
            generate_secondary_activities: scala.Boolean,
            intercept_file_path: java.lang.String,
            max_destination_choice_set_size: scala.Int,
            max_destination_distance_meters: scala.Double,
            mode_nest_scale_factor: scala.Double,
            trip_nest_scale_factor: scala.Double
          )

          object MulitnomialLogit {

            def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.TripBehaviors.MulitnomialLogit = {
              BeamConfig.Beam.Agentsim.Agents.TripBehaviors.MulitnomialLogit(
                activity_file_path =
                  if (c.hasPathOrNull("activity_file_path")) c.getString("activity_file_path") else "",
                additional_trip_utility =
                  if (c.hasPathOrNull("additional_trip_utility")) c.getDouble("additional_trip_utility") else 0.0,
                destination_nest_scale_factor =
                  if (c.hasPathOrNull("destination_nest_scale_factor")) c.getDouble("destination_nest_scale_factor")
                  else 1.0,
                generate_secondary_activities =
                  c.hasPathOrNull("generate_secondary_activities") && c.getBoolean("generate_secondary_activities"),
                intercept_file_path =
                  if (c.hasPathOrNull("intercept_file_path")) c.getString("intercept_file_path") else "",
                max_destination_choice_set_size =
                  if (c.hasPathOrNull("max_destination_choice_set_size")) c.getInt("max_destination_choice_set_size")
                  else 20,
                max_destination_distance_meters =
                  if (c.hasPathOrNull("max_destination_distance_meters")) c.getDouble("max_destination_distance_meters")
                  else 32000,
                mode_nest_scale_factor =
                  if (c.hasPathOrNull("mode_nest_scale_factor")) c.getDouble("mode_nest_scale_factor") else 1.0,
                trip_nest_scale_factor =
                  if (c.hasPathOrNull("trip_nest_scale_factor")) c.getDouble("trip_nest_scale_factor") else 1.0
              )
            }
          }

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.TripBehaviors = {
            BeamConfig.Beam.Agentsim.Agents.TripBehaviors(
              mulitnomialLogit = BeamConfig.Beam.Agentsim.Agents.TripBehaviors.MulitnomialLogit(
                if (c.hasPathOrNull("mulitnomialLogit")) c.getConfig("mulitnomialLogit")
                else com.typesafe.config.ConfigFactory.parseString("mulitnomialLogit{}")
              )
            )
          }
        }

        case class Vehicles(
          downsamplingMethod: java.lang.String,
          dummySharedBike: BeamConfig.Beam.Agentsim.Agents.Vehicles.DummySharedBike,
          dummySharedCar: BeamConfig.Beam.Agentsim.Agents.Vehicles.DummySharedCar,
          fractionOfInitialVehicleFleet: scala.Double,
          fractionOfPeopleWithBicycle: scala.Double,
          fuelTypesFilePath: java.lang.String,
          linkToGradePercentFilePath: java.lang.String,
          meanPrivateVehicleStartingSOC: scala.Double,
          meanRidehailVehicleStartingSOC: scala.Double,
          sharedFleets: scala.List[BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm],
          transitVehicleTypesByRouteFile: java.lang.String,
          vehicleAdjustmentMethod: java.lang.String,
          vehicleTypesFilePath: java.lang.String,
          vehiclesFilePath: java.lang.String
        )

        object Vehicles {

          case class DummySharedBike(
            vehicleTypeId: java.lang.String
          )

          object DummySharedBike {

            def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.Vehicles.DummySharedBike = {
              BeamConfig.Beam.Agentsim.Agents.Vehicles.DummySharedBike(
                vehicleTypeId =
                  if (c.hasPathOrNull("vehicleTypeId")) c.getString("vehicleTypeId") else "sharedVehicle-sharedBike"
              )
            }
          }

          case class DummySharedCar(
            vehicleTypeId: java.lang.String
          )

          object DummySharedCar {

            def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.Vehicles.DummySharedCar = {
              BeamConfig.Beam.Agentsim.Agents.Vehicles.DummySharedCar(
                vehicleTypeId =
                  if (c.hasPathOrNull("vehicleTypeId")) c.getString("vehicleTypeId") else "sharedVehicle-sharedCar"
              )
            }
          }

          case class SharedFleets$Elm(
            fixed_non_reserving: scala.Option[
              BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm.FixedNonReserving
            ],
            fixed_non_reserving_fleet_by_taz: scala.Option[
              BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm.FixedNonReservingFleetByTaz
            ],
            inexhaustible_reserving: scala.Option[
              BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm.InexhaustibleReserving
            ],
            managerType: java.lang.String,
            name: java.lang.String,
            parkingFilePath: java.lang.String,
            reposition: scala.Option[BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm.Reposition]
          )

          object SharedFleets$Elm {

            case class FixedNonReserving(
              maxWalkingDistance: scala.Int,
              vehicleTypeId: java.lang.String
            )

            object FixedNonReserving {

              def apply(
                c: com.typesafe.config.Config
              ): BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm.FixedNonReserving = {
                BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm.FixedNonReserving(
                  maxWalkingDistance =
                    if (c.hasPathOrNull("maxWalkingDistance")) c.getInt("maxWalkingDistance") else 500,
                  vehicleTypeId =
                    if (c.hasPathOrNull("vehicleTypeId")) c.getString("vehicleTypeId") else "sharedVehicle-sharedCar"
                )
              }
            }

            case class FixedNonReservingFleetByTaz(
              fleetSize: scala.Int,
              maxWalkingDistance: scala.Int,
              vehicleTypeId: java.lang.String,
              vehiclesSharePerTAZFromCSV: scala.Option[java.lang.String]
            )

            object FixedNonReservingFleetByTaz {

              def apply(
                c: com.typesafe.config.Config
              ): BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm.FixedNonReservingFleetByTaz = {
                BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm.FixedNonReservingFleetByTaz(
                  fleetSize = if (c.hasPathOrNull("fleetSize")) c.getInt("fleetSize") else 10,
                  maxWalkingDistance =
                    if (c.hasPathOrNull("maxWalkingDistance")) c.getInt("maxWalkingDistance") else 500,
                  vehicleTypeId =
                    if (c.hasPathOrNull("vehicleTypeId")) c.getString("vehicleTypeId") else "sharedVehicle-sharedCar",
                  vehiclesSharePerTAZFromCSV =
                    if (c.hasPathOrNull("vehiclesSharePerTAZFromCSV")) Some(c.getString("vehiclesSharePerTAZFromCSV"))
                    else None
                )
              }
            }

            case class InexhaustibleReserving(
              vehicleTypeId: java.lang.String
            )

            object InexhaustibleReserving {

              def apply(
                c: com.typesafe.config.Config
              ): BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm.InexhaustibleReserving = {
                BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm.InexhaustibleReserving(
                  vehicleTypeId =
                    if (c.hasPathOrNull("vehicleTypeId")) c.getString("vehicleTypeId") else "sharedVehicle-sharedCar"
                )
              }
            }

            case class Reposition(
              min_availability_undersupply_algorithm: scala.Option[
                BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm.Reposition.MinAvailabilityUndersupplyAlgorithm
              ],
              name: java.lang.String,
              repositionTimeBin: scala.Int,
              statTimeBin: scala.Int
            )

            object Reposition {

              case class MinAvailabilityUndersupplyAlgorithm(
                matchLimit: scala.Int
              )

              object MinAvailabilityUndersupplyAlgorithm {

                def apply(
                  c: com.typesafe.config.Config
                ): BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm.Reposition.MinAvailabilityUndersupplyAlgorithm = {
                  BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm.Reposition
                    .MinAvailabilityUndersupplyAlgorithm(
                      matchLimit = if (c.hasPathOrNull("matchLimit")) c.getInt("matchLimit") else 99999
                    )
                }
              }

              def apply(
                c: com.typesafe.config.Config
              ): BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm.Reposition = {
                BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm.Reposition(
                  min_availability_undersupply_algorithm =
                    if (c.hasPathOrNull("min-availability-undersupply-algorithm"))
                      scala.Some(
                        BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm.Reposition
                          .MinAvailabilityUndersupplyAlgorithm(c.getConfig("min-availability-undersupply-algorithm"))
                      )
                    else None,
                  name = if (c.hasPathOrNull("name")) c.getString("name") else "my-reposition-algorithm",
                  repositionTimeBin = if (c.hasPathOrNull("repositionTimeBin")) c.getInt("repositionTimeBin") else 3600,
                  statTimeBin = if (c.hasPathOrNull("statTimeBin")) c.getInt("statTimeBin") else 300
                )
              }
            }

            def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm = {
              BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm(
                fixed_non_reserving =
                  if (c.hasPathOrNull("fixed-non-reserving"))
                    scala.Some(
                      BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm
                        .FixedNonReserving(c.getConfig("fixed-non-reserving"))
                    )
                  else None,
                fixed_non_reserving_fleet_by_taz =
                  if (c.hasPathOrNull("fixed-non-reserving-fleet-by-taz"))
                    scala.Some(
                      BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm
                        .FixedNonReservingFleetByTaz(c.getConfig("fixed-non-reserving-fleet-by-taz"))
                    )
                  else None,
                inexhaustible_reserving =
                  if (c.hasPathOrNull("inexhaustible-reserving"))
                    scala.Some(
                      BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm
                        .InexhaustibleReserving(c.getConfig("inexhaustible-reserving"))
                    )
                  else None,
                managerType = if (c.hasPathOrNull("managerType")) c.getString("managerType") else "fixed-non-reserving",
                name = if (c.hasPathOrNull("name")) c.getString("name") else "my-fixed-non-reserving-fleet",
                parkingFilePath = if (c.hasPathOrNull("parkingFilePath")) c.getString("parkingFilePath") else "",
                reposition =
                  if (c.hasPathOrNull("reposition"))
                    scala.Some(
                      BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm.Reposition(c.getConfig("reposition"))
                    )
                  else None
              )
            }
          }

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents.Vehicles = {
            BeamConfig.Beam.Agentsim.Agents.Vehicles(
              downsamplingMethod =
                if (c.hasPathOrNull("downsamplingMethod")) c.getString("downsamplingMethod")
                else "SECONDARY_VEHICLES_FIRST",
              dummySharedBike = BeamConfig.Beam.Agentsim.Agents.Vehicles.DummySharedBike(
                if (c.hasPathOrNull("dummySharedBike")) c.getConfig("dummySharedBike")
                else com.typesafe.config.ConfigFactory.parseString("dummySharedBike{}")
              ),
              dummySharedCar = BeamConfig.Beam.Agentsim.Agents.Vehicles.DummySharedCar(
                if (c.hasPathOrNull("dummySharedCar")) c.getConfig("dummySharedCar")
                else com.typesafe.config.ConfigFactory.parseString("dummySharedCar{}")
              ),
              fractionOfInitialVehicleFleet =
                if (c.hasPathOrNull("fractionOfInitialVehicleFleet")) c.getDouble("fractionOfInitialVehicleFleet")
                else 1.0,
              fractionOfPeopleWithBicycle =
                if (c.hasPathOrNull("fractionOfPeopleWithBicycle")) c.getDouble("fractionOfPeopleWithBicycle") else 1.0,
              fuelTypesFilePath =
                if (c.hasPathOrNull("fuelTypesFilePath")) c.getString("fuelTypesFilePath")
                else "/test/input/beamville/beamFuelTypes.csv",
              linkToGradePercentFilePath =
                if (c.hasPathOrNull("linkToGradePercentFilePath")) c.getString("linkToGradePercentFilePath") else "",
              meanPrivateVehicleStartingSOC =
                if (c.hasPathOrNull("meanPrivateVehicleStartingSOC")) c.getDouble("meanPrivateVehicleStartingSOC")
                else 1.0,
              meanRidehailVehicleStartingSOC =
                if (c.hasPathOrNull("meanRidehailVehicleStartingSOC")) c.getDouble("meanRidehailVehicleStartingSOC")
                else 1.0,
              sharedFleets = $_LBeamConfig_Beam_Agentsim_Agents_Vehicles_SharedFleets$Elm(c.getList("sharedFleets")),
              transitVehicleTypesByRouteFile =
                if (c.hasPathOrNull("transitVehicleTypesByRouteFile")) c.getString("transitVehicleTypesByRouteFile")
                else "",
              vehicleAdjustmentMethod =
                if (c.hasPathOrNull("vehicleAdjustmentMethod")) c.getString("vehicleAdjustmentMethod") else "UNIFORM",
              vehicleTypesFilePath =
                if (c.hasPathOrNull("vehicleTypesFilePath")) c.getString("vehicleTypesFilePath")
                else "/test/input/beamville/vehicleTypes.csv",
              vehiclesFilePath =
                if (c.hasPathOrNull("vehiclesFilePath")) c.getString("vehiclesFilePath")
                else "/test/input/beamville/vehicles.csv"
            )
          }

          private def $_LBeamConfig_Beam_Agentsim_Agents_Vehicles_SharedFleets$Elm(
            cl: com.typesafe.config.ConfigList
          ): scala.List[BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm] = {
            import scala.collection.JavaConverters._
            cl.asScala
              .map(cv =>
                BeamConfig.Beam.Agentsim.Agents.Vehicles
                  .SharedFleets$Elm(cv.asInstanceOf[com.typesafe.config.ConfigObject].toConfig)
              )
              .toList
          }
        }

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Agents = {
          BeamConfig.Beam.Agentsim.Agents(
            bodyType = if (c.hasPathOrNull("bodyType")) c.getString("bodyType") else "BODY-TYPE-DEFAULT",
            freight = BeamConfig.Beam.Agentsim.Agents.Freight(
              if (c.hasPathOrNull("freight")) c.getConfig("freight")
              else com.typesafe.config.ConfigFactory.parseString("freight{}")
            ),
            households = BeamConfig.Beam.Agentsim.Agents.Households(
              if (c.hasPathOrNull("households")) c.getConfig("households")
              else com.typesafe.config.ConfigFactory.parseString("households{}")
            ),
            modalBehaviors = BeamConfig.Beam.Agentsim.Agents.ModalBehaviors(
              if (c.hasPathOrNull("modalBehaviors")) c.getConfig("modalBehaviors")
              else com.typesafe.config.ConfigFactory.parseString("modalBehaviors{}")
            ),
            modeIncentive = BeamConfig.Beam.Agentsim.Agents.ModeIncentive(
              if (c.hasPathOrNull("modeIncentive")) c.getConfig("modeIncentive")
              else com.typesafe.config.ConfigFactory.parseString("modeIncentive{}")
            ),
            parking = BeamConfig.Beam.Agentsim.Agents.Parking(
              if (c.hasPathOrNull("parking")) c.getConfig("parking")
              else com.typesafe.config.ConfigFactory.parseString("parking{}")
            ),
            plans = BeamConfig.Beam.Agentsim.Agents.Plans(
              if (c.hasPathOrNull("plans")) c.getConfig("plans")
              else com.typesafe.config.ConfigFactory.parseString("plans{}")
            ),
            population = BeamConfig.Beam.Agentsim.Agents.Population(
              if (c.hasPathOrNull("population")) c.getConfig("population")
              else com.typesafe.config.ConfigFactory.parseString("population{}")
            ),
            ptFare = BeamConfig.Beam.Agentsim.Agents.PtFare(
              if (c.hasPathOrNull("ptFare")) c.getConfig("ptFare")
              else com.typesafe.config.ConfigFactory.parseString("ptFare{}")
            ),
            rideHail = BeamConfig.Beam.Agentsim.Agents.RideHail(
              if (c.hasPathOrNull("rideHail")) c.getConfig("rideHail")
              else com.typesafe.config.ConfigFactory.parseString("rideHail{}")
            ),
            rideHailTransit = BeamConfig.Beam.Agentsim.Agents.RideHailTransit(
              if (c.hasPathOrNull("rideHailTransit")) c.getConfig("rideHailTransit")
              else com.typesafe.config.ConfigFactory.parseString("rideHailTransit{}")
            ),
            tripBehaviors = BeamConfig.Beam.Agentsim.Agents.TripBehaviors(
              if (c.hasPathOrNull("tripBehaviors")) c.getConfig("tripBehaviors")
              else com.typesafe.config.ConfigFactory.parseString("tripBehaviors{}")
            ),
            vehicles = BeamConfig.Beam.Agentsim.Agents.Vehicles(
              if (c.hasPathOrNull("vehicles")) c.getConfig("vehicles")
              else com.typesafe.config.ConfigFactory.parseString("vehicles{}")
            )
          )
        }
      }

      case class ChargingNetworkManager(
        helics: BeamConfig.Beam.Agentsim.ChargingNetworkManager.Helics,
        timeStepInSeconds: scala.Int
      )

      object ChargingNetworkManager {

        case class Helics(
          bufferSize: scala.Int,
          connectionEnabled: scala.Boolean,
          coreInitString: java.lang.String,
          coreType: java.lang.String,
          dataInStreamPoint: java.lang.String,
          dataOutStreamPoint: java.lang.String,
          federateName: java.lang.String,
          intLogLevel: scala.Int,
          timeDeltaProperty: scala.Double
        )

        object Helics {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.ChargingNetworkManager.Helics = {
            BeamConfig.Beam.Agentsim.ChargingNetworkManager.Helics(
              bufferSize = if (c.hasPathOrNull("bufferSize")) c.getInt("bufferSize") else 1000,
              connectionEnabled = c.hasPathOrNull("connectionEnabled") && c.getBoolean("connectionEnabled"),
              coreInitString =
                if (c.hasPathOrNull("coreInitString")) c.getString("coreInitString")
                else "--federates=1 --broker_address=tcp://127.0.0.1",
              coreType = if (c.hasPathOrNull("coreType")) c.getString("coreType") else "zmq",
              dataInStreamPoint =
                if (c.hasPathOrNull("dataInStreamPoint")) c.getString("dataInStreamPoint")
                else "GridFed/PhysicalBounds",
              dataOutStreamPoint =
                if (c.hasPathOrNull("dataOutStreamPoint")) c.getString("dataOutStreamPoint") else "PowerDemand",
              federateName = if (c.hasPathOrNull("federateName")) c.getString("federateName") else "CNMFederate",
              intLogLevel = if (c.hasPathOrNull("intLogLevel")) c.getInt("intLogLevel") else 1,
              timeDeltaProperty = if (c.hasPathOrNull("timeDeltaProperty")) c.getDouble("timeDeltaProperty") else 1.0
            )
          }
        }

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.ChargingNetworkManager = {
          BeamConfig.Beam.Agentsim.ChargingNetworkManager(
            helics = BeamConfig.Beam.Agentsim.ChargingNetworkManager.Helics(
              if (c.hasPathOrNull("helics")) c.getConfig("helics")
              else com.typesafe.config.ConfigFactory.parseString("helics{}")
            ),
            timeStepInSeconds = if (c.hasPathOrNull("timeStepInSeconds")) c.getInt("timeStepInSeconds") else 300
          )
        }
      }

      case class H3taz(
        lowerBoundResolution: scala.Int,
        upperBoundResolution: scala.Int
      )

      object H3taz {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.H3taz = {
          BeamConfig.Beam.Agentsim.H3taz(
            lowerBoundResolution = if (c.hasPathOrNull("lowerBoundResolution")) c.getInt("lowerBoundResolution") else 6,
            upperBoundResolution = if (c.hasPathOrNull("upperBoundResolution")) c.getInt("upperBoundResolution") else 9
          )
        }
      }

      case class Scenarios(
        frequencyAdjustmentFile: java.lang.String
      )

      object Scenarios {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Scenarios = {
          BeamConfig.Beam.Agentsim.Scenarios(
            frequencyAdjustmentFile =
              if (c.hasPathOrNull("frequencyAdjustmentFile")) c.getString("frequencyAdjustmentFile")
              else "/test/input/beamville/r5/FrequencyAdjustment.csv"
          )
        }
      }

      case class ScheduleMonitorTask(
        initialDelay: scala.Int,
        interval: scala.Int
      )

      object ScheduleMonitorTask {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.ScheduleMonitorTask = {
          BeamConfig.Beam.Agentsim.ScheduleMonitorTask(
            initialDelay = if (c.hasPathOrNull("initialDelay")) c.getInt("initialDelay") else 1,
            interval = if (c.hasPathOrNull("interval")) c.getInt("interval") else 30
          )
        }
      }

      case class Taz(
        filePath: java.lang.String,
        parkingCostScalingFactor: scala.Double,
        parkingFilePath: java.lang.String,
        parkingManager: BeamConfig.Beam.Agentsim.Taz.ParkingManager,
        parkingStallCountScalingFactor: scala.Double
      )

      object Taz {

        case class ParkingManager(
          displayPerformanceTimings: scala.Boolean,
          level: java.lang.String,
          method: java.lang.String,
          parallel: BeamConfig.Beam.Agentsim.Taz.ParkingManager.Parallel
        )

        object ParkingManager {

          case class Parallel(
            numberOfClusters: scala.Int
          )

          object Parallel {

            def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Taz.ParkingManager.Parallel = {
              BeamConfig.Beam.Agentsim.Taz.ParkingManager.Parallel(
                numberOfClusters = if (c.hasPathOrNull("numberOfClusters")) c.getInt("numberOfClusters") else 8
              )
            }
          }

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Taz.ParkingManager = {
            BeamConfig.Beam.Agentsim.Taz.ParkingManager(
              displayPerformanceTimings =
                c.hasPathOrNull("displayPerformanceTimings") && c.getBoolean("displayPerformanceTimings"),
              level = if (c.hasPathOrNull("level")) c.getString("level") else "TAZ",
              method = if (c.hasPathOrNull("method")) c.getString("method") else "DEFAULT",
              parallel = BeamConfig.Beam.Agentsim.Taz.ParkingManager.Parallel(
                if (c.hasPathOrNull("parallel")) c.getConfig("parallel")
                else com.typesafe.config.ConfigFactory.parseString("parallel{}")
              )
            )
          }
        }

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Taz = {
          BeamConfig.Beam.Agentsim.Taz(
            filePath =
              if (c.hasPathOrNull("filePath")) c.getString("filePath") else "/test/input/beamville/taz-centers.csv",
            parkingCostScalingFactor =
              if (c.hasPathOrNull("parkingCostScalingFactor")) c.getDouble("parkingCostScalingFactor") else 1.0,
            parkingFilePath = if (c.hasPathOrNull("parkingFilePath")) c.getString("parkingFilePath") else "",
            parkingManager = BeamConfig.Beam.Agentsim.Taz.ParkingManager(
              if (c.hasPathOrNull("parkingManager")) c.getConfig("parkingManager")
              else com.typesafe.config.ConfigFactory.parseString("parkingManager{}")
            ),
            parkingStallCountScalingFactor =
              if (c.hasPathOrNull("parkingStallCountScalingFactor")) c.getDouble("parkingStallCountScalingFactor")
              else 1.0
          )
        }
      }

      case class Toll(
        filePath: java.lang.String
      )

      object Toll {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Toll = {
          BeamConfig.Beam.Agentsim.Toll(
            filePath =
              if (c.hasPathOrNull("filePath")) c.getString("filePath") else "/test/input/beamville/toll-prices.csv"
          )
        }
      }

      case class Tuning(
        fuelCapacityInJoules: scala.Double,
        rideHailPrice: scala.Double,
        tollPrice: scala.Double,
        transitCapacity: scala.Option[scala.Double],
        transitPrice: scala.Double
      )

      object Tuning {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim.Tuning = {
          BeamConfig.Beam.Agentsim.Tuning(
            fuelCapacityInJoules =
              if (c.hasPathOrNull("fuelCapacityInJoules")) c.getDouble("fuelCapacityInJoules") else 86400000,
            rideHailPrice = if (c.hasPathOrNull("rideHailPrice")) c.getDouble("rideHailPrice") else 1.0,
            tollPrice = if (c.hasPathOrNull("tollPrice")) c.getDouble("tollPrice") else 1.0,
            transitCapacity = if (c.hasPathOrNull("transitCapacity")) Some(c.getDouble("transitCapacity")) else None,
            transitPrice = if (c.hasPathOrNull("transitPrice")) c.getDouble("transitPrice") else 1.0
          )
        }
      }

      def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Agentsim = {
        BeamConfig.Beam.Agentsim(
          agentSampleSizeAsFractionOfPopulation =
            if (c.hasPathOrNull("agentSampleSizeAsFractionOfPopulation"))
              c.getDouble("agentSampleSizeAsFractionOfPopulation")
            else 1.0,
          agents = BeamConfig.Beam.Agentsim.Agents(
            if (c.hasPathOrNull("agents")) c.getConfig("agents")
            else com.typesafe.config.ConfigFactory.parseString("agents{}")
          ),
          chargingNetworkManager = BeamConfig.Beam.Agentsim.ChargingNetworkManager(
            if (c.hasPathOrNull("chargingNetworkManager")) c.getConfig("chargingNetworkManager")
            else com.typesafe.config.ConfigFactory.parseString("chargingNetworkManager{}")
          ),
          endTime = if (c.hasPathOrNull("endTime")) c.getString("endTime") else "30:00:00",
          firstIteration = if (c.hasPathOrNull("firstIteration")) c.getInt("firstIteration") else 0,
          fractionOfPlansWithSingleActivity =
            if (c.hasPathOrNull("fractionOfPlansWithSingleActivity")) c.getDouble("fractionOfPlansWithSingleActivity")
            else 0.0,
          h3taz = BeamConfig.Beam.Agentsim.H3taz(
            if (c.hasPathOrNull("h3taz")) c.getConfig("h3taz")
            else com.typesafe.config.ConfigFactory.parseString("h3taz{}")
          ),
          lastIteration = if (c.hasPathOrNull("lastIteration")) c.getInt("lastIteration") else 0,
          populationAdjustment =
            if (c.hasPathOrNull("populationAdjustment")) c.getString("populationAdjustment") else "DEFAULT_ADJUSTMENT",
          scenarios = BeamConfig.Beam.Agentsim.Scenarios(
            if (c.hasPathOrNull("scenarios")) c.getConfig("scenarios")
            else com.typesafe.config.ConfigFactory.parseString("scenarios{}")
          ),
          scheduleMonitorTask = BeamConfig.Beam.Agentsim.ScheduleMonitorTask(
            if (c.hasPathOrNull("scheduleMonitorTask")) c.getConfig("scheduleMonitorTask")
            else com.typesafe.config.ConfigFactory.parseString("scheduleMonitorTask{}")
          ),
          schedulerParallelismWindow =
            if (c.hasPathOrNull("schedulerParallelismWindow")) c.getInt("schedulerParallelismWindow") else 30,
          simulationName = if (c.hasPathOrNull("simulationName")) c.getString("simulationName") else "beamville",
          taz = BeamConfig.Beam.Agentsim.Taz(
            if (c.hasPathOrNull("taz")) c.getConfig("taz") else com.typesafe.config.ConfigFactory.parseString("taz{}")
          ),
          thresholdForMakingParkingChoiceInMeters =
            if (c.hasPathOrNull("thresholdForMakingParkingChoiceInMeters"))
              c.getInt("thresholdForMakingParkingChoiceInMeters")
            else 100,
          thresholdForWalkingInMeters =
            if (c.hasPathOrNull("thresholdForWalkingInMeters")) c.getInt("thresholdForWalkingInMeters") else 100,
          timeBinSize = if (c.hasPathOrNull("timeBinSize")) c.getInt("timeBinSize") else 3600,
          toll = BeamConfig.Beam.Agentsim.Toll(
            if (c.hasPathOrNull("toll")) c.getConfig("toll")
            else com.typesafe.config.ConfigFactory.parseString("toll{}")
          ),
          tuning = BeamConfig.Beam.Agentsim.Tuning(
            if (c.hasPathOrNull("tuning")) c.getConfig("tuning")
            else com.typesafe.config.ConfigFactory.parseString("tuning{}")
          )
        )
      }
    }

    case class Calibration(
      counts: BeamConfig.Beam.Calibration.Counts,
      google: BeamConfig.Beam.Calibration.Google,
      meanToCountsWeightRatio: scala.Double,
      mode: BeamConfig.Beam.Calibration.Mode,
      objectiveFunction: java.lang.String,
      roadNetwork: BeamConfig.Beam.Calibration.RoadNetwork,
      studyArea: BeamConfig.Beam.Calibration.StudyArea
    )

    object Calibration {

      case class Counts(
        averageCountsOverIterations: scala.Int,
        countsScaleFactor: scala.Int,
        inputCountsFile: java.lang.String,
        writeCountsInterval: scala.Int
      )

      object Counts {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Calibration.Counts = {
          BeamConfig.Beam.Calibration.Counts(
            averageCountsOverIterations =
              if (c.hasPathOrNull("averageCountsOverIterations")) c.getInt("averageCountsOverIterations") else 1,
            countsScaleFactor = if (c.hasPathOrNull("countsScaleFactor")) c.getInt("countsScaleFactor") else 10,
            inputCountsFile = if (c.hasPathOrNull("inputCountsFile")) c.getString("inputCountsFile") else "",
            writeCountsInterval = if (c.hasPathOrNull("writeCountsInterval")) c.getInt("writeCountsInterval") else 1
          )
        }
      }

      case class Google(
        travelTimes: BeamConfig.Beam.Calibration.Google.TravelTimes
      )

      object Google {

        case class TravelTimes(
          enable: scala.Boolean,
          iterationInterval: scala.Int,
          minDistanceInMeters: scala.Double,
          numDataPointsOver24Hours: scala.Int,
          offPeakEnabled: scala.Boolean,
          queryDate: java.lang.String,
          tolls: scala.Boolean
        )

        object TravelTimes {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Calibration.Google.TravelTimes = {
            BeamConfig.Beam.Calibration.Google.TravelTimes(
              enable = c.hasPathOrNull("enable") && c.getBoolean("enable"),
              iterationInterval = if (c.hasPathOrNull("iterationInterval")) c.getInt("iterationInterval") else 5,
              minDistanceInMeters =
                if (c.hasPathOrNull("minDistanceInMeters")) c.getDouble("minDistanceInMeters") else 5000,
              numDataPointsOver24Hours =
                if (c.hasPathOrNull("numDataPointsOver24Hours")) c.getInt("numDataPointsOver24Hours") else 100,
              offPeakEnabled = c.hasPathOrNull("offPeakEnabled") && c.getBoolean("offPeakEnabled"),
              queryDate = if (c.hasPathOrNull("queryDate")) c.getString("queryDate") else "2020-10-14",
              tolls = !c.hasPathOrNull("tolls") || c.getBoolean("tolls")
            )
          }
        }

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Calibration.Google = {
          BeamConfig.Beam.Calibration.Google(
            travelTimes = BeamConfig.Beam.Calibration.Google.TravelTimes(
              if (c.hasPathOrNull("travelTimes")) c.getConfig("travelTimes")
              else com.typesafe.config.ConfigFactory.parseString("travelTimes{}")
            )
          )
        }
      }

      case class Mode(
        benchmarkFilePath: java.lang.String
      )

      object Mode {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Calibration.Mode = {
          BeamConfig.Beam.Calibration.Mode(
            benchmarkFilePath = if (c.hasPathOrNull("benchmarkFilePath")) c.getString("benchmarkFilePath") else ""
          )
        }
      }

      case class RoadNetwork(
        travelTimes: BeamConfig.Beam.Calibration.RoadNetwork.TravelTimes
      )

      object RoadNetwork {

        case class TravelTimes(
          zoneBoundariesFilePath: java.lang.String,
          zoneODTravelTimesFilePath: java.lang.String
        )

        object TravelTimes {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Calibration.RoadNetwork.TravelTimes = {
            BeamConfig.Beam.Calibration.RoadNetwork.TravelTimes(
              zoneBoundariesFilePath =
                if (c.hasPathOrNull("zoneBoundariesFilePath")) c.getString("zoneBoundariesFilePath") else "",
              zoneODTravelTimesFilePath =
                if (c.hasPathOrNull("zoneODTravelTimesFilePath")) c.getString("zoneODTravelTimesFilePath") else ""
            )
          }
        }

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Calibration.RoadNetwork = {
          BeamConfig.Beam.Calibration.RoadNetwork(
            travelTimes = BeamConfig.Beam.Calibration.RoadNetwork.TravelTimes(
              if (c.hasPathOrNull("travelTimes")) c.getConfig("travelTimes")
              else com.typesafe.config.ConfigFactory.parseString("travelTimes{}")
            )
          )
        }
      }

      case class StudyArea(
        enabled: scala.Boolean,
        lat: scala.Double,
        lon: scala.Double,
        radius: scala.Double
      )

      object StudyArea {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Calibration.StudyArea = {
          BeamConfig.Beam.Calibration.StudyArea(
            enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled"),
            lat = if (c.hasPathOrNull("lat")) c.getDouble("lat") else 0,
            lon = if (c.hasPathOrNull("lon")) c.getDouble("lon") else 0,
            radius = if (c.hasPathOrNull("radius")) c.getDouble("radius") else 0
          )
        }
      }

      def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Calibration = {
        BeamConfig.Beam.Calibration(
          counts = BeamConfig.Beam.Calibration.Counts(
            if (c.hasPathOrNull("counts")) c.getConfig("counts")
            else com.typesafe.config.ConfigFactory.parseString("counts{}")
          ),
          google = BeamConfig.Beam.Calibration.Google(
            if (c.hasPathOrNull("google")) c.getConfig("google")
            else com.typesafe.config.ConfigFactory.parseString("google{}")
          ),
          meanToCountsWeightRatio =
            if (c.hasPathOrNull("meanToCountsWeightRatio")) c.getDouble("meanToCountsWeightRatio") else 0.5,
          mode = BeamConfig.Beam.Calibration.Mode(
            if (c.hasPathOrNull("mode")) c.getConfig("mode")
            else com.typesafe.config.ConfigFactory.parseString("mode{}")
          ),
          objectiveFunction =
            if (c.hasPathOrNull("objectiveFunction")) c.getString("objectiveFunction")
            else "ModeChoiceObjectiveFunction",
          roadNetwork = BeamConfig.Beam.Calibration.RoadNetwork(
            if (c.hasPathOrNull("roadNetwork")) c.getConfig("roadNetwork")
            else com.typesafe.config.ConfigFactory.parseString("roadNetwork{}")
          ),
          studyArea = BeamConfig.Beam.Calibration.StudyArea(
            if (c.hasPathOrNull("studyArea")) c.getConfig("studyArea")
            else com.typesafe.config.ConfigFactory.parseString("studyArea{}")
          )
        )
      }
    }

    case class Cluster(
      clusterType: scala.Option[java.lang.String],
      enabled: scala.Boolean
    )

    object Cluster {

      def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Cluster = {
        BeamConfig.Beam.Cluster(
          clusterType = if (c.hasPathOrNull("clusterType")) Some(c.getString("clusterType")) else None,
          enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled")
        )
      }
    }

    case class Debug(
      actor: BeamConfig.Beam.Debug.Actor,
      agentTripScoresInterval: scala.Int,
      clearRoutedOutstandingWorkEnabled: scala.Boolean,
      debugActorTimerIntervalInSec: scala.Int,
      debugEnabled: scala.Boolean,
      memoryConsumptionDisplayTimeoutInSec: scala.Int,
      messageLogging: scala.Boolean,
      secondsToWaitToClearRoutedOutstandingWork: scala.Int,
      stuckAgentDetection: BeamConfig.Beam.Debug.StuckAgentDetection,
      triggerMeasurer: BeamConfig.Beam.Debug.TriggerMeasurer,
      vmInformation: BeamConfig.Beam.Debug.VmInformation,
      writeModeChoiceAlternatives: scala.Boolean,
      writeRealizedModeChoiceFile: scala.Boolean
    )

    object Debug {

      case class Actor(
        logDepth: scala.Int
      )

      object Actor {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Debug.Actor = {
          BeamConfig.Beam.Debug.Actor(
            logDepth = if (c.hasPathOrNull("logDepth")) c.getInt("logDepth") else 0
          )
        }
      }

      case class StuckAgentDetection(
        checkIntervalMs: scala.Long,
        checkMaxNumberOfMessagesEnabled: scala.Boolean,
        defaultTimeoutMs: scala.Long,
        enabled: scala.Boolean,
        overallSimulationTimeoutMs: scala.Long,
        thresholds: scala.List[BeamConfig.Beam.Debug.StuckAgentDetection.Thresholds$Elm]
      )

      object StuckAgentDetection {

        case class Thresholds$Elm(
          actorTypeToMaxNumberOfMessages: BeamConfig.Beam.Debug.StuckAgentDetection.Thresholds$Elm.ActorTypeToMaxNumberOfMessages,
          markAsStuckAfterMs: scala.Long,
          triggerType: java.lang.String
        )

        object Thresholds$Elm {

          case class ActorTypeToMaxNumberOfMessages(
            population: scala.Option[scala.Int],
            rideHailAgent: scala.Option[scala.Int],
            rideHailManager: scala.Option[scala.Int],
            transitDriverAgent: scala.Option[scala.Int]
          )

          object ActorTypeToMaxNumberOfMessages {

            def apply(
              c: com.typesafe.config.Config
            ): BeamConfig.Beam.Debug.StuckAgentDetection.Thresholds$Elm.ActorTypeToMaxNumberOfMessages = {
              BeamConfig.Beam.Debug.StuckAgentDetection.Thresholds$Elm.ActorTypeToMaxNumberOfMessages(
                population = if (c.hasPathOrNull("population")) Some(c.getInt("population")) else None,
                rideHailAgent = if (c.hasPathOrNull("rideHailAgent")) Some(c.getInt("rideHailAgent")) else None,
                rideHailManager = if (c.hasPathOrNull("rideHailManager")) Some(c.getInt("rideHailManager")) else None,
                transitDriverAgent =
                  if (c.hasPathOrNull("transitDriverAgent")) Some(c.getInt("transitDriverAgent")) else None
              )
            }
          }

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Debug.StuckAgentDetection.Thresholds$Elm = {
            BeamConfig.Beam.Debug.StuckAgentDetection.Thresholds$Elm(
              actorTypeToMaxNumberOfMessages =
                BeamConfig.Beam.Debug.StuckAgentDetection.Thresholds$Elm.ActorTypeToMaxNumberOfMessages(
                  if (c.hasPathOrNull("actorTypeToMaxNumberOfMessages")) c.getConfig("actorTypeToMaxNumberOfMessages")
                  else com.typesafe.config.ConfigFactory.parseString("actorTypeToMaxNumberOfMessages{}")
                ),
              markAsStuckAfterMs =
                if (c.hasPathOrNull("markAsStuckAfterMs"))
                  c.getDuration("markAsStuckAfterMs", java.util.concurrent.TimeUnit.MILLISECONDS)
                else 20000,
              triggerType =
                if (c.hasPathOrNull("triggerType")) c.getString("triggerType")
                else "beam.agentsim.agents.PersonAgent$ActivityStartTrigger"
            )
          }
        }

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Debug.StuckAgentDetection = {
          BeamConfig.Beam.Debug.StuckAgentDetection(
            checkIntervalMs =
              if (c.hasPathOrNull("checkIntervalMs"))
                c.getDuration("checkIntervalMs", java.util.concurrent.TimeUnit.MILLISECONDS)
              else 200,
            checkMaxNumberOfMessagesEnabled =
              !c.hasPathOrNull("checkMaxNumberOfMessagesEnabled") || c.getBoolean("checkMaxNumberOfMessagesEnabled"),
            defaultTimeoutMs =
              if (c.hasPathOrNull("defaultTimeoutMs"))
                c.getDuration("defaultTimeoutMs", java.util.concurrent.TimeUnit.MILLISECONDS)
              else 60000,
            enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled"),
            overallSimulationTimeoutMs =
              if (c.hasPathOrNull("overallSimulationTimeoutMs"))
                c.getDuration("overallSimulationTimeoutMs", java.util.concurrent.TimeUnit.MILLISECONDS)
              else 100000,
            thresholds = $_LBeamConfig_Beam_Debug_StuckAgentDetection_Thresholds$Elm(c.getList("thresholds"))
          )
        }

        private def $_LBeamConfig_Beam_Debug_StuckAgentDetection_Thresholds$Elm(
          cl: com.typesafe.config.ConfigList
        ): scala.List[BeamConfig.Beam.Debug.StuckAgentDetection.Thresholds$Elm] = {
          import scala.collection.JavaConverters._
          cl.asScala
            .map(cv =>
              BeamConfig.Beam.Debug.StuckAgentDetection
                .Thresholds$Elm(cv.asInstanceOf[com.typesafe.config.ConfigObject].toConfig)
            )
            .toList
        }
      }

      case class TriggerMeasurer(
        enabled: scala.Boolean,
        writeStuckAgentDetectionConfig: scala.Boolean
      )

      object TriggerMeasurer {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Debug.TriggerMeasurer = {
          BeamConfig.Beam.Debug.TriggerMeasurer(
            enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled"),
            writeStuckAgentDetectionConfig =
              !c.hasPathOrNull("writeStuckAgentDetectionConfig") || c.getBoolean("writeStuckAgentDetectionConfig")
          )
        }
      }

      case class VmInformation(
        createGCClassHistogram: scala.Boolean
      )

      object VmInformation {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Debug.VmInformation = {
          BeamConfig.Beam.Debug.VmInformation(
            createGCClassHistogram = c.hasPathOrNull("createGCClassHistogram") && c.getBoolean("createGCClassHistogram")
          )
        }
      }

      def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Debug = {
        BeamConfig.Beam.Debug(
          actor = BeamConfig.Beam.Debug.Actor(
            if (c.hasPathOrNull("actor")) c.getConfig("actor")
            else com.typesafe.config.ConfigFactory.parseString("actor{}")
          ),
          agentTripScoresInterval =
            if (c.hasPathOrNull("agentTripScoresInterval")) c.getInt("agentTripScoresInterval") else 0,
          clearRoutedOutstandingWorkEnabled =
            c.hasPathOrNull("clearRoutedOutstandingWorkEnabled") && c.getBoolean("clearRoutedOutstandingWorkEnabled"),
          debugActorTimerIntervalInSec =
            if (c.hasPathOrNull("debugActorTimerIntervalInSec")) c.getInt("debugActorTimerIntervalInSec") else 0,
          debugEnabled = c.hasPathOrNull("debugEnabled") && c.getBoolean("debugEnabled"),
          memoryConsumptionDisplayTimeoutInSec =
            if (c.hasPathOrNull("memoryConsumptionDisplayTimeoutInSec"))
              c.getInt("memoryConsumptionDisplayTimeoutInSec")
            else 0,
          messageLogging = c.hasPathOrNull("messageLogging") && c.getBoolean("messageLogging"),
          secondsToWaitToClearRoutedOutstandingWork =
            if (c.hasPathOrNull("secondsToWaitToClearRoutedOutstandingWork"))
              c.getInt("secondsToWaitToClearRoutedOutstandingWork")
            else 60,
          stuckAgentDetection = BeamConfig.Beam.Debug.StuckAgentDetection(
            if (c.hasPathOrNull("stuckAgentDetection")) c.getConfig("stuckAgentDetection")
            else com.typesafe.config.ConfigFactory.parseString("stuckAgentDetection{}")
          ),
          triggerMeasurer = BeamConfig.Beam.Debug.TriggerMeasurer(
            if (c.hasPathOrNull("triggerMeasurer")) c.getConfig("triggerMeasurer")
            else com.typesafe.config.ConfigFactory.parseString("triggerMeasurer{}")
          ),
          vmInformation = BeamConfig.Beam.Debug.VmInformation(
            if (c.hasPathOrNull("vmInformation")) c.getConfig("vmInformation")
            else com.typesafe.config.ConfigFactory.parseString("vmInformation{}")
          ),
          writeModeChoiceAlternatives =
            c.hasPathOrNull("writeModeChoiceAlternatives") && c.getBoolean("writeModeChoiceAlternatives"),
          writeRealizedModeChoiceFile =
            c.hasPathOrNull("writeRealizedModeChoiceFile") && c.getBoolean("writeRealizedModeChoiceFile")
        )
      }
    }

    case class Exchange(
      output: BeamConfig.Beam.Exchange.Output,
      scenario: BeamConfig.Beam.Exchange.Scenario
    )

    object Exchange {

      case class Output(
        activitySimSkimsEnabled: scala.Boolean,
        geo: BeamConfig.Beam.Exchange.Output.Geo
      )

      object Output {

        case class Geo(
          filePath: scala.Option[java.lang.String]
        )

        object Geo {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Exchange.Output.Geo = {
            BeamConfig.Beam.Exchange.Output.Geo(
              filePath = if (c.hasPathOrNull("filePath")) Some(c.getString("filePath")) else None
            )
          }
        }

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Exchange.Output = {
          BeamConfig.Beam.Exchange.Output(
            activitySimSkimsEnabled =
              c.hasPathOrNull("activitySimSkimsEnabled") && c.getBoolean("activitySimSkimsEnabled"),
            geo = BeamConfig.Beam.Exchange.Output.Geo(
              if (c.hasPathOrNull("geo")) c.getConfig("geo") else com.typesafe.config.ConfigFactory.parseString("geo{}")
            )
          )
        }
      }

      case class Scenario(
        convertWgs2Utm: scala.Boolean,
        fileFormat: java.lang.String,
        folder: java.lang.String,
        modeMap: scala.Option[scala.List[java.lang.String]],
        source: java.lang.String,
        urbansim: BeamConfig.Beam.Exchange.Scenario.Urbansim
      )

      object Scenario {

        case class Urbansim(
          activitySimEnabled: scala.Boolean
        )

        object Urbansim {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Exchange.Scenario.Urbansim = {
            BeamConfig.Beam.Exchange.Scenario.Urbansim(
              activitySimEnabled = c.hasPathOrNull("activitySimEnabled") && c.getBoolean("activitySimEnabled")
            )
          }
        }

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Exchange.Scenario = {
          BeamConfig.Beam.Exchange.Scenario(
            convertWgs2Utm = c.hasPathOrNull("convertWgs2Utm") && c.getBoolean("convertWgs2Utm"),
            fileFormat = if (c.hasPathOrNull("fileFormat")) c.getString("fileFormat") else "xml",
            folder = if (c.hasPathOrNull("folder")) c.getString("folder") else "",
            modeMap = if (c.hasPathOrNull("modeMap")) scala.Some($_L$_str(c.getList("modeMap"))) else None,
            source = if (c.hasPathOrNull("source")) c.getString("source") else "Beam",
            urbansim = BeamConfig.Beam.Exchange.Scenario.Urbansim(
              if (c.hasPathOrNull("urbansim")) c.getConfig("urbansim")
              else com.typesafe.config.ConfigFactory.parseString("urbansim{}")
            )
          )
        }
      }

      def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Exchange = {
        BeamConfig.Beam.Exchange(
          output = BeamConfig.Beam.Exchange.Output(
            if (c.hasPathOrNull("output")) c.getConfig("output")
            else com.typesafe.config.ConfigFactory.parseString("output{}")
          ),
          scenario = BeamConfig.Beam.Exchange.Scenario(
            if (c.hasPathOrNull("scenario")) c.getConfig("scenario")
            else com.typesafe.config.ConfigFactory.parseString("scenario{}")
          )
        )
      }
    }

    case class Experimental(
      optimizer: BeamConfig.Beam.Experimental.Optimizer
    )

    object Experimental {

      case class Optimizer(
        enabled: scala.Boolean
      )

      object Optimizer {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Experimental.Optimizer = {
          BeamConfig.Beam.Experimental.Optimizer(
            enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled")
          )
        }
      }

      def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Experimental = {
        BeamConfig.Beam.Experimental(
          optimizer = BeamConfig.Beam.Experimental.Optimizer(
            if (c.hasPathOrNull("optimizer")) c.getConfig("optimizer")
            else com.typesafe.config.ConfigFactory.parseString("optimizer{}")
          )
        )
      }
    }

    case class Input(
      lastBaseOutputDir: java.lang.String,
      simulationPrefix: java.lang.String
    )

    object Input {

      def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Input = {
        BeamConfig.Beam.Input(
          lastBaseOutputDir = if (c.hasPathOrNull("lastBaseOutputDir")) c.getString("lastBaseOutputDir") else "output",
          simulationPrefix = if (c.hasPathOrNull("simulationPrefix")) c.getString("simulationPrefix") else "beamville"
        )
      }
    }

    case class Logger(
      keepConsoleAppenderOn: scala.Boolean
    )

    object Logger {

      def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Logger = {
        BeamConfig.Beam.Logger(
          keepConsoleAppenderOn = !c.hasPathOrNull("keepConsoleAppenderOn") || c.getBoolean("keepConsoleAppenderOn")
        )
      }
    }

    case class Metrics(
      level: java.lang.String
    )

    object Metrics {

      def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Metrics = {
        BeamConfig.Beam.Metrics(
          level = if (c.hasPathOrNull("level")) c.getString("level") else "verbose"
        )
      }
    }

    case class Outputs(
      addTimestampToOutputDirectory: scala.Boolean,
      baseOutputDirectory: java.lang.String,
      collectAndCreateBeamAnalysisAndGraphs: scala.Boolean,
      defaultWriteInterval: scala.Int,
      displayPerformanceTimings: scala.Boolean,
      events: BeamConfig.Beam.Outputs.Events,
      generalizedLinkStats: BeamConfig.Beam.Outputs.GeneralizedLinkStats,
      generalizedLinkStatsInterval: scala.Int,
      matsim: BeamConfig.Beam.Outputs.Matsim,
      stats: BeamConfig.Beam.Outputs.Stats,
      writeAnalysis: scala.Boolean,
      writeEventsInterval: scala.Int,
      writeGraphs: scala.Boolean,
      writeLinkTraversalInterval: scala.Int,
      writePlansInterval: scala.Int,
      writeR5RoutesInterval: scala.Int
    )

    object Outputs {

      case class Events(
        eventsToWrite: java.lang.String,
        fileOutputFormats: java.lang.String
      )

      object Events {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Outputs.Events = {
          BeamConfig.Beam.Outputs.Events(
            eventsToWrite =
              if (c.hasPathOrNull("eventsToWrite")) c.getString("eventsToWrite")
              else
                "ActivityEndEvent,ActivityStartEvent,PersonEntersVehicleEvent,PersonLeavesVehicleEvent,ModeChoiceEvent,PathTraversalEvent,ReserveRideHailEvent,ReplanningEvent,RefuelSessionEvent,ChargingPlugInEvent,ChargingPlugOutEvent,ParkingEvent,LeavingParkingEvent",
            fileOutputFormats = if (c.hasPathOrNull("fileOutputFormats")) c.getString("fileOutputFormats") else "csv"
          )
        }
      }

      case class GeneralizedLinkStats(
        endTime: scala.Int,
        startTime: scala.Int
      )

      object GeneralizedLinkStats {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Outputs.GeneralizedLinkStats = {
          BeamConfig.Beam.Outputs.GeneralizedLinkStats(
            endTime = if (c.hasPathOrNull("endTime")) c.getInt("endTime") else 32400,
            startTime = if (c.hasPathOrNull("startTime")) c.getInt("startTime") else 25200
          )
        }
      }

      case class Matsim(
        deleteITERSFolderFiles: java.lang.String,
        deleteRootFolderFiles: java.lang.String
      )

      object Matsim {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Outputs.Matsim = {
          BeamConfig.Beam.Outputs.Matsim(
            deleteITERSFolderFiles =
              if (c.hasPathOrNull("deleteITERSFolderFiles")) c.getString("deleteITERSFolderFiles") else "",
            deleteRootFolderFiles =
              if (c.hasPathOrNull("deleteRootFolderFiles")) c.getString("deleteRootFolderFiles") else ""
          )
        }
      }

      case class Stats(
        binSize: scala.Int
      )

      object Stats {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Outputs.Stats = {
          BeamConfig.Beam.Outputs.Stats(
            binSize = if (c.hasPathOrNull("binSize")) c.getInt("binSize") else 3600
          )
        }
      }

      def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Outputs = {
        BeamConfig.Beam.Outputs(
          addTimestampToOutputDirectory =
            !c.hasPathOrNull("addTimestampToOutputDirectory") || c.getBoolean("addTimestampToOutputDirectory"),
          baseOutputDirectory =
            if (c.hasPathOrNull("baseOutputDirectory")) c.getString("baseOutputDirectory") else "output",
          collectAndCreateBeamAnalysisAndGraphs =
            !c.hasPathOrNull("collectAndCreateBeamAnalysisAndGraphs") || c.getBoolean(
              "collectAndCreateBeamAnalysisAndGraphs"
            ),
          defaultWriteInterval = if (c.hasPathOrNull("defaultWriteInterval")) c.getInt("defaultWriteInterval") else 1,
          displayPerformanceTimings =
            c.hasPathOrNull("displayPerformanceTimings") && c.getBoolean("displayPerformanceTimings"),
          events = BeamConfig.Beam.Outputs.Events(
            if (c.hasPathOrNull("events")) c.getConfig("events")
            else com.typesafe.config.ConfigFactory.parseString("events{}")
          ),
          generalizedLinkStats = BeamConfig.Beam.Outputs.GeneralizedLinkStats(
            if (c.hasPathOrNull("generalizedLinkStats")) c.getConfig("generalizedLinkStats")
            else com.typesafe.config.ConfigFactory.parseString("generalizedLinkStats{}")
          ),
          generalizedLinkStatsInterval =
            if (c.hasPathOrNull("generalizedLinkStatsInterval")) c.getInt("generalizedLinkStatsInterval") else 0,
          matsim = BeamConfig.Beam.Outputs.Matsim(
            if (c.hasPathOrNull("matsim")) c.getConfig("matsim")
            else com.typesafe.config.ConfigFactory.parseString("matsim{}")
          ),
          stats = BeamConfig.Beam.Outputs.Stats(
            if (c.hasPathOrNull("stats")) c.getConfig("stats")
            else com.typesafe.config.ConfigFactory.parseString("stats{}")
          ),
          writeAnalysis = !c.hasPathOrNull("writeAnalysis") || c.getBoolean("writeAnalysis"),
          writeEventsInterval = if (c.hasPathOrNull("writeEventsInterval")) c.getInt("writeEventsInterval") else 1,
          writeGraphs = !c.hasPathOrNull("writeGraphs") || c.getBoolean("writeGraphs"),
          writeLinkTraversalInterval =
            if (c.hasPathOrNull("writeLinkTraversalInterval")) c.getInt("writeLinkTraversalInterval") else 0,
          writePlansInterval = if (c.hasPathOrNull("writePlansInterval")) c.getInt("writePlansInterval") else 0,
          writeR5RoutesInterval = if (c.hasPathOrNull("writeR5RoutesInterval")) c.getInt("writeR5RoutesInterval") else 0
        )
      }
    }

    case class Physsim(
      bprsim: BeamConfig.Beam.Physsim.Bprsim,
      cchRoutingAssignment: BeamConfig.Beam.Physsim.CchRoutingAssignment,
      events: BeamConfig.Beam.Physsim.Events,
      eventsForFullVersionOfVia: scala.Boolean,
      eventsSampling: scala.Double,
      flowCapacityFactor: scala.Double,
      initializeRouterWithFreeFlowTimes: scala.Boolean,
      inputNetworkFilePath: java.lang.String,
      jdeqsim: BeamConfig.Beam.Physsim.Jdeqsim,
      linkStatsBinSize: scala.Int,
      linkStatsWriteInterval: scala.Int,
      name: java.lang.String,
      network: BeamConfig.Beam.Physsim.Network,
      overwriteLinkParamPath: java.lang.String,
      parbprsim: BeamConfig.Beam.Physsim.Parbprsim,
      ptSampleSize: scala.Double,
      quick_fix_minCarSpeedInMetersPerSecond: scala.Double,
      relaxation: BeamConfig.Beam.Physsim.Relaxation,
      skipPhysSim: scala.Boolean,
      speedScalingFactor: scala.Double,
      storageCapacityFactor: scala.Double,
      writeEventsInterval: scala.Int,
      writeMATSimNetwork: scala.Boolean,
      writePlansInterval: scala.Int,
      writeRouteHistoryInterval: scala.Int
    )

    object Physsim {

      case class Bprsim(
        inFlowAggregationTimeWindowInSeconds: scala.Int,
        minFlowToUseBPRFunction: scala.Int,
        travelTimeFunction: java.lang.String
      )

      object Bprsim {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Physsim.Bprsim = {
          BeamConfig.Beam.Physsim.Bprsim(
            inFlowAggregationTimeWindowInSeconds =
              if (c.hasPathOrNull("inFlowAggregationTimeWindowInSeconds"))
                c.getInt("inFlowAggregationTimeWindowInSeconds")
              else 900,
            minFlowToUseBPRFunction =
              if (c.hasPathOrNull("minFlowToUseBPRFunction")) c.getInt("minFlowToUseBPRFunction") else 0,
            travelTimeFunction = if (c.hasPathOrNull("travelTimeFunction")) c.getString("travelTimeFunction") else "BPR"
          )
        }
      }

      case class CchRoutingAssignment(
        congestionFactor: scala.Double
      )

      object CchRoutingAssignment {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Physsim.CchRoutingAssignment = {
          BeamConfig.Beam.Physsim.CchRoutingAssignment(
            congestionFactor = if (c.hasPathOrNull("congestionFactor")) c.getDouble("congestionFactor") else 1.0
          )
        }
      }

      case class Events(
        eventsToWrite: java.lang.String,
        fileOutputFormats: java.lang.String
      )

      object Events {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Physsim.Events = {
          BeamConfig.Beam.Physsim.Events(
            eventsToWrite =
              if (c.hasPathOrNull("eventsToWrite")) c.getString("eventsToWrite")
              else
                "ActivityEndEvent,ActivityStartEvent,LinkEnterEvent,LinkLeaveEvent,PersonArrivalEvent,PersonDepartureEvent,VehicleEntersTrafficEvent,VehicleLeavesTrafficEvent",
            fileOutputFormats = if (c.hasPathOrNull("fileOutputFormats")) c.getString("fileOutputFormats") else "csv"
          )
        }
      }

      case class Jdeqsim(
        agentSimPhysSimInterfaceDebugger: BeamConfig.Beam.Physsim.Jdeqsim.AgentSimPhysSimInterfaceDebugger,
        cacc: BeamConfig.Beam.Physsim.Jdeqsim.Cacc
      )

      object Jdeqsim {

        case class AgentSimPhysSimInterfaceDebugger(
          enabled: scala.Boolean
        )

        object AgentSimPhysSimInterfaceDebugger {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Physsim.Jdeqsim.AgentSimPhysSimInterfaceDebugger = {
            BeamConfig.Beam.Physsim.Jdeqsim.AgentSimPhysSimInterfaceDebugger(
              enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled")
            )
          }
        }

        case class Cacc(
          adjustedMinimumRoadSpeedInMetersPerSecond: scala.Double,
          capacityPlansWriteInterval: scala.Int,
          enabled: scala.Boolean,
          minRoadCapacity: scala.Int,
          minSpeedMetersPerSec: scala.Int,
          speedAdjustmentFactor: scala.Double
        )

        object Cacc {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Physsim.Jdeqsim.Cacc = {
            BeamConfig.Beam.Physsim.Jdeqsim.Cacc(
              adjustedMinimumRoadSpeedInMetersPerSecond =
                if (c.hasPathOrNull("adjustedMinimumRoadSpeedInMetersPerSecond"))
                  c.getDouble("adjustedMinimumRoadSpeedInMetersPerSecond")
                else 1.3,
              capacityPlansWriteInterval =
                if (c.hasPathOrNull("capacityPlansWriteInterval")) c.getInt("capacityPlansWriteInterval") else 0,
              enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled"),
              minRoadCapacity = if (c.hasPathOrNull("minRoadCapacity")) c.getInt("minRoadCapacity") else 2000,
              minSpeedMetersPerSec =
                if (c.hasPathOrNull("minSpeedMetersPerSec")) c.getInt("minSpeedMetersPerSec") else 20,
              speedAdjustmentFactor =
                if (c.hasPathOrNull("speedAdjustmentFactor")) c.getDouble("speedAdjustmentFactor") else 1.0
            )
          }
        }

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Physsim.Jdeqsim = {
          BeamConfig.Beam.Physsim.Jdeqsim(
            agentSimPhysSimInterfaceDebugger = BeamConfig.Beam.Physsim.Jdeqsim.AgentSimPhysSimInterfaceDebugger(
              if (c.hasPathOrNull("agentSimPhysSimInterfaceDebugger")) c.getConfig("agentSimPhysSimInterfaceDebugger")
              else com.typesafe.config.ConfigFactory.parseString("agentSimPhysSimInterfaceDebugger{}")
            ),
            cacc = BeamConfig.Beam.Physsim.Jdeqsim.Cacc(
              if (c.hasPathOrNull("cacc")) c.getConfig("cacc")
              else com.typesafe.config.ConfigFactory.parseString("cacc{}")
            )
          )
        }
      }

      case class Network(
        maxSpeedInference: BeamConfig.Beam.Physsim.Network.MaxSpeedInference,
        overwriteRoadTypeProperties: BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties
      )

      object Network {

        case class MaxSpeedInference(
          enabled: scala.Boolean,
          `type`: java.lang.String
        )

        object MaxSpeedInference {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Physsim.Network.MaxSpeedInference = {
            BeamConfig.Beam.Physsim.Network.MaxSpeedInference(
              enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled"),
              `type` = if (c.hasPathOrNull("type")) c.getString("type") else "MEAN"
            )
          }
        }

        case class OverwriteRoadTypeProperties(
          enabled: scala.Boolean,
          livingStreet: BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.LivingStreet,
          minor: BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Minor,
          motorway: BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Motorway,
          motorwayLink: BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.MotorwayLink,
          primary: BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Primary,
          primaryLink: BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.PrimaryLink,
          residential: BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Residential,
          secondary: BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Secondary,
          secondaryLink: BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.SecondaryLink,
          tertiary: BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Tertiary,
          tertiaryLink: BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.TertiaryLink,
          trunk: BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Trunk,
          trunkLink: BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.TrunkLink,
          unclassified: BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Unclassified
        )

        object OverwriteRoadTypeProperties {

          case class LivingStreet(
            capacity: scala.Option[scala.Int],
            lanes: scala.Option[scala.Int],
            speed: scala.Option[scala.Double]
          )

          object LivingStreet {

            def apply(
              c: com.typesafe.config.Config
            ): BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.LivingStreet = {
              BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.LivingStreet(
                capacity = if (c.hasPathOrNull("capacity")) Some(c.getInt("capacity")) else None,
                lanes = if (c.hasPathOrNull("lanes")) Some(c.getInt("lanes")) else None,
                speed = if (c.hasPathOrNull("speed")) Some(c.getDouble("speed")) else None
              )
            }
          }

          case class Minor(
            capacity: scala.Option[scala.Int],
            lanes: scala.Option[scala.Int],
            speed: scala.Option[scala.Double]
          )

          object Minor {

            def apply(
              c: com.typesafe.config.Config
            ): BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Minor = {
              BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Minor(
                capacity = if (c.hasPathOrNull("capacity")) Some(c.getInt("capacity")) else None,
                lanes = if (c.hasPathOrNull("lanes")) Some(c.getInt("lanes")) else None,
                speed = if (c.hasPathOrNull("speed")) Some(c.getDouble("speed")) else None
              )
            }
          }

          case class Motorway(
            capacity: scala.Option[scala.Int],
            lanes: scala.Option[scala.Int],
            speed: scala.Option[scala.Double]
          )

          object Motorway {

            def apply(
              c: com.typesafe.config.Config
            ): BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Motorway = {
              BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Motorway(
                capacity = if (c.hasPathOrNull("capacity")) Some(c.getInt("capacity")) else None,
                lanes = if (c.hasPathOrNull("lanes")) Some(c.getInt("lanes")) else None,
                speed = if (c.hasPathOrNull("speed")) Some(c.getDouble("speed")) else None
              )
            }
          }

          case class MotorwayLink(
            capacity: scala.Option[scala.Int],
            lanes: scala.Option[scala.Int],
            speed: scala.Option[scala.Double]
          )

          object MotorwayLink {

            def apply(
              c: com.typesafe.config.Config
            ): BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.MotorwayLink = {
              BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.MotorwayLink(
                capacity = if (c.hasPathOrNull("capacity")) Some(c.getInt("capacity")) else None,
                lanes = if (c.hasPathOrNull("lanes")) Some(c.getInt("lanes")) else None,
                speed = if (c.hasPathOrNull("speed")) Some(c.getDouble("speed")) else None
              )
            }
          }

          case class Primary(
            capacity: scala.Option[scala.Int],
            lanes: scala.Option[scala.Int],
            speed: scala.Option[scala.Double]
          )

          object Primary {

            def apply(
              c: com.typesafe.config.Config
            ): BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Primary = {
              BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Primary(
                capacity = if (c.hasPathOrNull("capacity")) Some(c.getInt("capacity")) else None,
                lanes = if (c.hasPathOrNull("lanes")) Some(c.getInt("lanes")) else None,
                speed = if (c.hasPathOrNull("speed")) Some(c.getDouble("speed")) else None
              )
            }
          }

          case class PrimaryLink(
            capacity: scala.Option[scala.Int],
            lanes: scala.Option[scala.Int],
            speed: scala.Option[scala.Double]
          )

          object PrimaryLink {

            def apply(
              c: com.typesafe.config.Config
            ): BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.PrimaryLink = {
              BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.PrimaryLink(
                capacity = if (c.hasPathOrNull("capacity")) Some(c.getInt("capacity")) else None,
                lanes = if (c.hasPathOrNull("lanes")) Some(c.getInt("lanes")) else None,
                speed = if (c.hasPathOrNull("speed")) Some(c.getDouble("speed")) else None
              )
            }
          }

          case class Residential(
            capacity: scala.Option[scala.Int],
            lanes: scala.Option[scala.Int],
            speed: scala.Option[scala.Double]
          )

          object Residential {

            def apply(
              c: com.typesafe.config.Config
            ): BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Residential = {
              BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Residential(
                capacity = if (c.hasPathOrNull("capacity")) Some(c.getInt("capacity")) else None,
                lanes = if (c.hasPathOrNull("lanes")) Some(c.getInt("lanes")) else None,
                speed = if (c.hasPathOrNull("speed")) Some(c.getDouble("speed")) else None
              )
            }
          }

          case class Secondary(
            capacity: scala.Option[scala.Int],
            lanes: scala.Option[scala.Int],
            speed: scala.Option[scala.Double]
          )

          object Secondary {

            def apply(
              c: com.typesafe.config.Config
            ): BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Secondary = {
              BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Secondary(
                capacity = if (c.hasPathOrNull("capacity")) Some(c.getInt("capacity")) else None,
                lanes = if (c.hasPathOrNull("lanes")) Some(c.getInt("lanes")) else None,
                speed = if (c.hasPathOrNull("speed")) Some(c.getDouble("speed")) else None
              )
            }
          }

          case class SecondaryLink(
            capacity: scala.Option[scala.Int],
            lanes: scala.Option[scala.Int],
            speed: scala.Option[scala.Double]
          )

          object SecondaryLink {

            def apply(
              c: com.typesafe.config.Config
            ): BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.SecondaryLink = {
              BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.SecondaryLink(
                capacity = if (c.hasPathOrNull("capacity")) Some(c.getInt("capacity")) else None,
                lanes = if (c.hasPathOrNull("lanes")) Some(c.getInt("lanes")) else None,
                speed = if (c.hasPathOrNull("speed")) Some(c.getDouble("speed")) else None
              )
            }
          }

          case class Tertiary(
            capacity: scala.Option[scala.Int],
            lanes: scala.Option[scala.Int],
            speed: scala.Option[scala.Double]
          )

          object Tertiary {

            def apply(
              c: com.typesafe.config.Config
            ): BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Tertiary = {
              BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Tertiary(
                capacity = if (c.hasPathOrNull("capacity")) Some(c.getInt("capacity")) else None,
                lanes = if (c.hasPathOrNull("lanes")) Some(c.getInt("lanes")) else None,
                speed = if (c.hasPathOrNull("speed")) Some(c.getDouble("speed")) else None
              )
            }
          }

          case class TertiaryLink(
            capacity: scala.Option[scala.Int],
            lanes: scala.Option[scala.Int],
            speed: scala.Option[scala.Double]
          )

          object TertiaryLink {

            def apply(
              c: com.typesafe.config.Config
            ): BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.TertiaryLink = {
              BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.TertiaryLink(
                capacity = if (c.hasPathOrNull("capacity")) Some(c.getInt("capacity")) else None,
                lanes = if (c.hasPathOrNull("lanes")) Some(c.getInt("lanes")) else None,
                speed = if (c.hasPathOrNull("speed")) Some(c.getDouble("speed")) else None
              )
            }
          }

          case class Trunk(
            capacity: scala.Option[scala.Int],
            lanes: scala.Option[scala.Int],
            speed: scala.Option[scala.Double]
          )

          object Trunk {

            def apply(
              c: com.typesafe.config.Config
            ): BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Trunk = {
              BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Trunk(
                capacity = if (c.hasPathOrNull("capacity")) Some(c.getInt("capacity")) else None,
                lanes = if (c.hasPathOrNull("lanes")) Some(c.getInt("lanes")) else None,
                speed = if (c.hasPathOrNull("speed")) Some(c.getDouble("speed")) else None
              )
            }
          }

          case class TrunkLink(
            capacity: scala.Option[scala.Int],
            lanes: scala.Option[scala.Int],
            speed: scala.Option[scala.Double]
          )

          object TrunkLink {

            def apply(
              c: com.typesafe.config.Config
            ): BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.TrunkLink = {
              BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.TrunkLink(
                capacity = if (c.hasPathOrNull("capacity")) Some(c.getInt("capacity")) else None,
                lanes = if (c.hasPathOrNull("lanes")) Some(c.getInt("lanes")) else None,
                speed = if (c.hasPathOrNull("speed")) Some(c.getDouble("speed")) else None
              )
            }
          }

          case class Unclassified(
            capacity: scala.Option[scala.Int],
            lanes: scala.Option[scala.Int],
            speed: scala.Option[scala.Double]
          )

          object Unclassified {

            def apply(
              c: com.typesafe.config.Config
            ): BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Unclassified = {
              BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Unclassified(
                capacity = if (c.hasPathOrNull("capacity")) Some(c.getInt("capacity")) else None,
                lanes = if (c.hasPathOrNull("lanes")) Some(c.getInt("lanes")) else None,
                speed = if (c.hasPathOrNull("speed")) Some(c.getDouble("speed")) else None
              )
            }
          }

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties = {
            BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties(
              enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled"),
              livingStreet = BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.LivingStreet(
                if (c.hasPathOrNull("livingStreet")) c.getConfig("livingStreet")
                else com.typesafe.config.ConfigFactory.parseString("livingStreet{}")
              ),
              minor = BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Minor(
                if (c.hasPathOrNull("minor")) c.getConfig("minor")
                else com.typesafe.config.ConfigFactory.parseString("minor{}")
              ),
              motorway = BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Motorway(
                if (c.hasPathOrNull("motorway")) c.getConfig("motorway")
                else com.typesafe.config.ConfigFactory.parseString("motorway{}")
              ),
              motorwayLink = BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.MotorwayLink(
                if (c.hasPathOrNull("motorwayLink")) c.getConfig("motorwayLink")
                else com.typesafe.config.ConfigFactory.parseString("motorwayLink{}")
              ),
              primary = BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Primary(
                if (c.hasPathOrNull("primary")) c.getConfig("primary")
                else com.typesafe.config.ConfigFactory.parseString("primary{}")
              ),
              primaryLink = BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.PrimaryLink(
                if (c.hasPathOrNull("primaryLink")) c.getConfig("primaryLink")
                else com.typesafe.config.ConfigFactory.parseString("primaryLink{}")
              ),
              residential = BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Residential(
                if (c.hasPathOrNull("residential")) c.getConfig("residential")
                else com.typesafe.config.ConfigFactory.parseString("residential{}")
              ),
              secondary = BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Secondary(
                if (c.hasPathOrNull("secondary")) c.getConfig("secondary")
                else com.typesafe.config.ConfigFactory.parseString("secondary{}")
              ),
              secondaryLink = BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.SecondaryLink(
                if (c.hasPathOrNull("secondaryLink")) c.getConfig("secondaryLink")
                else com.typesafe.config.ConfigFactory.parseString("secondaryLink{}")
              ),
              tertiary = BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Tertiary(
                if (c.hasPathOrNull("tertiary")) c.getConfig("tertiary")
                else com.typesafe.config.ConfigFactory.parseString("tertiary{}")
              ),
              tertiaryLink = BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.TertiaryLink(
                if (c.hasPathOrNull("tertiaryLink")) c.getConfig("tertiaryLink")
                else com.typesafe.config.ConfigFactory.parseString("tertiaryLink{}")
              ),
              trunk = BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Trunk(
                if (c.hasPathOrNull("trunk")) c.getConfig("trunk")
                else com.typesafe.config.ConfigFactory.parseString("trunk{}")
              ),
              trunkLink = BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.TrunkLink(
                if (c.hasPathOrNull("trunkLink")) c.getConfig("trunkLink")
                else com.typesafe.config.ConfigFactory.parseString("trunkLink{}")
              ),
              unclassified = BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties.Unclassified(
                if (c.hasPathOrNull("unclassified")) c.getConfig("unclassified")
                else com.typesafe.config.ConfigFactory.parseString("unclassified{}")
              )
            )
          }
        }

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Physsim.Network = {
          BeamConfig.Beam.Physsim.Network(
            maxSpeedInference = BeamConfig.Beam.Physsim.Network.MaxSpeedInference(
              if (c.hasPathOrNull("maxSpeedInference")) c.getConfig("maxSpeedInference")
              else com.typesafe.config.ConfigFactory.parseString("maxSpeedInference{}")
            ),
            overwriteRoadTypeProperties = BeamConfig.Beam.Physsim.Network.OverwriteRoadTypeProperties(
              if (c.hasPathOrNull("overwriteRoadTypeProperties")) c.getConfig("overwriteRoadTypeProperties")
              else com.typesafe.config.ConfigFactory.parseString("overwriteRoadTypeProperties{}")
            )
          )
        }
      }

      case class Parbprsim(
        numberOfClusters: scala.Int,
        syncInterval: scala.Int
      )

      object Parbprsim {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Physsim.Parbprsim = {
          BeamConfig.Beam.Physsim.Parbprsim(
            numberOfClusters = if (c.hasPathOrNull("numberOfClusters")) c.getInt("numberOfClusters") else 8,
            syncInterval = if (c.hasPathOrNull("syncInterval")) c.getInt("syncInterval") else 60
          )
        }
      }

      case class Relaxation(
        experiment2_0: BeamConfig.Beam.Physsim.Relaxation.Experiment20,
        experiment2_1: BeamConfig.Beam.Physsim.Relaxation.Experiment21,
        experiment3_0: BeamConfig.Beam.Physsim.Relaxation.Experiment30,
        experiment4_0: BeamConfig.Beam.Physsim.Relaxation.Experiment40,
        experiment5_0: BeamConfig.Beam.Physsim.Relaxation.Experiment50,
        experiment5_1: BeamConfig.Beam.Physsim.Relaxation.Experiment51,
        experiment5_2: BeamConfig.Beam.Physsim.Relaxation.Experiment52,
        `type`: java.lang.String
      )

      object Relaxation {

        case class Experiment20(
          clearModesEveryIteration: scala.Boolean,
          clearRoutesEveryIteration: scala.Boolean,
          fractionOfPopulationToReroute: scala.Double,
          internalNumberOfIterations: scala.Int
        )

        object Experiment20 {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Physsim.Relaxation.Experiment20 = {
            BeamConfig.Beam.Physsim.Relaxation.Experiment20(
              clearModesEveryIteration =
                !c.hasPathOrNull("clearModesEveryIteration") || c.getBoolean("clearModesEveryIteration"),
              clearRoutesEveryIteration =
                !c.hasPathOrNull("clearRoutesEveryIteration") || c.getBoolean("clearRoutesEveryIteration"),
              fractionOfPopulationToReroute =
                if (c.hasPathOrNull("fractionOfPopulationToReroute")) c.getDouble("fractionOfPopulationToReroute")
                else 0.1,
              internalNumberOfIterations =
                if (c.hasPathOrNull("internalNumberOfIterations")) c.getInt("internalNumberOfIterations") else 15
            )
          }
        }

        case class Experiment21(
          clearModesEveryIteration: scala.Boolean,
          clearRoutesEveryIteration: scala.Boolean,
          fractionOfPopulationToReroute: scala.Double,
          internalNumberOfIterations: scala.Int
        )

        object Experiment21 {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Physsim.Relaxation.Experiment21 = {
            BeamConfig.Beam.Physsim.Relaxation.Experiment21(
              clearModesEveryIteration =
                !c.hasPathOrNull("clearModesEveryIteration") || c.getBoolean("clearModesEveryIteration"),
              clearRoutesEveryIteration =
                !c.hasPathOrNull("clearRoutesEveryIteration") || c.getBoolean("clearRoutesEveryIteration"),
              fractionOfPopulationToReroute =
                if (c.hasPathOrNull("fractionOfPopulationToReroute")) c.getDouble("fractionOfPopulationToReroute")
                else 0.1,
              internalNumberOfIterations =
                if (c.hasPathOrNull("internalNumberOfIterations")) c.getInt("internalNumberOfIterations") else 15
            )
          }
        }

        case class Experiment30(
          fractionOfPopulationToReroute: scala.Double,
          internalNumberOfIterations: scala.Int
        )

        object Experiment30 {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Physsim.Relaxation.Experiment30 = {
            BeamConfig.Beam.Physsim.Relaxation.Experiment30(
              fractionOfPopulationToReroute =
                if (c.hasPathOrNull("fractionOfPopulationToReroute")) c.getDouble("fractionOfPopulationToReroute")
                else 0.1,
              internalNumberOfIterations =
                if (c.hasPathOrNull("internalNumberOfIterations")) c.getInt("internalNumberOfIterations") else 15
            )
          }
        }

        case class Experiment40(
          percentToSimulate: scala.Option[scala.List[scala.Double]]
        )

        object Experiment40 {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Physsim.Relaxation.Experiment40 = {
            BeamConfig.Beam.Physsim.Relaxation.Experiment40(
              percentToSimulate =
                if (c.hasPathOrNull("percentToSimulate")) scala.Some($_L$_dbl(c.getList("percentToSimulate"))) else None
            )
          }
        }

        case class Experiment50(
          percentToSimulate: scala.Option[scala.List[scala.Double]]
        )

        object Experiment50 {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Physsim.Relaxation.Experiment50 = {
            BeamConfig.Beam.Physsim.Relaxation.Experiment50(
              percentToSimulate =
                if (c.hasPathOrNull("percentToSimulate")) scala.Some($_L$_dbl(c.getList("percentToSimulate"))) else None
            )
          }
        }

        case class Experiment51(
          percentToSimulate: scala.Option[scala.List[scala.Double]]
        )

        object Experiment51 {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Physsim.Relaxation.Experiment51 = {
            BeamConfig.Beam.Physsim.Relaxation.Experiment51(
              percentToSimulate =
                if (c.hasPathOrNull("percentToSimulate")) scala.Some($_L$_dbl(c.getList("percentToSimulate"))) else None
            )
          }
        }

        case class Experiment52(
          percentToSimulate: scala.Option[scala.List[scala.Double]]
        )

        object Experiment52 {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Physsim.Relaxation.Experiment52 = {
            BeamConfig.Beam.Physsim.Relaxation.Experiment52(
              percentToSimulate =
                if (c.hasPathOrNull("percentToSimulate")) scala.Some($_L$_dbl(c.getList("percentToSimulate"))) else None
            )
          }
        }

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Physsim.Relaxation = {
          BeamConfig.Beam.Physsim.Relaxation(
            experiment2_0 = BeamConfig.Beam.Physsim.Relaxation.Experiment20(
              if (c.hasPathOrNull("experiment2_0")) c.getConfig("experiment2_0")
              else com.typesafe.config.ConfigFactory.parseString("experiment2_0{}")
            ),
            experiment2_1 = BeamConfig.Beam.Physsim.Relaxation.Experiment21(
              if (c.hasPathOrNull("experiment2_1")) c.getConfig("experiment2_1")
              else com.typesafe.config.ConfigFactory.parseString("experiment2_1{}")
            ),
            experiment3_0 = BeamConfig.Beam.Physsim.Relaxation.Experiment30(
              if (c.hasPathOrNull("experiment3_0")) c.getConfig("experiment3_0")
              else com.typesafe.config.ConfigFactory.parseString("experiment3_0{}")
            ),
            experiment4_0 = BeamConfig.Beam.Physsim.Relaxation.Experiment40(
              if (c.hasPathOrNull("experiment4_0")) c.getConfig("experiment4_0")
              else com.typesafe.config.ConfigFactory.parseString("experiment4_0{}")
            ),
            experiment5_0 = BeamConfig.Beam.Physsim.Relaxation.Experiment50(
              if (c.hasPathOrNull("experiment5_0")) c.getConfig("experiment5_0")
              else com.typesafe.config.ConfigFactory.parseString("experiment5_0{}")
            ),
            experiment5_1 = BeamConfig.Beam.Physsim.Relaxation.Experiment51(
              if (c.hasPathOrNull("experiment5_1")) c.getConfig("experiment5_1")
              else com.typesafe.config.ConfigFactory.parseString("experiment5_1{}")
            ),
            experiment5_2 = BeamConfig.Beam.Physsim.Relaxation.Experiment52(
              if (c.hasPathOrNull("experiment5_2")) c.getConfig("experiment5_2")
              else com.typesafe.config.ConfigFactory.parseString("experiment5_2{}")
            ),
            `type` = if (c.hasPathOrNull("type")) c.getString("type") else "normal"
          )
        }
      }

      def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Physsim = {
        BeamConfig.Beam.Physsim(
          bprsim = BeamConfig.Beam.Physsim.Bprsim(
            if (c.hasPathOrNull("bprsim")) c.getConfig("bprsim")
            else com.typesafe.config.ConfigFactory.parseString("bprsim{}")
          ),
          cchRoutingAssignment = BeamConfig.Beam.Physsim.CchRoutingAssignment(
            if (c.hasPathOrNull("cchRoutingAssignment")) c.getConfig("cchRoutingAssignment")
            else com.typesafe.config.ConfigFactory.parseString("cchRoutingAssignment{}")
          ),
          events = BeamConfig.Beam.Physsim.Events(
            if (c.hasPathOrNull("events")) c.getConfig("events")
            else com.typesafe.config.ConfigFactory.parseString("events{}")
          ),
          eventsForFullVersionOfVia =
            !c.hasPathOrNull("eventsForFullVersionOfVia") || c.getBoolean("eventsForFullVersionOfVia"),
          eventsSampling = if (c.hasPathOrNull("eventsSampling")) c.getDouble("eventsSampling") else 1.0,
          flowCapacityFactor = if (c.hasPathOrNull("flowCapacityFactor")) c.getDouble("flowCapacityFactor") else 1.0,
          initializeRouterWithFreeFlowTimes =
            !c.hasPathOrNull("initializeRouterWithFreeFlowTimes") || c.getBoolean("initializeRouterWithFreeFlowTimes"),
          inputNetworkFilePath =
            if (c.hasPathOrNull("inputNetworkFilePath")) c.getString("inputNetworkFilePath")
            else "/test/input/beamville/r5/physsim-network.xml",
          jdeqsim = BeamConfig.Beam.Physsim.Jdeqsim(
            if (c.hasPathOrNull("jdeqsim")) c.getConfig("jdeqsim")
            else com.typesafe.config.ConfigFactory.parseString("jdeqsim{}")
          ),
          linkStatsBinSize = if (c.hasPathOrNull("linkStatsBinSize")) c.getInt("linkStatsBinSize") else 3600,
          linkStatsWriteInterval =
            if (c.hasPathOrNull("linkStatsWriteInterval")) c.getInt("linkStatsWriteInterval") else 0,
          name = if (c.hasPathOrNull("name")) c.getString("name") else "JDEQSim",
          network = BeamConfig.Beam.Physsim.Network(
            if (c.hasPathOrNull("network")) c.getConfig("network")
            else com.typesafe.config.ConfigFactory.parseString("network{}")
          ),
          overwriteLinkParamPath =
            if (c.hasPathOrNull("overwriteLinkParamPath")) c.getString("overwriteLinkParamPath") else "",
          parbprsim = BeamConfig.Beam.Physsim.Parbprsim(
            if (c.hasPathOrNull("parbprsim")) c.getConfig("parbprsim")
            else com.typesafe.config.ConfigFactory.parseString("parbprsim{}")
          ),
          ptSampleSize = if (c.hasPathOrNull("ptSampleSize")) c.getDouble("ptSampleSize") else 1.0,
          quick_fix_minCarSpeedInMetersPerSecond =
            if (c.hasPathOrNull("quick_fix_minCarSpeedInMetersPerSecond"))
              c.getDouble("quick_fix_minCarSpeedInMetersPerSecond")
            else 0.5,
          relaxation = BeamConfig.Beam.Physsim.Relaxation(
            if (c.hasPathOrNull("relaxation")) c.getConfig("relaxation")
            else com.typesafe.config.ConfigFactory.parseString("relaxation{}")
          ),
          skipPhysSim = c.hasPathOrNull("skipPhysSim") && c.getBoolean("skipPhysSim"),
          speedScalingFactor = if (c.hasPathOrNull("speedScalingFactor")) c.getDouble("speedScalingFactor") else 1.0,
          storageCapacityFactor =
            if (c.hasPathOrNull("storageCapacityFactor")) c.getDouble("storageCapacityFactor") else 1.0,
          writeEventsInterval = if (c.hasPathOrNull("writeEventsInterval")) c.getInt("writeEventsInterval") else 0,
          writeMATSimNetwork = !c.hasPathOrNull("writeMATSimNetwork") || c.getBoolean("writeMATSimNetwork"),
          writePlansInterval = if (c.hasPathOrNull("writePlansInterval")) c.getInt("writePlansInterval") else 0,
          writeRouteHistoryInterval =
            if (c.hasPathOrNull("writeRouteHistoryInterval")) c.getInt("writeRouteHistoryInterval") else 10
        )
      }
    }

    case class Replanning(
      ModuleProbability_1: scala.Double,
      ModuleProbability_2: scala.Double,
      ModuleProbability_3: scala.Double,
      ModuleProbability_4: scala.Int,
      Module_1: java.lang.String,
      Module_2: java.lang.String,
      Module_3: java.lang.String,
      Module_4: java.lang.String,
      clearModes: BeamConfig.Beam.Replanning.ClearModes,
      fractionOfIterationsToDisableInnovation: scala.Double,
      maxAgentPlanMemorySize: scala.Int
    )

    object Replanning {

      case class ClearModes(
        iteration: scala.Int,
        modes: scala.Option[scala.List[java.lang.String]],
        strategy: java.lang.String
      )

      object ClearModes {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Replanning.ClearModes = {
          BeamConfig.Beam.Replanning.ClearModes(
            iteration = if (c.hasPathOrNull("iteration")) c.getInt("iteration") else 0,
            modes = if (c.hasPathOrNull("modes")) scala.Some($_L$_str(c.getList("modes"))) else None,
            strategy = if (c.hasPathOrNull("strategy")) c.getString("strategy") else "AtBeginningOfIteration"
          )
        }
      }

      def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Replanning = {
        BeamConfig.Beam.Replanning(
          ModuleProbability_1 = if (c.hasPathOrNull("ModuleProbability_1")) c.getDouble("ModuleProbability_1") else 0.8,
          ModuleProbability_2 = if (c.hasPathOrNull("ModuleProbability_2")) c.getDouble("ModuleProbability_2") else 0.1,
          ModuleProbability_3 = if (c.hasPathOrNull("ModuleProbability_3")) c.getDouble("ModuleProbability_3") else 0.1,
          ModuleProbability_4 = if (c.hasPathOrNull("ModuleProbability_4")) c.getInt("ModuleProbability_4") else 0,
          Module_1 = if (c.hasPathOrNull("Module_1")) c.getString("Module_1") else "SelectExpBeta",
          Module_2 = if (c.hasPathOrNull("Module_2")) c.getString("Module_2") else "ClearRoutes",
          Module_3 = if (c.hasPathOrNull("Module_3")) c.getString("Module_3") else "ClearModes",
          Module_4 = if (c.hasPathOrNull("Module_4")) c.getString("Module_4") else "TimeMutator",
          clearModes = BeamConfig.Beam.Replanning.ClearModes(
            if (c.hasPathOrNull("clearModes")) c.getConfig("clearModes")
            else com.typesafe.config.ConfigFactory.parseString("clearModes{}")
          ),
          fractionOfIterationsToDisableInnovation =
            if (c.hasPathOrNull("fractionOfIterationsToDisableInnovation"))
              c.getDouble("fractionOfIterationsToDisableInnovation")
            else Double.PositiveInfinity,
          maxAgentPlanMemorySize =
            if (c.hasPathOrNull("maxAgentPlanMemorySize")) c.getInt("maxAgentPlanMemorySize") else 5
        )
      }
    }

    case class Router(
      skim: BeamConfig.Beam.Router.Skim
    )

    object Router {

      case class Skim(
        activity_sim_skimmer: BeamConfig.Beam.Router.Skim.ActivitySimSkimmer,
        drive_time_skimmer: BeamConfig.Beam.Router.Skim.DriveTimeSkimmer,
        keepKLatestSkims: scala.Int,
        origin_destination_skimmer: BeamConfig.Beam.Router.Skim.OriginDestinationSkimmer,
        taz_skimmer: BeamConfig.Beam.Router.Skim.TazSkimmer,
        transit_crowding_skimmer: BeamConfig.Beam.Router.Skim.TransitCrowdingSkimmer,
        writeAggregatedSkimsInterval: scala.Int,
        writeSkimsInterval: scala.Int
      )

      object Skim {

        case class ActivitySimSkimmer(
          fileBaseName: java.lang.String,
          name: java.lang.String
        )

        object ActivitySimSkimmer {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Router.Skim.ActivitySimSkimmer = {
            BeamConfig.Beam.Router.Skim.ActivitySimSkimmer(
              fileBaseName = if (c.hasPathOrNull("fileBaseName")) c.getString("fileBaseName") else "activitySimODSkims",
              name = if (c.hasPathOrNull("name")) c.getString("name") else "activity-sim-skimmer"
            )
          }
        }

        case class DriveTimeSkimmer(
          fileBaseName: java.lang.String,
          name: java.lang.String
        )

        object DriveTimeSkimmer {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Router.Skim.DriveTimeSkimmer = {
            BeamConfig.Beam.Router.Skim.DriveTimeSkimmer(
              fileBaseName =
                if (c.hasPathOrNull("fileBaseName")) c.getString("fileBaseName")
                else "skimsTravelTimeObservedVsSimulated",
              name = if (c.hasPathOrNull("name")) c.getString("name") else "drive-time-skimmer"
            )
          }
        }

        case class OriginDestinationSkimmer(
          fileBaseName: java.lang.String,
          name: java.lang.String,
          poolingTravelTimeOveheadFactor: scala.Double,
          writeAllModeSkimsForPeakNonPeakPeriodsInterval: scala.Int,
          writeFullSkimsInterval: scala.Int
        )

        object OriginDestinationSkimmer {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Router.Skim.OriginDestinationSkimmer = {
            BeamConfig.Beam.Router.Skim.OriginDestinationSkimmer(
              fileBaseName = if (c.hasPathOrNull("fileBaseName")) c.getString("fileBaseName") else "skimsOD",
              name = if (c.hasPathOrNull("name")) c.getString("name") else "od-skimmer",
              poolingTravelTimeOveheadFactor =
                if (c.hasPathOrNull("poolingTravelTimeOveheadFactor")) c.getDouble("poolingTravelTimeOveheadFactor")
                else 1.21,
              writeAllModeSkimsForPeakNonPeakPeriodsInterval =
                if (c.hasPathOrNull("writeAllModeSkimsForPeakNonPeakPeriodsInterval"))
                  c.getInt("writeAllModeSkimsForPeakNonPeakPeriodsInterval")
                else 0,
              writeFullSkimsInterval =
                if (c.hasPathOrNull("writeFullSkimsInterval")) c.getInt("writeFullSkimsInterval") else 0
            )
          }
        }

        case class TazSkimmer(
          fileBaseName: java.lang.String,
          geoHierarchy: java.lang.String,
          name: java.lang.String
        )

        object TazSkimmer {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Router.Skim.TazSkimmer = {
            BeamConfig.Beam.Router.Skim.TazSkimmer(
              fileBaseName = if (c.hasPathOrNull("fileBaseName")) c.getString("fileBaseName") else "skimsTAZ",
              geoHierarchy = if (c.hasPathOrNull("geoHierarchy")) c.getString("geoHierarchy") else "TAZ",
              name = if (c.hasPathOrNull("name")) c.getString("name") else "taz-skimmer"
            )
          }
        }

        case class TransitCrowdingSkimmer(
          fileBaseName: java.lang.String,
          name: java.lang.String
        )

        object TransitCrowdingSkimmer {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Router.Skim.TransitCrowdingSkimmer = {
            BeamConfig.Beam.Router.Skim.TransitCrowdingSkimmer(
              fileBaseName =
                if (c.hasPathOrNull("fileBaseName")) c.getString("fileBaseName") else "skimsTransitCrowding",
              name = if (c.hasPathOrNull("name")) c.getString("name") else "transit-crowding-skimmer"
            )
          }
        }

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Router.Skim = {
          BeamConfig.Beam.Router.Skim(
            activity_sim_skimmer = BeamConfig.Beam.Router.Skim.ActivitySimSkimmer(
              if (c.hasPathOrNull("activity-sim-skimmer")) c.getConfig("activity-sim-skimmer")
              else com.typesafe.config.ConfigFactory.parseString("activity-sim-skimmer{}")
            ),
            drive_time_skimmer = BeamConfig.Beam.Router.Skim.DriveTimeSkimmer(
              if (c.hasPathOrNull("drive-time-skimmer")) c.getConfig("drive-time-skimmer")
              else com.typesafe.config.ConfigFactory.parseString("drive-time-skimmer{}")
            ),
            keepKLatestSkims = if (c.hasPathOrNull("keepKLatestSkims")) c.getInt("keepKLatestSkims") else 1,
            origin_destination_skimmer = BeamConfig.Beam.Router.Skim.OriginDestinationSkimmer(
              if (c.hasPathOrNull("origin-destination-skimmer")) c.getConfig("origin-destination-skimmer")
              else com.typesafe.config.ConfigFactory.parseString("origin-destination-skimmer{}")
            ),
            taz_skimmer = BeamConfig.Beam.Router.Skim.TazSkimmer(
              if (c.hasPathOrNull("taz-skimmer")) c.getConfig("taz-skimmer")
              else com.typesafe.config.ConfigFactory.parseString("taz-skimmer{}")
            ),
            transit_crowding_skimmer = BeamConfig.Beam.Router.Skim.TransitCrowdingSkimmer(
              if (c.hasPathOrNull("transit-crowding-skimmer")) c.getConfig("transit-crowding-skimmer")
              else com.typesafe.config.ConfigFactory.parseString("transit-crowding-skimmer{}")
            ),
            writeAggregatedSkimsInterval =
              if (c.hasPathOrNull("writeAggregatedSkimsInterval")) c.getInt("writeAggregatedSkimsInterval") else 0,
            writeSkimsInterval = if (c.hasPathOrNull("writeSkimsInterval")) c.getInt("writeSkimsInterval") else 0
          )
        }
      }

      def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Router = {
        BeamConfig.Beam.Router(
          skim = BeamConfig.Beam.Router.Skim(
            if (c.hasPathOrNull("skim")) c.getConfig("skim")
            else com.typesafe.config.ConfigFactory.parseString("skim{}")
          )
        )
      }
    }

    case class Routing(
      baseDate: java.lang.String,
      carRouter: java.lang.String,
      minimumPossibleSkimBasedTravelTimeInS: scala.Int,
      overrideNetworkTravelTimesUsingSkims: scala.Boolean,
      r5: BeamConfig.Beam.Routing.R5,
      skimTravelTimesScalingFactor: scala.Double,
      startingIterationForTravelTimesMSA: scala.Int,
      transitOnStreetNetwork: scala.Boolean
    )

    object Routing {

      case class R5(
        bikeLaneLinkIdsFilePath: java.lang.String,
        bikeLaneScaleFactor: scala.Double,
        departureWindow: scala.Double,
        directory: java.lang.String,
        mNetBuilder: BeamConfig.Beam.Routing.R5.MNetBuilder,
        maxDistanceLimitByModeInMeters: BeamConfig.Beam.Routing.R5.MaxDistanceLimitByModeInMeters,
        numberOfSamples: scala.Int,
        osmFile: java.lang.String,
        osmMapdbFile: java.lang.String,
        travelTimeNoiseFraction: scala.Double
      )

      object R5 {

        case class MNetBuilder(
          fromCRS: java.lang.String,
          toCRS: java.lang.String
        )

        object MNetBuilder {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Routing.R5.MNetBuilder = {
            BeamConfig.Beam.Routing.R5.MNetBuilder(
              fromCRS = if (c.hasPathOrNull("fromCRS")) c.getString("fromCRS") else "EPSG:4326",
              toCRS = if (c.hasPathOrNull("toCRS")) c.getString("toCRS") else "EPSG:26910"
            )
          }
        }

        case class MaxDistanceLimitByModeInMeters(
          bike: scala.Int
        )

        object MaxDistanceLimitByModeInMeters {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Routing.R5.MaxDistanceLimitByModeInMeters = {
            BeamConfig.Beam.Routing.R5.MaxDistanceLimitByModeInMeters(
              bike = if (c.hasPathOrNull("bike")) c.getInt("bike") else 40000
            )
          }
        }

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Routing.R5 = {
          BeamConfig.Beam.Routing.R5(
            bikeLaneLinkIdsFilePath =
              if (c.hasPathOrNull("bikeLaneLinkIdsFilePath")) c.getString("bikeLaneLinkIdsFilePath") else "",
            bikeLaneScaleFactor =
              if (c.hasPathOrNull("bikeLaneScaleFactor")) c.getDouble("bikeLaneScaleFactor") else 1.0,
            departureWindow = if (c.hasPathOrNull("departureWindow")) c.getDouble("departureWindow") else 15.0,
            directory = if (c.hasPathOrNull("directory")) c.getString("directory") else "/test/input/beamville/r5",
            mNetBuilder = BeamConfig.Beam.Routing.R5.MNetBuilder(
              if (c.hasPathOrNull("mNetBuilder")) c.getConfig("mNetBuilder")
              else com.typesafe.config.ConfigFactory.parseString("mNetBuilder{}")
            ),
            maxDistanceLimitByModeInMeters = BeamConfig.Beam.Routing.R5.MaxDistanceLimitByModeInMeters(
              if (c.hasPathOrNull("maxDistanceLimitByModeInMeters")) c.getConfig("maxDistanceLimitByModeInMeters")
              else com.typesafe.config.ConfigFactory.parseString("maxDistanceLimitByModeInMeters{}")
            ),
            numberOfSamples = if (c.hasPathOrNull("numberOfSamples")) c.getInt("numberOfSamples") else 1,
            osmFile =
              if (c.hasPathOrNull("osmFile")) c.getString("osmFile") else "/test/input/beamville/r5/beamville.osm.pbf",
            osmMapdbFile =
              if (c.hasPathOrNull("osmMapdbFile")) c.getString("osmMapdbFile")
              else "/test/input/beamville/r5/osm.mapdb",
            travelTimeNoiseFraction =
              if (c.hasPathOrNull("travelTimeNoiseFraction")) c.getDouble("travelTimeNoiseFraction") else 0.0
          )
        }
      }

      def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Routing = {
        BeamConfig.Beam.Routing(
          baseDate = if (c.hasPathOrNull("baseDate")) c.getString("baseDate") else "2016-10-17T00:00:00-07:00",
          carRouter = if (c.hasPathOrNull("carRouter")) c.getString("carRouter") else "R5",
          minimumPossibleSkimBasedTravelTimeInS =
            if (c.hasPathOrNull("minimumPossibleSkimBasedTravelTimeInS"))
              c.getInt("minimumPossibleSkimBasedTravelTimeInS")
            else 60,
          overrideNetworkTravelTimesUsingSkims =
            c.hasPathOrNull("overrideNetworkTravelTimesUsingSkims") && c.getBoolean(
              "overrideNetworkTravelTimesUsingSkims"
            ),
          r5 = BeamConfig.Beam.Routing.R5(
            if (c.hasPathOrNull("r5")) c.getConfig("r5") else com.typesafe.config.ConfigFactory.parseString("r5{}")
          ),
          skimTravelTimesScalingFactor =
            if (c.hasPathOrNull("skimTravelTimesScalingFactor")) c.getDouble("skimTravelTimesScalingFactor") else 0.0,
          startingIterationForTravelTimesMSA =
            if (c.hasPathOrNull("startingIterationForTravelTimesMSA")) c.getInt("startingIterationForTravelTimesMSA")
            else 0,
          transitOnStreetNetwork = !c.hasPathOrNull("transitOnStreetNetwork") || c.getBoolean("transitOnStreetNetwork")
        )
      }
    }

    case class Sim(
      metric: BeamConfig.Beam.Sim.Metric,
      termination: BeamConfig.Beam.Sim.Termination
    )

    object Sim {

      case class Metric(
        collector: BeamConfig.Beam.Sim.Metric.Collector
      )

      object Metric {

        case class Collector(
          influxDbSimulationMetricCollector: BeamConfig.Beam.Sim.Metric.Collector.InfluxDbSimulationMetricCollector,
          metrics: java.lang.String
        )

        object Collector {

          case class InfluxDbSimulationMetricCollector(
            connectionString: java.lang.String,
            database: java.lang.String
          )

          object InfluxDbSimulationMetricCollector {

            def apply(
              c: com.typesafe.config.Config
            ): BeamConfig.Beam.Sim.Metric.Collector.InfluxDbSimulationMetricCollector = {
              BeamConfig.Beam.Sim.Metric.Collector.InfluxDbSimulationMetricCollector(
                connectionString =
                  if (c.hasPathOrNull("connectionString")) c.getString("connectionString") else "http://localhost:8086",
                database = if (c.hasPathOrNull("database")) c.getString("database") else "beam"
              )
            }
          }

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Sim.Metric.Collector = {
            BeamConfig.Beam.Sim.Metric.Collector(
              influxDbSimulationMetricCollector =
                BeamConfig.Beam.Sim.Metric.Collector.InfluxDbSimulationMetricCollector(
                  if (c.hasPathOrNull("influxDbSimulationMetricCollector"))
                    c.getConfig("influxDbSimulationMetricCollector")
                  else com.typesafe.config.ConfigFactory.parseString("influxDbSimulationMetricCollector{}")
                ),
              metrics = if (c.hasPathOrNull("metrics")) c.getString("metrics") else "beam-run, beam-iteration"
            )
          }
        }

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Sim.Metric = {
          BeamConfig.Beam.Sim.Metric(
            collector = BeamConfig.Beam.Sim.Metric.Collector(
              if (c.hasPathOrNull("collector")) c.getConfig("collector")
              else com.typesafe.config.ConfigFactory.parseString("collector{}")
            )
          )
        }
      }

      case class Termination(
        criterionName: java.lang.String,
        terminateAtRideHailFleetStoredElectricityConvergence: BeamConfig.Beam.Sim.Termination.TerminateAtRideHailFleetStoredElectricityConvergence
      )

      object Termination {

        case class TerminateAtRideHailFleetStoredElectricityConvergence(
          maxLastIteration: scala.Int,
          minLastIteration: scala.Int,
          relativeTolerance: scala.Double
        )

        object TerminateAtRideHailFleetStoredElectricityConvergence {

          def apply(
            c: com.typesafe.config.Config
          ): BeamConfig.Beam.Sim.Termination.TerminateAtRideHailFleetStoredElectricityConvergence = {
            BeamConfig.Beam.Sim.Termination.TerminateAtRideHailFleetStoredElectricityConvergence(
              maxLastIteration = if (c.hasPathOrNull("maxLastIteration")) c.getInt("maxLastIteration") else 0,
              minLastIteration = if (c.hasPathOrNull("minLastIteration")) c.getInt("minLastIteration") else 0,
              relativeTolerance = if (c.hasPathOrNull("relativeTolerance")) c.getDouble("relativeTolerance") else 0.01
            )
          }
        }

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Sim.Termination = {
          BeamConfig.Beam.Sim.Termination(
            criterionName =
              if (c.hasPathOrNull("criterionName")) c.getString("criterionName") else "TerminateAtFixedIterationNumber",
            terminateAtRideHailFleetStoredElectricityConvergence =
              BeamConfig.Beam.Sim.Termination.TerminateAtRideHailFleetStoredElectricityConvergence(
                if (c.hasPathOrNull("terminateAtRideHailFleetStoredElectricityConvergence"))
                  c.getConfig("terminateAtRideHailFleetStoredElectricityConvergence")
                else
                  com.typesafe.config.ConfigFactory
                    .parseString("terminateAtRideHailFleetStoredElectricityConvergence{}")
              )
          )
        }
      }

      def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Sim = {
        BeamConfig.Beam.Sim(
          metric = BeamConfig.Beam.Sim.Metric(
            if (c.hasPathOrNull("metric")) c.getConfig("metric")
            else com.typesafe.config.ConfigFactory.parseString("metric{}")
          ),
          termination = BeamConfig.Beam.Sim.Termination(
            if (c.hasPathOrNull("termination")) c.getConfig("termination")
            else com.typesafe.config.ConfigFactory.parseString("termination{}")
          )
        )
      }
    }

    case class Spatial(
      boundingBoxBuffer: scala.Int,
      localCRS: java.lang.String
    )

    object Spatial {

      def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Spatial = {
        BeamConfig.Beam.Spatial(
          boundingBoxBuffer = if (c.hasPathOrNull("boundingBoxBuffer")) c.getInt("boundingBoxBuffer") else 5000,
          localCRS = if (c.hasPathOrNull("localCRS")) c.getString("localCRS") else "epsg:32631"
        )
      }
    }

    case class Urbansim(
      backgroundODSkimsCreator: BeamConfig.Beam.Urbansim.BackgroundODSkimsCreator,
      fractionOfModesToClear: BeamConfig.Beam.Urbansim.FractionOfModesToClear
    )

    object Urbansim {

      case class BackgroundODSkimsCreator(
        calculationTimeoutHours: scala.Int,
        enabled: scala.Boolean,
        maxTravelDistanceInMeters: BeamConfig.Beam.Urbansim.BackgroundODSkimsCreator.MaxTravelDistanceInMeters,
        modesToBuild: BeamConfig.Beam.Urbansim.BackgroundODSkimsCreator.ModesToBuild,
        numberOfH3Indexes: scala.Int,
        peakHours: scala.Option[scala.List[scala.Double]],
        routerType: java.lang.String,
        skimsGeoType: java.lang.String,
        skimsKind: java.lang.String
      )

      object BackgroundODSkimsCreator {

        case class MaxTravelDistanceInMeters(
          bike: scala.Int,
          walk: scala.Int
        )

        object MaxTravelDistanceInMeters {

          def apply(
            c: com.typesafe.config.Config
          ): BeamConfig.Beam.Urbansim.BackgroundODSkimsCreator.MaxTravelDistanceInMeters = {
            BeamConfig.Beam.Urbansim.BackgroundODSkimsCreator.MaxTravelDistanceInMeters(
              bike = if (c.hasPathOrNull("bike")) c.getInt("bike") else 33000,
              walk = if (c.hasPathOrNull("walk")) c.getInt("walk") else 10000
            )
          }
        }

        case class ModesToBuild(
          drive: scala.Boolean,
          transit: scala.Boolean,
          walk: scala.Boolean
        )

        object ModesToBuild {

          def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Urbansim.BackgroundODSkimsCreator.ModesToBuild = {
            BeamConfig.Beam.Urbansim.BackgroundODSkimsCreator.ModesToBuild(
              drive = !c.hasPathOrNull("drive") || c.getBoolean("drive"),
              transit = !c.hasPathOrNull("transit") || c.getBoolean("transit"),
              walk = !c.hasPathOrNull("walk") || c.getBoolean("walk")
            )
          }
        }

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Urbansim.BackgroundODSkimsCreator = {
          BeamConfig.Beam.Urbansim.BackgroundODSkimsCreator(
            calculationTimeoutHours =
              if (c.hasPathOrNull("calculationTimeoutHours")) c.getInt("calculationTimeoutHours") else 6,
            enabled = c.hasPathOrNull("enabled") && c.getBoolean("enabled"),
            maxTravelDistanceInMeters = BeamConfig.Beam.Urbansim.BackgroundODSkimsCreator.MaxTravelDistanceInMeters(
              if (c.hasPathOrNull("maxTravelDistanceInMeters")) c.getConfig("maxTravelDistanceInMeters")
              else com.typesafe.config.ConfigFactory.parseString("maxTravelDistanceInMeters{}")
            ),
            modesToBuild = BeamConfig.Beam.Urbansim.BackgroundODSkimsCreator.ModesToBuild(
              if (c.hasPathOrNull("modesToBuild")) c.getConfig("modesToBuild")
              else com.typesafe.config.ConfigFactory.parseString("modesToBuild{}")
            ),
            numberOfH3Indexes = if (c.hasPathOrNull("numberOfH3Indexes")) c.getInt("numberOfH3Indexes") else 1000,
            peakHours = if (c.hasPathOrNull("peakHours")) scala.Some($_L$_dbl(c.getList("peakHours"))) else None,
            routerType = if (c.hasPathOrNull("routerType")) c.getString("routerType") else "r5",
            skimsGeoType = if (c.hasPathOrNull("skimsGeoType")) c.getString("skimsGeoType") else "h3",
            skimsKind = if (c.hasPathOrNull("skimsKind")) c.getString("skimsKind") else "od"
          )
        }
      }

      case class FractionOfModesToClear(
        allModes: scala.Double,
        bike: scala.Double,
        car: scala.Double,
        drive_transit: scala.Double,
        walk: scala.Double,
        walk_transit: scala.Double
      )

      object FractionOfModesToClear {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Urbansim.FractionOfModesToClear = {
          BeamConfig.Beam.Urbansim.FractionOfModesToClear(
            allModes = if (c.hasPathOrNull("allModes")) c.getDouble("allModes") else 0.0,
            bike = if (c.hasPathOrNull("bike")) c.getDouble("bike") else 0.0,
            car = if (c.hasPathOrNull("car")) c.getDouble("car") else 0.0,
            drive_transit = if (c.hasPathOrNull("drive_transit")) c.getDouble("drive_transit") else 0.0,
            walk = if (c.hasPathOrNull("walk")) c.getDouble("walk") else 0.0,
            walk_transit = if (c.hasPathOrNull("walk_transit")) c.getDouble("walk_transit") else 0.0
          )
        }
      }

      def apply(c: com.typesafe.config.Config): BeamConfig.Beam.Urbansim = {
        BeamConfig.Beam.Urbansim(
          backgroundODSkimsCreator = BeamConfig.Beam.Urbansim.BackgroundODSkimsCreator(
            if (c.hasPathOrNull("backgroundODSkimsCreator")) c.getConfig("backgroundODSkimsCreator")
            else com.typesafe.config.ConfigFactory.parseString("backgroundODSkimsCreator{}")
          ),
          fractionOfModesToClear = BeamConfig.Beam.Urbansim.FractionOfModesToClear(
            if (c.hasPathOrNull("fractionOfModesToClear")) c.getConfig("fractionOfModesToClear")
            else com.typesafe.config.ConfigFactory.parseString("fractionOfModesToClear{}")
          )
        )
      }
    }

    case class WarmStart(
      path: java.lang.String,
      prepareData: scala.Boolean,
      samplePopulationIntegerFlag: scala.Int,
      skimsFilePaths: scala.Option[scala.List[BeamConfig.Beam.WarmStart.SkimsFilePaths$Elm]],
      `type`: java.lang.String
    )

    object WarmStart {

      case class SkimsFilePaths$Elm(
        skimType: java.lang.String,
        skimsFilePath: java.lang.String
      )

      object SkimsFilePaths$Elm {

        def apply(c: com.typesafe.config.Config): BeamConfig.Beam.WarmStart.SkimsFilePaths$Elm = {
          BeamConfig.Beam.WarmStart.SkimsFilePaths$Elm(
            skimType = c.getString("skimType"),
            skimsFilePath = if (c.hasPathOrNull("skimsFilePath")) c.getString("skimsFilePath") else ""
          )
        }
      }

      def apply(c: com.typesafe.config.Config): BeamConfig.Beam.WarmStart = {
        BeamConfig.Beam.WarmStart(
          path = if (c.hasPathOrNull("path")) c.getString("path") else "",
          prepareData = c.hasPathOrNull("prepareData") && c.getBoolean("prepareData"),
          samplePopulationIntegerFlag =
            if (c.hasPathOrNull("samplePopulationIntegerFlag")) c.getInt("samplePopulationIntegerFlag") else 0,
          skimsFilePaths =
            if (c.hasPathOrNull("skimsFilePaths"))
              scala.Some($_LBeamConfig_Beam_WarmStart_SkimsFilePaths$Elm(c.getList("skimsFilePaths")))
            else None,
          `type` = if (c.hasPathOrNull("type")) c.getString("type") else "disabled"
        )
      }

      private def $_LBeamConfig_Beam_WarmStart_SkimsFilePaths$Elm(
        cl: com.typesafe.config.ConfigList
      ): scala.List[BeamConfig.Beam.WarmStart.SkimsFilePaths$Elm] = {
        import scala.collection.JavaConverters._
        cl.asScala
          .map(cv =>
            BeamConfig.Beam.WarmStart.SkimsFilePaths$Elm(cv.asInstanceOf[com.typesafe.config.ConfigObject].toConfig)
          )
          .toList
      }
    }

    def apply(c: com.typesafe.config.Config): BeamConfig.Beam = {
      BeamConfig.Beam(
        actorSystemName = if (c.hasPathOrNull("actorSystemName")) c.getString("actorSystemName") else "ClusterSystem",
        agentsim = BeamConfig.Beam.Agentsim(
          if (c.hasPathOrNull("agentsim")) c.getConfig("agentsim")
          else com.typesafe.config.ConfigFactory.parseString("agentsim{}")
        ),
        calibration = BeamConfig.Beam.Calibration(
          if (c.hasPathOrNull("calibration")) c.getConfig("calibration")
          else com.typesafe.config.ConfigFactory.parseString("calibration{}")
        ),
        cluster = BeamConfig.Beam.Cluster(
          if (c.hasPathOrNull("cluster")) c.getConfig("cluster")
          else com.typesafe.config.ConfigFactory.parseString("cluster{}")
        ),
        debug = BeamConfig.Beam.Debug(
          if (c.hasPathOrNull("debug")) c.getConfig("debug")
          else com.typesafe.config.ConfigFactory.parseString("debug{}")
        ),
        exchange = BeamConfig.Beam.Exchange(
          if (c.hasPathOrNull("exchange")) c.getConfig("exchange")
          else com.typesafe.config.ConfigFactory.parseString("exchange{}")
        ),
        experimental = BeamConfig.Beam.Experimental(
          if (c.hasPathOrNull("experimental")) c.getConfig("experimental")
          else com.typesafe.config.ConfigFactory.parseString("experimental{}")
        ),
        input = BeamConfig.Beam.Input(
          if (c.hasPathOrNull("input")) c.getConfig("input")
          else com.typesafe.config.ConfigFactory.parseString("input{}")
        ),
        inputDirectory =
          if (c.hasPathOrNull("inputDirectory")) c.getString("inputDirectory") else "/test/input/beamville",
        logger = BeamConfig.Beam.Logger(
          if (c.hasPathOrNull("logger")) c.getConfig("logger")
          else com.typesafe.config.ConfigFactory.parseString("logger{}")
        ),
        metrics = BeamConfig.Beam.Metrics(
          if (c.hasPathOrNull("metrics")) c.getConfig("metrics")
          else com.typesafe.config.ConfigFactory.parseString("metrics{}")
        ),
        outputs = BeamConfig.Beam.Outputs(
          if (c.hasPathOrNull("outputs")) c.getConfig("outputs")
          else com.typesafe.config.ConfigFactory.parseString("outputs{}")
        ),
        physsim = BeamConfig.Beam.Physsim(
          if (c.hasPathOrNull("physsim")) c.getConfig("physsim")
          else com.typesafe.config.ConfigFactory.parseString("physsim{}")
        ),
        replanning = BeamConfig.Beam.Replanning(
          if (c.hasPathOrNull("replanning")) c.getConfig("replanning")
          else com.typesafe.config.ConfigFactory.parseString("replanning{}")
        ),
        router = BeamConfig.Beam.Router(
          if (c.hasPathOrNull("router")) c.getConfig("router")
          else com.typesafe.config.ConfigFactory.parseString("router{}")
        ),
        routing = BeamConfig.Beam.Routing(
          if (c.hasPathOrNull("routing")) c.getConfig("routing")
          else com.typesafe.config.ConfigFactory.parseString("routing{}")
        ),
        sim = BeamConfig.Beam.Sim(
          if (c.hasPathOrNull("sim")) c.getConfig("sim") else com.typesafe.config.ConfigFactory.parseString("sim{}")
        ),
        spatial = BeamConfig.Beam.Spatial(
          if (c.hasPathOrNull("spatial")) c.getConfig("spatial")
          else com.typesafe.config.ConfigFactory.parseString("spatial{}")
        ),
        urbansim = BeamConfig.Beam.Urbansim(
          if (c.hasPathOrNull("urbansim")) c.getConfig("urbansim")
          else com.typesafe.config.ConfigFactory.parseString("urbansim{}")
        ),
        useLocalWorker = !c.hasPathOrNull("useLocalWorker") || c.getBoolean("useLocalWorker"),
        warmStart = BeamConfig.Beam.WarmStart(
          if (c.hasPathOrNull("warmStart")) c.getConfig("warmStart")
          else com.typesafe.config.ConfigFactory.parseString("warmStart{}")
        )
      )
    }
  }

  case class Matsim(
    conversion: BeamConfig.Matsim.Conversion,
    modules: BeamConfig.Matsim.Modules
  )

  object Matsim {

    case class Conversion(
      defaultHouseholdIncome: BeamConfig.Matsim.Conversion.DefaultHouseholdIncome,
      generateVehicles: scala.Boolean,
      matsimNetworkFile: java.lang.String,
      osmFile: java.lang.String,
      populationFile: java.lang.String,
      scenarioDirectory: java.lang.String,
      shapeConfig: BeamConfig.Matsim.Conversion.ShapeConfig,
      vehiclesFile: java.lang.String
    )

    object Conversion {

      case class DefaultHouseholdIncome(
        currency: java.lang.String,
        period: java.lang.String,
        value: scala.Int
      )

      object DefaultHouseholdIncome {

        def apply(c: com.typesafe.config.Config): BeamConfig.Matsim.Conversion.DefaultHouseholdIncome = {
          BeamConfig.Matsim.Conversion.DefaultHouseholdIncome(
            currency = if (c.hasPathOrNull("currency")) c.getString("currency") else "usd",
            period = if (c.hasPathOrNull("period")) c.getString("period") else "year",
            value = if (c.hasPathOrNull("value")) c.getInt("value") else 50000
          )
        }
      }

      case class ShapeConfig(
        shapeFile: java.lang.String,
        tazIdFieldName: java.lang.String
      )

      object ShapeConfig {

        def apply(c: com.typesafe.config.Config): BeamConfig.Matsim.Conversion.ShapeConfig = {
          BeamConfig.Matsim.Conversion.ShapeConfig(
            shapeFile = if (c.hasPathOrNull("shapeFile")) c.getString("shapeFile") else "tz46_d00.shp",
            tazIdFieldName = if (c.hasPathOrNull("tazIdFieldName")) c.getString("tazIdFieldName") else "TZ46_D00_I"
          )
        }
      }

      def apply(c: com.typesafe.config.Config): BeamConfig.Matsim.Conversion = {
        BeamConfig.Matsim.Conversion(
          defaultHouseholdIncome = BeamConfig.Matsim.Conversion.DefaultHouseholdIncome(
            if (c.hasPathOrNull("defaultHouseholdIncome")) c.getConfig("defaultHouseholdIncome")
            else com.typesafe.config.ConfigFactory.parseString("defaultHouseholdIncome{}")
          ),
          generateVehicles = !c.hasPathOrNull("generateVehicles") || c.getBoolean("generateVehicles"),
          matsimNetworkFile =
            if (c.hasPathOrNull("matsimNetworkFile")) c.getString("matsimNetworkFile") else "Siouxfalls_network_PT.xml",
          osmFile = if (c.hasPathOrNull("osmFile")) c.getString("osmFile") else "south-dakota-latest.osm.pbf",
          populationFile =
            if (c.hasPathOrNull("populationFile")) c.getString("populationFile") else "Siouxfalls_population.xml",
          scenarioDirectory =
            if (c.hasPathOrNull("scenarioDirectory")) c.getString("scenarioDirectory")
            else "/path/to/scenario/directory",
          shapeConfig = BeamConfig.Matsim.Conversion.ShapeConfig(
            if (c.hasPathOrNull("shapeConfig")) c.getConfig("shapeConfig")
            else com.typesafe.config.ConfigFactory.parseString("shapeConfig{}")
          ),
          vehiclesFile = if (c.hasPathOrNull("vehiclesFile")) c.getString("vehiclesFile") else "Siouxfalls_vehicles.xml"
        )
      }
    }

    case class Modules(
      changeMode: BeamConfig.Matsim.Modules.ChangeMode,
      controler: BeamConfig.Matsim.Modules.Controler,
      counts: BeamConfig.Matsim.Modules.Counts,
      global: BeamConfig.Matsim.Modules.Global,
      households: BeamConfig.Matsim.Modules.Households,
      linkStats: BeamConfig.Matsim.Modules.LinkStats,
      network: BeamConfig.Matsim.Modules.Network,
      parallelEventHandling: BeamConfig.Matsim.Modules.ParallelEventHandling,
      planCalcScore: BeamConfig.Matsim.Modules.PlanCalcScore,
      plans: BeamConfig.Matsim.Modules.Plans,
      qsim: BeamConfig.Matsim.Modules.Qsim,
      strategy: BeamConfig.Matsim.Modules.Strategy,
      transit: BeamConfig.Matsim.Modules.Transit,
      vehicles: BeamConfig.Matsim.Modules.Vehicles
    )

    object Modules {

      case class ChangeMode(
        modes: java.lang.String
      )

      object ChangeMode {

        def apply(c: com.typesafe.config.Config): BeamConfig.Matsim.Modules.ChangeMode = {
          BeamConfig.Matsim.Modules.ChangeMode(
            modes = if (c.hasPathOrNull("modes")) c.getString("modes") else "car,pt"
          )
        }
      }

      case class Controler(
        eventsFileFormat: java.lang.String,
        firstIteration: scala.Int,
        lastIteration: scala.Int,
        mobsim: java.lang.String,
        outputDirectory: java.lang.String,
        overwriteFiles: java.lang.String
      )

      object Controler {

        def apply(c: com.typesafe.config.Config): BeamConfig.Matsim.Modules.Controler = {
          BeamConfig.Matsim.Modules.Controler(
            eventsFileFormat = if (c.hasPathOrNull("eventsFileFormat")) c.getString("eventsFileFormat") else "xml",
            firstIteration = if (c.hasPathOrNull("firstIteration")) c.getInt("firstIteration") else 0,
            lastIteration = if (c.hasPathOrNull("lastIteration")) c.getInt("lastIteration") else 0,
            mobsim = if (c.hasPathOrNull("mobsim")) c.getString("mobsim") else "metasim",
            outputDirectory = if (c.hasPathOrNull("outputDirectory")) c.getString("outputDirectory") else "",
            overwriteFiles =
              if (c.hasPathOrNull("overwriteFiles")) c.getString("overwriteFiles") else "overwriteExistingFiles"
          )
        }
      }

      case class Counts(
        averageCountsOverIterations: scala.Int,
        countsScaleFactor: scala.Double,
        inputCountsFile: java.lang.String,
        outputformat: java.lang.String,
        writeCountsInterval: scala.Int
      )

      object Counts {

        def apply(c: com.typesafe.config.Config): BeamConfig.Matsim.Modules.Counts = {
          BeamConfig.Matsim.Modules.Counts(
            averageCountsOverIterations =
              if (c.hasPathOrNull("averageCountsOverIterations")) c.getInt("averageCountsOverIterations") else 0,
            countsScaleFactor = if (c.hasPathOrNull("countsScaleFactor")) c.getDouble("countsScaleFactor") else 10.355,
            inputCountsFile = if (c.hasPathOrNull("inputCountsFile")) c.getString("inputCountsFile") else "",
            outputformat = if (c.hasPathOrNull("outputformat")) c.getString("outputformat") else "all",
            writeCountsInterval = if (c.hasPathOrNull("writeCountsInterval")) c.getInt("writeCountsInterval") else 0
          )
        }
      }

      case class Global(
        coordinateSystem: java.lang.String,
        randomSeed: scala.Int
      )

      object Global {

        def apply(c: com.typesafe.config.Config): BeamConfig.Matsim.Modules.Global = {
          BeamConfig.Matsim.Modules.Global(
            coordinateSystem = if (c.hasPathOrNull("coordinateSystem")) c.getString("coordinateSystem") else "Atlantis",
            randomSeed = if (c.hasPathOrNull("randomSeed")) c.getInt("randomSeed") else 4711
          )
        }
      }

      case class Households(
        inputFile: java.lang.String,
        inputHouseholdAttributesFile: java.lang.String
      )

      object Households {

        def apply(c: com.typesafe.config.Config): BeamConfig.Matsim.Modules.Households = {
          BeamConfig.Matsim.Modules.Households(
            inputFile =
              if (c.hasPathOrNull("inputFile")) c.getString("inputFile") else "/test/input/beamville/households.xml",
            inputHouseholdAttributesFile =
              if (c.hasPathOrNull("inputHouseholdAttributesFile")) c.getString("inputHouseholdAttributesFile")
              else "/test/input/beamville/householdAttributes.xml"
          )
        }
      }

      case class LinkStats(
        averageLinkStatsOverIterations: scala.Int,
        writeLinkStatsInterval: scala.Int
      )

      object LinkStats {

        def apply(c: com.typesafe.config.Config): BeamConfig.Matsim.Modules.LinkStats = {
          BeamConfig.Matsim.Modules.LinkStats(
            averageLinkStatsOverIterations =
              if (c.hasPathOrNull("averageLinkStatsOverIterations")) c.getInt("averageLinkStatsOverIterations") else 5,
            writeLinkStatsInterval =
              if (c.hasPathOrNull("writeLinkStatsInterval")) c.getInt("writeLinkStatsInterval") else 10
          )
        }
      }

      case class Network(
        inputNetworkFile: java.lang.String
      )

      object Network {

        def apply(c: com.typesafe.config.Config): BeamConfig.Matsim.Modules.Network = {
          BeamConfig.Matsim.Modules.Network(
            inputNetworkFile =
              if (c.hasPathOrNull("inputNetworkFile")) c.getString("inputNetworkFile")
              else "/test/input/beamville/physsim-network.xml"
          )
        }
      }

      case class ParallelEventHandling(
        estimatedNumberOfEvents: scala.Int,
        numberOfThreads: scala.Int,
        oneThreadPerHandler: scala.Boolean,
        synchronizeOnSimSteps: scala.Boolean
      )

      object ParallelEventHandling {

        def apply(c: com.typesafe.config.Config): BeamConfig.Matsim.Modules.ParallelEventHandling = {
          BeamConfig.Matsim.Modules.ParallelEventHandling(
            estimatedNumberOfEvents =
              if (c.hasPathOrNull("estimatedNumberOfEvents")) c.getInt("estimatedNumberOfEvents") else 1000000000,
            numberOfThreads = if (c.hasPathOrNull("numberOfThreads")) c.getInt("numberOfThreads") else 1,
            oneThreadPerHandler = c.hasPathOrNull("oneThreadPerHandler") && c.getBoolean("oneThreadPerHandler"),
            synchronizeOnSimSteps = c.hasPathOrNull("synchronizeOnSimSteps") && c.getBoolean("synchronizeOnSimSteps")
          )
        }
      }

      case class PlanCalcScore(
        BrainExpBeta: scala.Long,
        earlyDeparture: scala.Long,
        lateArrival: scala.Long,
        learningRate: scala.Long,
        parameterset: scala.List[BeamConfig.Matsim.Modules.PlanCalcScore.Parameterset$Elm],
        performing: scala.Long,
        traveling: scala.Long,
        waiting: scala.Long,
        writeExperiencedPlans: scala.Boolean
      )

      object PlanCalcScore {

        case class Parameterset$Elm(
          activityType: java.lang.String,
          priority: scala.Int,
          scoringThisActivityAtAll: scala.Boolean,
          `type`: java.lang.String,
          typicalDuration: java.lang.String,
          typicalDurationScoreComputation: java.lang.String
        )

        object Parameterset$Elm {

          def apply(c: com.typesafe.config.Config): BeamConfig.Matsim.Modules.PlanCalcScore.Parameterset$Elm = {
            BeamConfig.Matsim.Modules.PlanCalcScore.Parameterset$Elm(
              activityType = if (c.hasPathOrNull("activityType")) c.getString("activityType") else "Home",
              priority = if (c.hasPathOrNull("priority")) c.getInt("priority") else 1,
              scoringThisActivityAtAll =
                !c.hasPathOrNull("scoringThisActivityAtAll") || c.getBoolean("scoringThisActivityAtAll"),
              `type` = if (c.hasPathOrNull("type")) c.getString("type") else "activityParams",
              typicalDuration = if (c.hasPathOrNull("typicalDuration")) c.getString("typicalDuration") else "01:00:00",
              typicalDurationScoreComputation =
                if (c.hasPathOrNull("typicalDurationScoreComputation")) c.getString("typicalDurationScoreComputation")
                else "uniform"
            )
          }
        }

        def apply(c: com.typesafe.config.Config): BeamConfig.Matsim.Modules.PlanCalcScore = {
          BeamConfig.Matsim.Modules.PlanCalcScore(
            BrainExpBeta =
              if (c.hasPathOrNull("BrainExpBeta"))
                c.getDuration("BrainExpBeta", java.util.concurrent.TimeUnit.MILLISECONDS)
              else 2,
            earlyDeparture =
              if (c.hasPathOrNull("earlyDeparture"))
                c.getDuration("earlyDeparture", java.util.concurrent.TimeUnit.MILLISECONDS)
              else 0,
            lateArrival =
              if (c.hasPathOrNull("lateArrival"))
                c.getDuration("lateArrival", java.util.concurrent.TimeUnit.MILLISECONDS)
              else -18,
            learningRate =
              if (c.hasPathOrNull("learningRate"))
                c.getDuration("learningRate", java.util.concurrent.TimeUnit.MILLISECONDS)
              else 1,
            parameterset = $_LBeamConfig_Matsim_Modules_PlanCalcScore_Parameterset$Elm(c.getList("parameterset")),
            performing =
              if (c.hasPathOrNull("performing")) c.getDuration("performing", java.util.concurrent.TimeUnit.MILLISECONDS)
              else 6,
            traveling =
              if (c.hasPathOrNull("traveling")) c.getDuration("traveling", java.util.concurrent.TimeUnit.MILLISECONDS)
              else -6,
            waiting =
              if (c.hasPathOrNull("waiting")) c.getDuration("waiting", java.util.concurrent.TimeUnit.MILLISECONDS)
              else 0,
            writeExperiencedPlans = !c.hasPathOrNull("writeExperiencedPlans") || c.getBoolean("writeExperiencedPlans")
          )
        }

        private def $_LBeamConfig_Matsim_Modules_PlanCalcScore_Parameterset$Elm(
          cl: com.typesafe.config.ConfigList
        ): scala.List[BeamConfig.Matsim.Modules.PlanCalcScore.Parameterset$Elm] = {
          import scala.collection.JavaConverters._
          cl.asScala
            .map(cv =>
              BeamConfig.Matsim.Modules.PlanCalcScore
                .Parameterset$Elm(cv.asInstanceOf[com.typesafe.config.ConfigObject].toConfig)
            )
            .toList
        }
      }

      case class Plans(
        inputPersonAttributesFile: java.lang.String,
        inputPlansFile: java.lang.String
      )

      object Plans {

        def apply(c: com.typesafe.config.Config): BeamConfig.Matsim.Modules.Plans = {
          BeamConfig.Matsim.Modules.Plans(
            inputPersonAttributesFile =
              if (c.hasPathOrNull("inputPersonAttributesFile")) c.getString("inputPersonAttributesFile")
              else "/test/input/beamville/populationAttributes.xml",
            inputPlansFile =
              if (c.hasPathOrNull("inputPlansFile")) c.getString("inputPlansFile")
              else "/test/input/beamville/population.xml"
          )
        }
      }

      case class Qsim(
        endTime: java.lang.String,
        snapshotperiod: java.lang.String,
        startTime: java.lang.String
      )

      object Qsim {

        def apply(c: com.typesafe.config.Config): BeamConfig.Matsim.Modules.Qsim = {
          BeamConfig.Matsim.Modules.Qsim(
            endTime = if (c.hasPathOrNull("endTime")) c.getString("endTime") else "30:00:00",
            snapshotperiod = if (c.hasPathOrNull("snapshotperiod")) c.getString("snapshotperiod") else "00:00:00",
            startTime = if (c.hasPathOrNull("startTime")) c.getString("startTime") else "00:00:00"
          )
        }
      }

      case class Strategy(
        ModuleProbability_1: scala.Int,
        ModuleProbability_2: scala.Int,
        ModuleProbability_3: scala.Int,
        ModuleProbability_4: scala.Int,
        Module_1: java.lang.String,
        Module_2: java.lang.String,
        Module_3: java.lang.String,
        Module_4: java.lang.String,
        fractionOfIterationsToDisableInnovation: scala.Int,
        maxAgentPlanMemorySize: scala.Int,
        parameterset: scala.List[BeamConfig.Matsim.Modules.Strategy.Parameterset$Elm],
        planSelectorForRemoval: java.lang.String
      )

      object Strategy {

        case class Parameterset$Elm(
          disableAfterIteration: scala.Int,
          strategyName: java.lang.String,
          `type`: java.lang.String,
          weight: scala.Int
        )

        object Parameterset$Elm {

          def apply(c: com.typesafe.config.Config): BeamConfig.Matsim.Modules.Strategy.Parameterset$Elm = {
            BeamConfig.Matsim.Modules.Strategy.Parameterset$Elm(
              disableAfterIteration =
                if (c.hasPathOrNull("disableAfterIteration")) c.getInt("disableAfterIteration") else -1,
              strategyName = if (c.hasPathOrNull("strategyName")) c.getString("strategyName") else "",
              `type` = if (c.hasPathOrNull("type")) c.getString("type") else "strategysettings",
              weight = if (c.hasPathOrNull("weight")) c.getInt("weight") else 0
            )
          }
        }

        def apply(c: com.typesafe.config.Config): BeamConfig.Matsim.Modules.Strategy = {
          BeamConfig.Matsim.Modules.Strategy(
            ModuleProbability_1 = if (c.hasPathOrNull("ModuleProbability_1")) c.getInt("ModuleProbability_1") else 0,
            ModuleProbability_2 = if (c.hasPathOrNull("ModuleProbability_2")) c.getInt("ModuleProbability_2") else 0,
            ModuleProbability_3 = if (c.hasPathOrNull("ModuleProbability_3")) c.getInt("ModuleProbability_3") else 0,
            ModuleProbability_4 = if (c.hasPathOrNull("ModuleProbability_4")) c.getInt("ModuleProbability_4") else 0,
            Module_1 = if (c.hasPathOrNull("Module_1")) c.getString("Module_1") else "",
            Module_2 = if (c.hasPathOrNull("Module_2")) c.getString("Module_2") else "",
            Module_3 = if (c.hasPathOrNull("Module_3")) c.getString("Module_3") else "",
            Module_4 = if (c.hasPathOrNull("Module_4")) c.getString("Module_4") else "",
            fractionOfIterationsToDisableInnovation =
              if (c.hasPathOrNull("fractionOfIterationsToDisableInnovation"))
                c.getInt("fractionOfIterationsToDisableInnovation")
              else 999999,
            maxAgentPlanMemorySize =
              if (c.hasPathOrNull("maxAgentPlanMemorySize")) c.getInt("maxAgentPlanMemorySize") else 5,
            parameterset = $_LBeamConfig_Matsim_Modules_Strategy_Parameterset$Elm(c.getList("parameterset")),
            planSelectorForRemoval =
              if (c.hasPathOrNull("planSelectorForRemoval")) c.getString("planSelectorForRemoval")
              else "WorstPlanForRemovalSelector"
          )
        }

        private def $_LBeamConfig_Matsim_Modules_Strategy_Parameterset$Elm(
          cl: com.typesafe.config.ConfigList
        ): scala.List[BeamConfig.Matsim.Modules.Strategy.Parameterset$Elm] = {
          import scala.collection.JavaConverters._
          cl.asScala
            .map(cv =>
              BeamConfig.Matsim.Modules.Strategy
                .Parameterset$Elm(cv.asInstanceOf[com.typesafe.config.ConfigObject].toConfig)
            )
            .toList
        }
      }

      case class Transit(
        transitModes: java.lang.String,
        useTransit: scala.Boolean,
        vehiclesFile: java.lang.String
      )

      object Transit {

        def apply(c: com.typesafe.config.Config): BeamConfig.Matsim.Modules.Transit = {
          BeamConfig.Matsim.Modules.Transit(
            transitModes = if (c.hasPathOrNull("transitModes")) c.getString("transitModes") else "pt",
            useTransit = c.hasPathOrNull("useTransit") && c.getBoolean("useTransit"),
            vehiclesFile = if (c.hasPathOrNull("vehiclesFile")) c.getString("vehiclesFile") else ""
          )
        }
      }

      case class Vehicles(
        vehiclesFile: java.lang.String
      )

      object Vehicles {

        def apply(c: com.typesafe.config.Config): BeamConfig.Matsim.Modules.Vehicles = {
          BeamConfig.Matsim.Modules.Vehicles(
            vehiclesFile = if (c.hasPathOrNull("vehiclesFile")) c.getString("vehiclesFile") else ""
          )
        }
      }

      def apply(c: com.typesafe.config.Config): BeamConfig.Matsim.Modules = {
        BeamConfig.Matsim.Modules(
          changeMode = BeamConfig.Matsim.Modules.ChangeMode(
            if (c.hasPathOrNull("changeMode")) c.getConfig("changeMode")
            else com.typesafe.config.ConfigFactory.parseString("changeMode{}")
          ),
          controler = BeamConfig.Matsim.Modules.Controler(
            if (c.hasPathOrNull("controler")) c.getConfig("controler")
            else com.typesafe.config.ConfigFactory.parseString("controler{}")
          ),
          counts = BeamConfig.Matsim.Modules.Counts(
            if (c.hasPathOrNull("counts")) c.getConfig("counts")
            else com.typesafe.config.ConfigFactory.parseString("counts{}")
          ),
          global = BeamConfig.Matsim.Modules.Global(
            if (c.hasPathOrNull("global")) c.getConfig("global")
            else com.typesafe.config.ConfigFactory.parseString("global{}")
          ),
          households = BeamConfig.Matsim.Modules.Households(
            if (c.hasPathOrNull("households")) c.getConfig("households")
            else com.typesafe.config.ConfigFactory.parseString("households{}")
          ),
          linkStats = BeamConfig.Matsim.Modules.LinkStats(
            if (c.hasPathOrNull("linkStats")) c.getConfig("linkStats")
            else com.typesafe.config.ConfigFactory.parseString("linkStats{}")
          ),
          network = BeamConfig.Matsim.Modules.Network(
            if (c.hasPathOrNull("network")) c.getConfig("network")
            else com.typesafe.config.ConfigFactory.parseString("network{}")
          ),
          parallelEventHandling = BeamConfig.Matsim.Modules.ParallelEventHandling(
            if (c.hasPathOrNull("parallelEventHandling")) c.getConfig("parallelEventHandling")
            else com.typesafe.config.ConfigFactory.parseString("parallelEventHandling{}")
          ),
          planCalcScore = BeamConfig.Matsim.Modules.PlanCalcScore(
            if (c.hasPathOrNull("planCalcScore")) c.getConfig("planCalcScore")
            else com.typesafe.config.ConfigFactory.parseString("planCalcScore{}")
          ),
          plans = BeamConfig.Matsim.Modules.Plans(
            if (c.hasPathOrNull("plans")) c.getConfig("plans")
            else com.typesafe.config.ConfigFactory.parseString("plans{}")
          ),
          qsim = BeamConfig.Matsim.Modules.Qsim(
            if (c.hasPathOrNull("qsim")) c.getConfig("qsim")
            else com.typesafe.config.ConfigFactory.parseString("qsim{}")
          ),
          strategy = BeamConfig.Matsim.Modules.Strategy(
            if (c.hasPathOrNull("strategy")) c.getConfig("strategy")
            else com.typesafe.config.ConfigFactory.parseString("strategy{}")
          ),
          transit = BeamConfig.Matsim.Modules.Transit(
            if (c.hasPathOrNull("transit")) c.getConfig("transit")
            else com.typesafe.config.ConfigFactory.parseString("transit{}")
          ),
          vehicles = BeamConfig.Matsim.Modules.Vehicles(
            if (c.hasPathOrNull("vehicles")) c.getConfig("vehicles")
            else com.typesafe.config.ConfigFactory.parseString("vehicles{}")
          )
        )
      }
    }

    def apply(c: com.typesafe.config.Config): BeamConfig.Matsim = {
      BeamConfig.Matsim(
        conversion = BeamConfig.Matsim.Conversion(
          if (c.hasPathOrNull("conversion")) c.getConfig("conversion")
          else com.typesafe.config.ConfigFactory.parseString("conversion{}")
        ),
        modules = BeamConfig.Matsim.Modules(
          if (c.hasPathOrNull("modules")) c.getConfig("modules")
          else com.typesafe.config.ConfigFactory.parseString("modules{}")
        )
      )
    }
  }

  def apply(c: com.typesafe.config.Config): BeamConfig = {
    BeamConfig(
      beam = BeamConfig.Beam(
        if (c.hasPathOrNull("beam")) c.getConfig("beam") else com.typesafe.config.ConfigFactory.parseString("beam{}")
      ),
      matsim = BeamConfig.Matsim(
        if (c.hasPathOrNull("matsim")) c.getConfig("matsim")
        else com.typesafe.config.ConfigFactory.parseString("matsim{}")
      )
    )
  }

  private def $_L$_dbl(cl: com.typesafe.config.ConfigList): scala.List[scala.Double] = {
    import scala.collection.JavaConverters._
    cl.asScala.map(cv => $_dbl(cv)).toList
  }

  private def $_L$_str(cl: com.typesafe.config.ConfigList): scala.List[java.lang.String] = {
    import scala.collection.JavaConverters._
    cl.asScala.map(cv => $_str(cv)).toList
  }

  private def $_dbl(cv: com.typesafe.config.ConfigValue): scala.Double = {
    val u: Any = cv.unwrapped
    if (
      (cv.valueType != com.typesafe.config.ConfigValueType.NUMBER) ||
      !u.isInstanceOf[java.lang.Number]
    ) throw $_expE(cv, "double")
    u.asInstanceOf[java.lang.Number].doubleValue()
  }

  private def $_expE(cv: com.typesafe.config.ConfigValue, exp: java.lang.String) = {
    val u: Any = cv.unwrapped
    new java.lang.RuntimeException(
      cv.origin.lineNumber +
      ": expecting: " + exp + " got: " +
      (if (u.isInstanceOf[java.lang.String]) "\"" + u + "\"" else u)
    )
  }

  private def $_str(cv: com.typesafe.config.ConfigValue) =
    java.lang.String.valueOf(cv.unwrapped())
}
