package beam.integration

import beam.router.Modes.BeamMode
import beam.sim.BeamHelper
import com.typesafe.config.{Config, ConfigValueFactory}
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.{Matchers, WordSpecLike}

class MultinomialCustomConfigSpec
    extends WordSpecLike
    with Matchers
    with BeamHelper
    with IntegrationSpecCommon
    with LazyLogging {

  "Running beam with Multinomial ModeChoice custom config" must {
    "Prefer mode choice car type in positive values than negative values " in {

      val config1: Config = baseConfig
        .withValue(
          TestConstants.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
          ConfigValueFactory.fromAnyRef(TestConstants.MODE_CHOICE_MULTINOMIAL_LOGIT)
        )
        .withValue(
          "beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.car_intercept",
          ConfigValueFactory.fromAnyRef(100)
        )
        .resolve()

      val config2: Config = baseConfig
        .withValue(
          TestConstants.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
          ConfigValueFactory.fromAnyRef(TestConstants.MODE_CHOICE_MULTINOMIAL_LOGIT)
        )
        .withValue(
          "beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.car_intercept",
          ConfigValueFactory.fromAnyRef(-100)
        )
        .resolve()

      val carConfigPositive = new StartWithCustomConfig(config1)
      val carConfigNegative = new StartWithCustomConfig(config2)

      val countPositive = carConfigPositive.groupedCount.getOrElse(BeamMode.CAR.value, 0)
      val countNegative = carConfigNegative.groupedCount.getOrElse(BeamMode.CAR.value, 0)

      logger.debug("CAR __________>")
      logger.debug(s"Positive: $countPositive")
      logger.debug(s"Negative: $countNegative")
      logger.debug("__________________________________")

      countPositive should be >= countNegative
    }

    "Prefer mode choice bike type in positive values than negative values " in {

      val config1: Config = baseConfig
        .withValue(
          TestConstants.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
          ConfigValueFactory.fromAnyRef(TestConstants.MODE_CHOICE_MULTINOMIAL_LOGIT)
        )
        .withValue(
          "beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.bike_intercept",
          ConfigValueFactory.fromAnyRef(100)
        )
        .resolve()

      val config2: Config = baseConfig
        .withValue(
          TestConstants.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
          ConfigValueFactory.fromAnyRef(TestConstants.MODE_CHOICE_MULTINOMIAL_LOGIT)
        )
        .withValue(
          "beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.bike_intercept",
          ConfigValueFactory.fromAnyRef(-100)
        )
        .resolve()

      val bikeConfigPositive = new StartWithCustomConfig(config1)
      val bikeConfigNegative = new StartWithCustomConfig(config2)

      val countPositive = bikeConfigPositive.groupedCount.getOrElse("bike", 0)
      val countNegative = bikeConfigNegative.groupedCount.getOrElse("bike", 0)

      countPositive should be >= countNegative
    }

    "Prefer mode choice ride hailing type in positive values than negative values " in {

      val config1: Config = baseConfig
        .withValue(
          TestConstants.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
          ConfigValueFactory.fromAnyRef(TestConstants.MODE_CHOICE_MULTINOMIAL_LOGIT)
        )
        .withValue(
          "beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hail_intercept",
          ConfigValueFactory.fromAnyRef(100)
        )
        .resolve()

      val config2: Config = baseConfig
        .withValue(
          TestConstants.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
          ConfigValueFactory.fromAnyRef(TestConstants.MODE_CHOICE_MULTINOMIAL_LOGIT)
        )
        .withValue(
          "beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hail_intercept",
          ConfigValueFactory.fromAnyRef(-100)
        )
        .resolve()

      val rideConfigPositive = new StartWithCustomConfig(config1)
      val rideConfigNegative = new StartWithCustomConfig(config2)

      val countPositive = rideConfigPositive.groupedCount.getOrElse(BeamMode.RIDE_HAIL.value, 0)
      val countNegative = rideConfigNegative.groupedCount.getOrElse(BeamMode.RIDE_HAIL.value, 0)

      countPositive should be >= countNegative
    }

    "Prefer mode choice drive_transit type in positive values than negative values " in {

      val config1: Config = baseConfig
        .withValue(
          TestConstants.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
          ConfigValueFactory.fromAnyRef(TestConstants.MODE_CHOICE_MULTINOMIAL_LOGIT)
        )
        .withValue(
          "beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.drive_transit_intercept",
          ConfigValueFactory.fromAnyRef(100)
        )
        .resolve()

      val config2: Config = baseConfig
        .withValue(
          TestConstants.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
          ConfigValueFactory.fromAnyRef(TestConstants.MODE_CHOICE_MULTINOMIAL_LOGIT)
        )
        .withValue(
          "beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.drive_transit_intercept",
          ConfigValueFactory.fromAnyRef(-100)
        )
        .resolve()

      val transitConfigPositive = new StartWithCustomConfig(config1)
      val transitConfigNegative = new StartWithCustomConfig(config2)

      val countPositive =
        transitConfigPositive.groupedCount.getOrElse(BeamMode.DRIVE_TRANSIT.value, 0)
      val countNegative =
        transitConfigNegative.groupedCount.getOrElse(BeamMode.DRIVE_TRANSIT.value, 0)

      countPositive should be >= countNegative
    }

    "Prefer mode choice walk type in positive values than negative values " in {

      val config1: Config = baseConfig
        .withValue(
          TestConstants.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
          ConfigValueFactory.fromAnyRef(TestConstants.MODE_CHOICE_MULTINOMIAL_LOGIT)
        )
        .withValue(
          "beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.walk_intercept",
          ConfigValueFactory.fromAnyRef(100)
        )
        .resolve()

      val config2: Config = baseConfig
        .withValue(
          TestConstants.KEY_AGENT_MODAL_BEHAVIORS_MODE_CHOICE_CLASS,
          ConfigValueFactory.fromAnyRef(TestConstants.MODE_CHOICE_MULTINOMIAL_LOGIT)
        )
        .withValue(
          "beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.walk_intercept",
          ConfigValueFactory.fromAnyRef(-100)
        )
        .resolve()

      val walkConfigPositive = new StartWithCustomConfig(config1)
      val walkConfigNegative = new StartWithCustomConfig(config2)

      val countPositive = walkConfigPositive.groupedCount.getOrElse(BeamMode.WALK.value, 0)
      val countNegative = walkConfigNegative.groupedCount.getOrElse(BeamMode.WALK.value, 0)

      countPositive should be >= countNegative
    }

  }

}
