package beam.agentsim.infrastructure.h3

case class H3Index(value: String) extends AnyVal {

  def resolution: Int = H3Wrapper.getResolution(value)

}
