package beam.utils.logging

trait LoggerWrapper {

  def error(messageTemplate: String, args: Any*): Unit

  def warn(messageTemplate: String, args: Any*): Unit

  def info(messageTemplate: String, args: Any*): Unit

  def debug(messageTemplate: String, args: Any*): Unit

  def reset(): Unit

}
