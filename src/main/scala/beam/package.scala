package object beam {
  import beam.agentsim._
  import beam.utils.OptionalUtils.JavaOptionals._
  import org.matsim.core.utils.misc.OptionalTime

  implicit class OptionalTimeExtension(optionalTime: OptionalTime) {

    def secondsWithDefaultOf(fallBack: Double): Double = {
      optionalTime.orElse(fallBack)
    }
  }
}
