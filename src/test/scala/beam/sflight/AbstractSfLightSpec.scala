package beam.sflight

import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify, PoisonPill}
import akka.testkit.{ImplicitSender, TestKitBase}
import beam.router.BeamRouter
import beam.sim.common.GeoUtilsImpl
import beam.sim.{BeamScenario, BeamServices, BeamServicesImpl}
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{NetworkHelperImpl, SimRunnerForTest}
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.api.core.v01.population.{Activity, Plan}
import org.scalatest._
import org.scalatest.mockito.MockitoSugar

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps

class AbstractSfLightSpec(val name: String)
    extends WordSpecLike
    with TestKitBase
    with SimRunnerForTest
    with RouterForTest
    with Matchers
    with ImplicitSender
    with MockitoSugar {
  lazy implicit val system = ActorSystem(name, ConfigFactory.parseString("""
      |akka.test.timefactor=10""".stripMargin))

  def outputDirPath: String = basePath + "/" + testOutputDir + name
  def config: Config = testConfig("test/input/sf-light/sf-light.conf").resolve()

  def planToVec(plan: Plan): Vector[Activity] = {
    plan.getPlanElements.asScala
      .filter(_.isInstanceOf[Activity])
      .map(_.asInstanceOf[Activity])
      .toVector
  }
}
