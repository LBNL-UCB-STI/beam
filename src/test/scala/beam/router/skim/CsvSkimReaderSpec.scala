package beam.router.skim

import akka.event.Logging
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.skim.ODSkimmer.{ODSkimmerInternal, ODSkimmerKey}
import beam.sim.BeamHelper
import com.typesafe.scalalogging.{LazyLogging, Logger}
import org.matsim.api.core.v01.Id
import org.scalatest.{Assertion, FlatSpec, Matchers}

/**
  * This spec tests that CsvSkimReader reads skims correctly.
  */
class CsvSkimReaderSpec extends FlatSpec with Matchers with BeamHelper {

  "CsvSkimReader" must "read skims correctly" in {
    val skims =
      new CsvSkimReader("test/test-resources/beam/router/skim/skims.csv", ODSkimmer.fromCsv, getDummyLogger()).readAggregatedSkims
        .map {
          case (skimmerKey, skimmerInternal) =>
            (skimmerKey.asInstanceOf[ODSkimmerKey], skimmerInternal.asInstanceOf[ODSkimmerInternal])
        }

    assert(skims.size == 5, "not all lines read")

    assert(skims.keys.exists(x => x.mode.value.equalsIgnoreCase("CAR")), error("mode"))
    assert(skims.keys.exists(x => x.mode.value.equalsIgnoreCase("RIDE_HAIL")), error("mode"))
    assert(skims.keys.exists(x => x.originTaz == Id.create("101241", TAZ.getClass)), error("originTaz"))
    assert(skims.keys.exists(x => x.originTaz == Id.create("101245", TAZ.getClass)), error("originTaz"))
    assert(skims.keys.exists(x => x.destinationTaz == Id.create("101243", TAZ.getClass)), error("destinationTaz"))
    assert(skims.keys.exists(x => x.destinationTaz == Id.create("101246", TAZ.getClass)), error("destinationTaz"))

    checkSkimmerField[Int, ODSkimmerKey]("travelTimeInS", 0, 4, skims.keys, x => x.hour)
    checkSkimmerField[Double, ODSkimmerInternal]("cost", 3, 50, skims.values, x => x.cost)
    checkSkimmerField[Double, ODSkimmerInternal]("generalizedCost", 2, 10, skims.values, x => x.generalizedCost)
    checkSkimmerField[Double, ODSkimmerInternal]("distanceInM", 1175.0, 2500, skims.values, x => x.distanceInM)
    checkSkimmerField[Double, ODSkimmerInternal]("energy", 10, 2000, skims.values, x => x.energy)
    checkSkimmerField[Int, ODSkimmerInternal]("observations", 0, 50, skims.values, x => x.observations)
    checkSkimmerField[Int, ODSkimmerInternal]("iterations", 2, 5, skims.values, x => x.iterations)
    checkSkimmerField[Double, ODSkimmerInternal](
      "generalizedTimeInS",
      19.0,
      500,
      skims.values,
      x => x.generalizedTimeInS
    )

  }

  def error(field: String): String = {
    s"$field not read correctly"
  }

  def checkSkimmerField[T: Ordering, V](
    fieldName: String,
    minValue: T,
    maxValue: T,
    oDSkimmerInternals: Iterable[V],
    fieldMapping: V => T
  ): Assertion = {
    assert(oDSkimmerInternals.map(fieldMapping).min == minValue, error(fieldName))
    assert(oDSkimmerInternals.map(fieldMapping).max == maxValue, error(fieldName))
  }

  def getDummyLogger(): Logger = {
    new DummyLogging().log
  }

  class DummyLogging extends LazyLogging {
    def log: Logger = logger
  }

}
