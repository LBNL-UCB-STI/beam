package beam.integration

import beam.sim.BeamHelper
import com.typesafe.config.{Config, ConfigValueFactory}
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.{Matchers, WordSpecLike}

import scala.xml.transform.{RewriteRule, RuleTransformer}
import scala.xml.{Elem, Node}

object MultinomialCustomConfigSpec {
  case class Utility(name: String, utype: String, value: String)

  class CustomAlternative(alternativeName: String, utilityValues: Seq[Utility])
      extends RewriteRule {
    override def transform(n: Node): Seq[Node] = n match {
      case elem: Elem
          if elem.label == "alternative" && elem.attributes
            .exists(m => m.value.text.equals(alternativeName)) =>
        val utilityChild = utilityValues.map { uv =>
          <param name={uv.name} type={uv.utype}>{uv.value}</param>
        }
        val utility = <utility>{utilityChild}</utility>
        elem.copy(child = utility)
      case n => n
    }
  }

  def fullXml(parameters: Node) = <modeChoices>
      <lccm>
        <name>Latent Class Choice Model</name>
        <parameters>lccm-long.csv</parameters>
      </lccm>
      <mnl>
        <name>Multinomial Logit</name>
        <parameters>
          {parameters}
        </parameters>
      </mnl>
    </modeChoices>

  val baseXml = <multinomialLogit name="mnl">
    <alternative name="car">
      <utility>
        <param name="intercept" type="INTERCEPT">0.0</param>
        <param name="cost" type="MULTIPLIER">-0.5</param>
        <param name="time" type="MULTIPLIER">-0.001</param>
      </utility>
    </alternative>
    <alternative name="walk_transit">
      <utility>
        <param name="intercept" type="INTERCEPT">0.0</param>
        <param name="cost" type="MULTIPLIER">-0.5</param>
        <param name="time" type="MULTIPLIER">-0.001</param>
        <param name="transfer" type="MULTIPLIER">-1.4</param>
      </utility>
    </alternative>
    <alternative name="drive_transit">
      <utility>
        <param name="intercept" type="INTERCEPT">0.0</param>
        <param name="cost" type="MULTIPLIER">-0.5</param>
        <param name="time" type="MULTIPLIER">-0.001</param>
        <param name="transfer" type="MULTIPLIER">-1.4</param>
      </utility>
    </alternative>
    <alternative name="ride_hailing">
      <utility>
        <param name="intercept" type="INTERCEPT">0.0</param>
        <param name="cost" type="MULTIPLIER">-0.5</param>
        <param name="time" type="MULTIPLIER">-0.001</param>
      </utility>
    </alternative>
    <alternative name="walk">
      <utility>
        <param name="intercept" type="INTERCEPT">0.0</param>
        <param name="cost" type="MULTIPLIER">-0.5</param>
        <param name="time" type="MULTIPLIER">-0.001</param>
      </utility>
    </alternative>
    <alternative name="bike">
      <utility>
        <param name="intercept" type="INTERCEPT">0.0</param>
        <param name="cost" type="MULTIPLIER">-0.5</param>
        <param name="time" type="MULTIPLIER">-0.001</param>
      </utility>
    </alternative>
  </multinomialLogit>

}

