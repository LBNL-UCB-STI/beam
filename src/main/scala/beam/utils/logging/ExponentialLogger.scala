package beam.utils.logging

import java.util.concurrent.ConcurrentHashMap

import beam.utils.logging.ExponentialLoggerWrapperImpl._
import org.slf4j.{Logger, LoggerFactory}

trait ExponentialLogger {
  val logger: LoggerWrapper = new ExponentialLoggerWrapperImpl(getClass.getName)
}

private class ExponentialLoggerWrapperImpl(name: String) extends LoggerWrapper {
  type Func = (String, Object *) => Unit

  private val logger: Logger = LoggerFactory.getLogger(name)

  override def error(messageTemplate: String, args: Object*): Unit = {
    if (logger.isErrorEnabled) {
      processMessage(messageTemplate, args)(logger.error)
    }
  }

  def warn(messageTemplate: String, args: Object*): Unit = {
    if (logger.isWarnEnabled) {
      processMessage(messageTemplate, args)(logger.warn)
    }
  }

  override def info(messageTemplate: String, args: Object*): Unit = {
    if (logger.isInfoEnabled) {
      processMessage(messageTemplate, args)(logger.info)
    }
  }

  override def debug(messageTemplate: String, args: Object*): Unit = {
    if (logger.isDebugEnabled) {
      processMessage(messageTemplate, args)(logger.debug)
    }
  }

  private def processMessage(messageTemplate: String, args: Object*)(func: Func): Unit = {
    val newValue = messages.merge(messageTemplate, 1, (counter, incValue) => counter + incValue)
    if (isNumberPowerOfTwo(newValue)) {
      func(messageTemplate, args)
    }
  }

}

private object ExponentialLoggerWrapperImpl {

  def isNumberPowerOfTwo(number: Int): Boolean = {
    number > 0 && ((number & (number - 1)) == 0)
  }

  private val messages: ConcurrentHashMap[String, Int] = new ConcurrentHashMap[String, Int]()

}
