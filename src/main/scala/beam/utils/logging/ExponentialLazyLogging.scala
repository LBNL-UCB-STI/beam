package beam.utils.logging

trait ExponentialLazyLogging {
  lazy val logger: LoggerWrapper = new ExponentialLoggerWrapperImpl(getClass.getName)
}

object ExponentialLazyLogging extends ExponentialLazyLogging {

  def reset(): Unit = logger.reset()

}