class MultinomialCustomConfigSpec
    extends WordSpecLike
    with Matchers
    with BeamHelper
    with IntegrationSpecCommon
    with LazyLogging {

  "Running beam with Multinomial ModeChoice custom config" must {
    "Prefer mode choice car type in positive values than negative values " ignore {

      val transformer1 = new RuleTransformer(
        new MultinomialCustomConfigSpec.CustomAlternative(
          "car",
          Seq(
            MultinomialCustomConfigSpec.Utility("intercept", "INTERCEPT", "100.0"),
            MultinomialCustomConfigSpec.Utility("cost", "MULTIPLIER", "100.0"),
            MultinomialCustomConfigSpec.Utility("time", "MULTIPLIER", "100.0")
          )
        )
      )

      val transformer2 = new RuleTransformer(
        new MultinomialCustomConfigSpec.CustomAlternative(
          "car",
          Seq(
            MultinomialCustomConfigSpec.Utility("intercept", "INTERCEPT", "-100"),
            MultinomialCustomConfigSpec.Utility("cost", "MULTIPLIER", "-100"),
            MultinomialCustomConfigSpec.Utility("time", "MULTIPLIER", "-100")
          )
        )
      )

      val transformed1 = transformer1(MultinomialCustomConfigSpec.baseXml)
      val transformed2 = transformer2(MultinomialCustomConfigSpec.baseXml)

      val routeConfig1 = (MultinomialCustomConfigSpec.fullXml(transformed1).toString())
      val routeConfig2 = (MultinomialCustomConfigSpec.fullXml(transformed2).toString())

      val config1: Config = baseConfig
        .withValue(
          "beam.agentsim.agents.modalbehaviors.modeChoiceClass",
          ConfigValueFactory.fromAnyRef("ModeChoiceMultinomialLogitTest")
        )
        .withValue(
          "beam.agentsim.agents.modalbehaviors.modeChoiceParametersFile",
          ConfigValueFactory.fromAnyRef(routeConfig1)
        )
        .resolve()

      val config2: Config = baseConfig
        .withValue(
          "beam.agentsim.agents.modalbehaviors.modeChoiceClass",
          ConfigValueFactory.fromAnyRef("ModeChoiceMultinomialLogitTest")
        )
        .withValue(
          "beam.agentsim.agents.modalbehaviors.modeChoiceParametersFile",
          ConfigValueFactory.fromAnyRef(routeConfig2)
        )
        .resolve()

      val carConfigPositive = new StartWithCustomConfig(config1)
      val carConfigNegative = new StartWithCustomConfig(config2)

      val countPositive = carConfigPositive.groupedCount.get("car").getOrElse(0);
      val countNegative = carConfigNegative.groupedCount.get("car").getOrElse(0);

      logger.debug("CAR __________>")
      logger.debug(s"Positive: $countPositive")
      logger.debug(s"Negative: $countNegative")
      logger.debug("__________________________________")

      countPositive should be >= countNegative
    }

    "Prefer mode choice bike type in positive values than negative values " ignore {

      val transformer1 = new RuleTransformer(
        new MultinomialCustomConfigSpec.CustomAlternative(
          "bike",
          Seq(
            MultinomialCustomConfigSpec.Utility("intercept", "INTERCEPT", "100"),
            MultinomialCustomConfigSpec.Utility("cost", "MULTIPLIER", "100"),
            MultinomialCustomConfigSpec.Utility("time", "MULTIPLIER", "100")
          )
        )
      )

      val transformer2 = new RuleTransformer(
        new MultinomialCustomConfigSpec.CustomAlternative(
          "bike",
          Seq(
            MultinomialCustomConfigSpec.Utility("intercept", "INTERCEPT", "-100"),
            MultinomialCustomConfigSpec.Utility("cost", "MULTIPLIER", "-100"),
            MultinomialCustomConfigSpec.Utility("time", "MULTIPLIER", "-100")
          )
        )
      )

      val transformed1 = transformer1(MultinomialCustomConfigSpec.baseXml)
      val transformed2 = transformer2(MultinomialCustomConfigSpec.baseXml)

      val routeConfig1 = (MultinomialCustomConfigSpec.fullXml(transformed1).toString())
      val routeConfig2 = (MultinomialCustomConfigSpec.fullXml(transformed2).toString())

      val config1: Config = baseConfig
        .withValue(
          "beam.agentsim.agents.modalbehaviors.modeChoiceClass",
          ConfigValueFactory.fromAnyRef("ModeChoiceMultinomialLogitTest")
        )
        .withValue(
          "beam.agentsim.agents.modalbehaviors.modeChoiceParametersFile",
          ConfigValueFactory.fromAnyRef(routeConfig1)
        )
        .resolve()

      val config2: Config = baseConfig
        .withValue(
          "beam.agentsim.agents.modalbehaviors.modeChoiceClass",
          ConfigValueFactory.fromAnyRef("ModeChoiceMultinomialLogitTest")
        )
        .withValue(
          "beam.agentsim.agents.modalbehaviors.modeChoiceParametersFile",
          ConfigValueFactory.fromAnyRef(routeConfig2)
        )
        .resolve()

      val bikeConfigPositive = new StartWithCustomConfig(config1)
      val bikeConfigNegative = new StartWithCustomConfig(config2)

      val countPositive = bikeConfigPositive.groupedCount.get("bike").getOrElse(0);
      val countNegative = bikeConfigNegative.groupedCount.get("bike").getOrElse(0);

//      println("Bike __________>")
//      println("Positive: " + countPositive)
//      println("Negative: " + countNegative)
//      println("__________________________________")

      countPositive should be >= countNegative
    }

    "Prefer mode choice ride hailing type in positive values than negative values " ignore {

      val transformer1 = new RuleTransformer(
        new MultinomialCustomConfigSpec.CustomAlternative(
          "ride_hailing",
          Seq(
            MultinomialCustomConfigSpec.Utility("intercept", "INTERCEPT", "100"),
            MultinomialCustomConfigSpec.Utility("cost", "MULTIPLIER", "100"),
            MultinomialCustomConfigSpec.Utility("time", "MULTIPLIER", "100")
          )
        )
      )

      val transformer2 = new RuleTransformer(
        new MultinomialCustomConfigSpec.CustomAlternative(
          "ride_hailing",
          Seq(
            MultinomialCustomConfigSpec.Utility("intercept", "INTERCEPT", "-100"),
            MultinomialCustomConfigSpec.Utility("cost", "MULTIPLIER", "-100"),
            MultinomialCustomConfigSpec.Utility("time", "MULTIPLIER", "-100")
          )
        )
      )

      val transformed1 = transformer1(MultinomialCustomConfigSpec.baseXml)
      val transformed2 = transformer2(MultinomialCustomConfigSpec.baseXml)

      val routeConfig1 = (MultinomialCustomConfigSpec.fullXml(transformed1).toString())
      val routeConfig2 = (MultinomialCustomConfigSpec.fullXml(transformed2).toString())

      val config1: Config = baseConfig
        .withValue(
          "beam.agentsim.agents.modalbehaviors.modeChoiceClass",
          ConfigValueFactory.fromAnyRef("ModeChoiceMultinomialLogitTest")
        )
        .withValue(
          "beam.agentsim.agents.modalbehaviors.modeChoiceParametersFile",
          ConfigValueFactory.fromAnyRef(routeConfig1)
        )
        .resolve()

      val config2: Config = baseConfig
        .withValue(
          "beam.agentsim.agents.modalbehaviors.modeChoiceClass",
          ConfigValueFactory.fromAnyRef("ModeChoiceMultinomialLogitTest")
        )
        .withValue(
          "beam.agentsim.agents.modalbehaviors.modeChoiceParametersFile",
          ConfigValueFactory.fromAnyRef(routeConfig2)
        )
        .resolve()

      val rideConfigPositive = new StartWithCustomConfig(config1)
      val rideConfigNegative = new StartWithCustomConfig(config2)

      val countPositive = rideConfigPositive.groupedCount.get("ride_hailing").getOrElse(0);
      val countNegative = rideConfigNegative.groupedCount.get("ride_hailing").getOrElse(0);

//      println("Ride Hailing __________>")
//      println("Positive: " + countPositive)
//      println("Negative: " + countNegative)
//      println("__________________________________")

      countPositive should be >= countNegative
    }

    "Prefer mode choice drive_transit type in positive values than negative values " ignore {

      val transformer1 = new RuleTransformer(
        new MultinomialCustomConfigSpec.CustomAlternative(
          "drive_transit",
          Seq(
            MultinomialCustomConfigSpec.Utility("intercept", "INTERCEPT", "100"),
            MultinomialCustomConfigSpec.Utility("cost", "MULTIPLIER", "100"),
            MultinomialCustomConfigSpec.Utility("time", "MULTIPLIER", "100")
          )
        )
      )

      val transformer2 = new RuleTransformer(
        new MultinomialCustomConfigSpec.CustomAlternative(
          "drive_transit",
          Seq(
            MultinomialCustomConfigSpec.Utility("intercept", "INTERCEPT", "-100"),
            MultinomialCustomConfigSpec.Utility("cost", "MULTIPLIER", "-100"),
            MultinomialCustomConfigSpec.Utility("time", "MULTIPLIER", "-100")
          )
        )
      )

      val transformed1 = transformer1(MultinomialCustomConfigSpec.baseXml)
      val transformed2 = transformer2(MultinomialCustomConfigSpec.baseXml)

      val routeConfig1 = (MultinomialCustomConfigSpec.fullXml(transformed1).toString())
      val routeConfig2 = (MultinomialCustomConfigSpec.fullXml(transformed2).toString())

      val config1: Config = baseConfig
        .withValue(
          "beam.agentsim.agents.modalbehaviors.modeChoiceClass",
          ConfigValueFactory.fromAnyRef("ModeChoiceMultinomialLogitTest")
        )
        .withValue(
          "beam.agentsim.agents.modalbehaviors.modeChoiceParametersFile",
          ConfigValueFactory.fromAnyRef(routeConfig1)
        )
        .resolve()

      val config2: Config = baseConfig
        .withValue(
          "beam.agentsim.agents.modalbehaviors.modeChoiceClass",
          ConfigValueFactory.fromAnyRef("ModeChoiceMultinomialLogitTest")
        )
        .withValue(
          "beam.agentsim.agents.modalbehaviors.modeChoiceParametersFile",
          ConfigValueFactory.fromAnyRef(routeConfig2)
        )
        .resolve()

      val transitConfigPositive = new StartWithCustomConfig(config1)
      val transitConfigNegative = new StartWithCustomConfig(config2)

      val countPositive = transitConfigPositive.groupedCount.get("drive_transit").getOrElse(0);
      val countNegative = transitConfigNegative.groupedCount.get("drive_transit").getOrElse(0);

//      println("Transit __________>")
//      println("Positive: " + countPositive)
//      println("Negative: " + countNegative)
//      println("__________________________________")

      countPositive should be >= countNegative
    }

    "Prefer mode choice walk type in positive values than negative values " ignore {

      val transformer1 = new RuleTransformer(
        new MultinomialCustomConfigSpec.CustomAlternative(
          "walk",
          Seq(
            MultinomialCustomConfigSpec.Utility("intercept", "INTERCEPT", "100"),
            MultinomialCustomConfigSpec.Utility("cost", "MULTIPLIER", "100"),
            MultinomialCustomConfigSpec.Utility("time", "MULTIPLIER", "100")
          )
        )
      )

      val transformer2 = new RuleTransformer(
        new MultinomialCustomConfigSpec.CustomAlternative(
          "walk",
          Seq(
            MultinomialCustomConfigSpec.Utility("intercept", "INTERCEPT", "-100"),
            MultinomialCustomConfigSpec.Utility("cost", "MULTIPLIER", "-100"),
            MultinomialCustomConfigSpec.Utility("time", "MULTIPLIER", "-100")
          )
        )
      )

      val transformed1 = transformer1(MultinomialCustomConfigSpec.baseXml)
      val transformed2 = transformer2(MultinomialCustomConfigSpec.baseXml)

      val routeConfig1 = (MultinomialCustomConfigSpec.fullXml(transformed1).toString())
      val routeConfig2 = (MultinomialCustomConfigSpec.fullXml(transformed2).toString())

      val config1: Config = baseConfig
        .withValue(
          "beam.agentsim.agents.modalbehaviors.modeChoiceClass",
          ConfigValueFactory.fromAnyRef("ModeChoiceMultinomialLogitTest")
        )
        .withValue(
          "beam.agentsim.agents.modalbehaviors.modeChoiceParametersFile",
          ConfigValueFactory.fromAnyRef(routeConfig1)
        )
        .resolve()

      val config2: Config = baseConfig
        .withValue(
          "beam.agentsim.agents.modalbehaviors.modeChoiceClass",
          ConfigValueFactory.fromAnyRef("ModeChoiceMultinomialLogitTest")
        )
        .withValue(
          "beam.agentsim.agents.modalbehaviors.modeChoiceParametersFile",
          ConfigValueFactory.fromAnyRef(routeConfig2)
        )
        .resolve()

      val walkConfigPositive = new StartWithCustomConfig(config1)
      val walkConfigNegative = new StartWithCustomConfig(config2)

      val countPositive = walkConfigPositive.groupedCount.get("walk").getOrElse(0);
      val countNegative = walkConfigNegative.groupedCount.get("walk").getOrElse(0);

//      println("WAlk __________>")
//      println("Positive: " + countPositive)
//      println("Negative: " + countNegative)
//      println("__________________________________")

      countPositive should be >= countNegative
    }

  }

}
